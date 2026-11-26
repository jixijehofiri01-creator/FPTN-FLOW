package org.fptn.vpn.services.websocket;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Exact JNI bridge for official libfptn_native_lib.so WebSocket tunnel engine
 * (BoringSSL, YaFF, SNI/Reality browser fingerprints, Obfuscation, Rolling/Dual/Triple sliding tunnel).
 */
public class NativeWebSocketClientImpl {

    public interface Callbacks {
        void onOpen();
        void onMessageReceived(byte[][] packets);
        void onFailure();
        void onSocketOpened(int fd);
    }

    private static final ExecutorService FAILURE_DISPATCH_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fptn-ws-failure-dispatch");
                t.setDaemon(true);
                return t;
            });

    private final Callbacks callbacks;
    private final ReentrantReadWriteLock handleLock = new ReentrantReadWriteLock();
    private volatile boolean released = false;
    private volatile long nativeHandle = 0L;

    public NativeWebSocketClientImpl(
            String host,
            int port,
            String tunAddressIPv4,
            String tunAddressIPv6,
            String accessToken,
            String md5ServerFingerprint,
            String sniHostName,
            String clientVersion,
            String censorshipStrategyName,
            String connectionStrategyName,
            Callbacks callbacks
    ) {
        if (!FptnNativeLoader.ensureLoaded()) {
            throw new IllegalStateException(
                    "Failed to load libfptn_native_lib.so: " + FptnNativeLoader.getLastLoadError()
            );
        }
        this.callbacks = callbacks;
        this.nativeHandle = nativeCreate(
                host,
                port,
                tunAddressIPv4,
                tunAddressIPv6,
                sniHostName,
                accessToken,
                md5ServerFingerprint,
                clientVersion,
                censorshipStrategyName,
                connectionStrategyName
        );
        if (this.nativeHandle == 0L) {
            throw new IllegalStateException("Native FPTN engine failed to create WebSocket session.");
        }
    }

    public void start() {
        if (released) {
            return;
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (!released && handle != 0L && !nativeIsStarted(handle)) {
                nativeRun(handle);
            }
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public void stop() {
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (handle != 0L && nativeIsStarted(handle)) {
                nativeStop(handle);
            }
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public boolean isStarted() {
        if (released) {
            return false;
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            return !released && handle != 0L && nativeIsStarted(handle);
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public boolean send(byte[] data, long length) {
        if (released || data == null || length <= 0L) {
            return false;
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (!released && handle != 0L) {
                return nativeSend(handle, data, length);
            }
            return false;
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public void release() {
        released = true;
        // Stop native worker threads before taking the exclusive write lock to avoid callback deadlocks.
        stop();
        handleLock.writeLock().lock();
        try {
            final long handle = nativeHandle;
            if (handle != 0L) {
                nativeHandle = 0L;
                nativeDestroy(handle);
            }
        } finally {
            handleLock.writeLock().unlock();
        }
    }

    public String getIPv4Address() {
        if (released) {
            return "";
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (!released && handle != 0L) {
                String ip = nativeGetIPv4Address(handle);
                return ip != null ? ip : "";
            }
            return "";
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public String getIPv6Address() {
        if (released) {
            return "";
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (!released && handle != 0L) {
                String ip = nativeGetIPv6Address(handle);
                return ip != null ? ip : "";
            }
            return "";
        } finally {
            handleLock.readLock().unlock();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    // JNI callbacks invoked from libfptn_native_lib.so (names bound to C++ symbols)

    public void onOpenImpl() {
        if (callbacks != null) {
            callbacks.onOpen();
        }
    }

    public void onFailureImpl() {
        if (callbacks != null) {
            FAILURE_DISPATCH_EXECUTOR.execute(callbacks::onFailure);
        }
    }

    public void onMessageImpl(byte[][] packets) {
        if (callbacks != null && packets != null) {
            callbacks.onMessageReceived(packets);
        }
    }

    public void onSocketOpenedImpl(int fd) {
        if (callbacks != null) {
            callbacks.onSocketOpened(fd);
        }
    }

    // Native JNI entry points (do not rename or move package)

    private native long nativeCreate(
            String server_ip,
            int server_port,
            String tun_ipv4,
            String tun_ipv6,
            String sni,
            String access_token,
            String expected_md5_fingerprint,
            String client_version,
            String censorship_strategy_name,
            String connection_strategy_name
    );

    private native void nativeDestroy(long nativeHandle);

    private native boolean nativeRun(long nativeHandle);

    private native boolean nativeStop(long nativeHandle);

    private native boolean nativeSend(long nativeHandle, byte[] data, long length);

    private native boolean nativeIsStarted(long nativeHandle);

    private native String nativeGetIPv4Address(long nativeHandle);

    private native String nativeGetIPv6Address(long nativeHandle);
}
