package org.fptn.vpn.services.snichecker;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.fptn.vpn.services.websocket.FptnNativeLoader;

/**
 * Exact JNI bridge for official libfptn_native_lib.so SNI checker that tests SNI domains
 * directly against the selected FPTN server using the chosen bypass/obfuscation strategy.
 */
public class SniChecker implements AutoCloseable {

    private final ReentrantReadWriteLock handleLock = new ReentrantReadWriteLock();
    private volatile long nativeHandle = 0L;

    public SniChecker(
            String host,
            int port,
            String md5Fingerprint,
            String censorshipStrategy
    ) {
        if (!FptnNativeLoader.ensureLoaded()) {
            throw new IllegalStateException(
                    "Failed to load libfptn_native_lib.so: " + FptnNativeLoader.getLastLoadError()
            );
        }
        this.nativeHandle = nativeCreate(host, port, md5Fingerprint, censorshipStrategy);
    }

    public boolean isValid() {
        return nativeHandle != 0L;
    }

    public boolean checkSni(String sni) {
        if (sni == null || sni.isEmpty()) {
            return false;
        }
        handleLock.readLock().lock();
        try {
            final long handle = nativeHandle;
            if (handle == 0L) {
                return false;
            }
            return nativeCheckSni(handle, sni);
        } finally {
            handleLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
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

    @SuppressWarnings("deprecation")
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private native long nativeCreate(String host, int port, String md5Fingerprint, String censorshipStrategy);

    private native boolean nativeCheckSni(long nativeHandle, String sni);

    private native void nativeDestroy(long nativeHandle);
}
