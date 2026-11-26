# FPTN Client Android ProGuard / R8 Rules
-keepattributes SourceFile,LineNumberTable

# Preserve official FPTN native JNI bridge classes & callbacks called from libfptn_native_lib.so
-keep class org.fptn.vpn.services.websocket.** { *; }
-keep class org.fptn.vpn.services.snichecker.** { *; }

# Preserve Brotli decoder classes
-keep class org.brotli.dec.** { *; }

# Preserve libsu Shell classes
-keep class com.topjohnwu.superuser.** { *; }

# Preserve ZXing QR generator
-keep class com.google.zxing.** { *; }

# Preserve OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Preserve JNA native bindings for Stealth Root TUN (/dev/tun + TUNSETIFF ioctl)
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class com.example.vpn.root.RootStealthTunOpener$LibC { *; }
-keep class com.example.vpn.root.RootTunFdHelper { *; }
-keep class com.example.vpn.root.RootTunFdHelper$LibC { *; }
-dontwarn com.sun.jna.**


