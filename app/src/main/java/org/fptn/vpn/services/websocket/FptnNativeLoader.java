package org.fptn.vpn.services.websocket;

import android.os.Build;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe lazy loader for official libfptn_native_lib.so.
 * Safely reports unavailable inside JVM/Robolectric unit tests without crashing class initialization.
 */
public final class FptnNativeLoader {

    private static final AtomicBoolean loaded = new AtomicBoolean(false);
    private static volatile String loadError = null;

    private FptnNativeLoader() {}

    public static boolean ensureLoaded() {
        if (loaded.get()) {
            return true;
        }
        if ("robolectric".equalsIgnoreCase(Build.FINGERPRINT)) {
            loadError = "Native libfptn_native_lib.so skipped in Robolectric JVM environment";
            return false;
        }
        synchronized (FptnNativeLoader.class) {
            if (loaded.get()) {
                return true;
            }
            try {
                System.loadLibrary("fptn_native_lib");
                loaded.set(true);
                loadError = null;
                return true;
            } catch (Throwable t) {
                loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
                return false;
            }
        }
    }

    public static String getLastLoadError() {
        return loadError;
    }
}
