# Rules applied to apps that use Krapfen and shrink/obfuscate their code.
#
# Native code looks up dev.krapfen.Native by name (JNI_OnLoad) and calls its
# onHostRequest/onLog methods; the external functions are bound by name too.
-keep class dev.krapfen.Native { *; }
-keepclasseswithmembernames class dev.krapfen.** {
    native <methods>;
}
