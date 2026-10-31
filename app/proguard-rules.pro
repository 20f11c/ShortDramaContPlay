# Shizuku UserService 由 Shizuku 按类名反射实例化，不能混淆/裁剪。
-keep class com.shortdrama.assist.ocr.ShizukuShellService { *; }
-keep class com.shortdrama.assist.ocr.** { *; }
