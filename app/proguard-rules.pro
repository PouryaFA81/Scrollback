# Keep Room entities and DAOs (handled by Room's generated code).

# --- v0.7 SFTP (sshj) / SMB (smbj) ---------------------------------------------------------
# BouncyCastle JCA provider classes are looked up reflectively by algorithm name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.pqc.jcajce.provider.** { *; }
# sshj instantiates algorithm factories by name.
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.security.** { *; }
-keep class com.hierynomus.ntlm.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
-dontwarn sun.security.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn com.jcraft.jzlib.**
-dontwarn org.newsclub.**
-dontwarn javax.el.**
-dontwarn com.sun.**

# commons-compress optional codecs we don't ship
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn java.lang.invoke.**

# v0.13 libVLC: native code calls back into these classes by name.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
