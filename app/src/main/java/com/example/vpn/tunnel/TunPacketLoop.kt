package com.example.vpn.tunnel

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.example.core.logging.AppLogStore
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-throughput Layer-3 TUN packet loop driven by Os.poll() + Os.pipe() wakeup pipe:
 * - Batch inbound writes (`writeInboundBatch`) acquire the write lock and update `rxBytesTotal`
 *   ONCE per native WebSocket packet batch (matching official `FptnConnection.onMessageReceived`).
 * - Dedicated `writeLock` prevents contention with shutdown/resource teardown.
 * - Direct `FileInputStream.read` with batched TX counter flushes minimizes per-packet overhead.
 * - Detects fatal/revoked TUN write failures so the service can recover instead of blackholing silently.
 */
class TunPacketLoop(
    private val tunInterface: ParcelFileDescriptor,
    private val onOutboundPacket: (ByteArray, Int) -> Unit,
    private val onStopped: () -> Unit,
    private val onTunWriteFailure: ((Exception) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val rxBytesTotal = AtomicLong(0L)
    private val txBytesTotal = AtomicLong(0L)
    private val consecutiveWriteErrors = AtomicInteger(0)

    private val writeLock = Any()
    private val closeLock = Any()

    @Volatile
    private var wakeupReadFd: FileDescriptor? = null
    @Volatile
    private var wakeupWriteFd: FileDescriptor? = null
    @Volatile
    private var tunInStream: FileInputStream? = null
    @Volatile
    private var tunOutStream: FileOutputStream? = null
    @Volatile
    private var workerTid: Int = -1
    @Volatile
    private var desiredNicePriority: Int = android.os.Process.THREAD_PRIORITY_DEFAULT
    @Volatile
    private var readerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            val pipeFds = Os.pipe()
            wakeupReadFd = pipeFds[0]
            wakeupWriteFd = pipeFds[1]
            tunOutStream = FileOutputStream(tunInterface.fileDescriptor)
            consecutiveWriteErrors.set(0)

            readerThread = Thread({
                runPollLoop()
            }, "FptnTunPollLoop").apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
                start()
            }
        } catch (ex: Exception) {
            running.set(false)
            closeResources()
            throw ex
        }
    }

    /**
     * Dynamically adjusts the TUN packet reader thread's Linux nice priority without interrupting I/O.
     * Used by [com.example.vpn.root.AdaptiveRootBoostController] to boost throughput when the
     * phone is idle and yield immediately when foreground games or thermal throttling appear.
     */
    fun updateWorkerThreadPriority(nicePriority: Int) {
        val clamped = nicePriority.coerceIn(
            android.os.Process.THREAD_PRIORITY_DISPLAY,
            android.os.Process.THREAD_PRIORITY_BACKGROUND,
        )
        desiredNicePriority = clamped
        val tid = workerTid
        if (tid > 0 && running.get()) {
            runCatching { android.os.Process.setThreadPriority(tid, clamped) }
        }
    }

    private fun runPollLoop() {
        workerTid = android.os.Process.myTid()
        runCatching { android.os.Process.setThreadPriority(desiredNicePriority) }
        val tunFd = tunInterface.fileDescriptor
        val wakeFd = wakeupReadFd ?: return
        val inputStream = FileInputStream(tunFd).also { tunInStream = it }
        val packetBuffer = ByteArray(MTU_BUFFER_BYTES)

        val pollFds = arrayOf(
            StructPollfd().apply {
                fd = tunFd
                events = OsConstants.POLLIN.toShort()
            },
            StructPollfd().apply {
                fd = wakeFd
                events = OsConstants.POLLIN.toShort()
            },
        )

        var pendingTxBytes = 0L
        var pendingTxPackets = 0

        try {
            while (running.get()) {
                pollFds[0].revents = 0
                pollFds[1].revents = 0

                val ready = Os.poll(pollFds, POLL_TIMEOUT_MS)
                if (!running.get()) break
                if (ready <= 0) {
                    if (pendingTxBytes > 0L) {
                        txBytesTotal.addAndGet(pendingTxBytes)
                        pendingTxBytes = 0L
                        pendingTxPackets = 0
                    }
                    continue
                }

                // Wakeup pipe triggered -> exit loop immediately.
                if ((pollFds[1].revents.toInt() and OsConstants.POLLIN) != 0) {
                    break
                }

                if ((pollFds[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP)) != 0) {
                    break
                }

                if ((pollFds[0].revents.toInt() and OsConstants.POLLIN) != 0) {
                    val bytesRead = inputStream.read(packetBuffer)
                    if (bytesRead > 0) {
                        pendingTxBytes += bytesRead.toLong()
                        pendingTxPackets++
                        if (pendingTxPackets >= TX_COUNTER_FLUSH_BATCH) {
                            txBytesTotal.addAndGet(pendingTxBytes)
                            pendingTxBytes = 0L
                            pendingTxPackets = 0
                        }
                        onOutboundPacket(packetBuffer, bytesRead)
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            }
        } catch (ex: Exception) {
            if (running.get()) {
                AppLogStore.warn("TunLoop", "Цикл чтения TUN прерван: ${ex.message}")
            }
        } finally {
            if (pendingTxBytes > 0L) {
                txBytesTotal.addAndGet(pendingTxBytes)
            }
            closeResources()
            onStopped()
        }
    }

    /**
     * Writes an entire native WebSocket packet batch to the TUN interface under a single lock
     * and updates `rxBytesTotal` once per batch — matching official `FptnConnection.onMessageReceived`.
     */
    fun writeInboundBatch(packets: Array<ByteArray>): Long {
        if (!running.get() || packets.isEmpty()) return 0L
        var writtenBytes = 0L
        try {
            synchronized(writeLock) {
                val out = tunOutStream ?: return 0L
                for (pkt in packets) {
                    if (pkt.isNotEmpty()) {
                        out.write(pkt)
                        writtenBytes += pkt.size.toLong()
                    }
                }
            }
            if (writtenBytes > 0L) {
                rxBytesTotal.addAndGet(writtenBytes)
                consecutiveWriteErrors.set(0)
            }
        } catch (ex: Exception) {
            if (running.get()) {
                val errCount = consecutiveWriteErrors.incrementAndGet()
                if (errCount == 1 || errCount % 10 == 0) {
                    AppLogStore.warn("TunLoop", "Ошибка пакетной записи в TUN (#$errCount): ${ex.message}")
                }
                if (errCount == FATAL_WRITE_ERROR_THRESHOLD) {
                    onTunWriteFailure?.invoke(ex)
                }
            }
        }
        return writtenBytes
    }

    fun writeInboundPacket(packet: ByteArray) {
        if (!running.get() || packet.isEmpty()) return
        try {
            synchronized(writeLock) {
                tunOutStream?.write(packet)
            }
            rxBytesTotal.addAndGet(packet.size.toLong())
            consecutiveWriteErrors.set(0)
        } catch (ex: Exception) {
            if (running.get()) {
                val errCount = consecutiveWriteErrors.incrementAndGet()
                if (errCount == 1 || errCount % 10 == 0) {
                    AppLogStore.warn("TunLoop", "Ошибка записи в TUN (#$errCount): ${ex.message}")
                }
                if (errCount == FATAL_WRITE_ERROR_THRESHOLD) {
                    onTunWriteFailure?.invoke(ex)
                }
            }
        }
    }

    fun snapshotCounters(): Pair<Long, Long> = rxBytesTotal.get() to txBytesTotal.get()

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            if (Thread.currentThread() !== readerThread) {
                closeResources()
            }
            return
        }
        // Trigger immediate wakeup of Os.poll()
        runCatching {
            wakeupWriteFd?.let { fd ->
                Os.write(fd, byteArrayOf(1), 0, 1)
            }
        }
        val worker = readerThread
        if (worker != null && Thread.currentThread() !== worker) {
            runCatching { worker.join(600) }
            closeResources()
        }
    }

    private fun closeResources() {
        synchronized(closeLock) {
            synchronized(writeLock) {
                runCatching { tunOutStream?.close() }
                tunOutStream = null
            }
            runCatching { tunInStream?.close() }
            tunInStream = null
            runCatching { tunInterface.close() }
            runCatching { wakeupReadFd?.let { Os.close(it) } }
            wakeupReadFd = null
            runCatching { wakeupWriteFd?.let { Os.close(it) } }
            wakeupWriteFd = null
        }
    }

    companion object {
        private const val MTU_BUFFER_BYTES = 32767
        private const val POLL_TIMEOUT_MS = 1000
        private const val TX_COUNTER_FLUSH_BATCH = 8
        private const val FATAL_WRITE_ERROR_THRESHOLD = 5
    }
}
