package org.fptn.vpn.services.websocket;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Exact JNI bridge for official libfptn_native_lib.so HTTPS client (BoringSSL + custom session_id + SNI/Reality/Obfuscation).
 */
public class NativeHttpsClientImpl implements AutoCloseable {

    private final ReentrantReadWriteLock handleLock = new ReentrantReadWriteLock();
    private volatile long nativeHandle = 0L;

    public NativeHttpsClientImpl(
            String serverIP,
            int serverPort,
            String md5Fingerprint,
            String sni,
            String censorshipStrategyName
    ) {
        if (!FptnNativeLoader.ensureLoaded()) {
            throw new IllegalStateException(
                    "Failed to load libfptn_native_lib.so: " + FptnNativeLoader.getLastLoadError()
            );
        }
        this.nativeHandle = nativeCreate(
                serverIP,
                serverPort,
                sni,
                md5Fingerprint,
                censorshipStrategyName
        );
        if (this.nativeHandle == 0L) {
            throw new IllegalStateException("Failed to initialize native FPTN HTTPS client.");
        }
    }

    public NativeResponse get(String url, int timeoutSec) {
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (handle == 0L) {
                return new NativeResponse(0, "", "NativeHttpsClient already released");
            }
            return nativeGet(handle, url, timeoutSec);
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public NativeResponse post(String url, String body, int timeoutSec) {
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (handle == 0L) {
                return new NativeResponse(0, "", "NativeHttpsClient already released");
            }
            return nativePost(handle, url, body, timeoutSec);
        } finally {
            handleLock.readLock().unlock();
        }
    }

    public void release() {
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

    @Override
    public void close() {
        release();
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

    private native long nativeCreate(
            String server_ip,
            int server_port,
            String sni,
            String expected_md5_fingerprint,
            String censorship_strategy_name
    );

    private native void nativeDestroy(long nativeHandle);

    private native NativeResponse nativeGet(long nativeHandle, String url, int timeout);

    private native NativeResponse nativePost(long nativeHandle, String url, String requestBody, int timeout);
}
