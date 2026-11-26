package org.fptn.vpn.services.websocket;

/**
 * Exact JNI data class instantiated by libfptn_native_lib.so with signature (ILjava/lang/String;Ljava/lang/String;)V.
 */
public class NativeResponse {
    public int code;
    public String body;
    public String errorMessage;

    public NativeResponse() {}

    public NativeResponse(int code, String body, String errorMessage) {
        this.code = code;
        this.body = body;
        this.errorMessage = errorMessage;
    }
}
