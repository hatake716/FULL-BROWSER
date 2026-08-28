# lorie (X サーバ) を組み込んだら、JNI から参照されるクラスを keep する
-keep class com.termux.x11.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn org.apache.commons.compress.**
