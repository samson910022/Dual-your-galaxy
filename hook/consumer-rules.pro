// Keeps the LSPosed entry point; the framework loads it via
// META-INF/xposed/java_init.list (rewritten on obfuscation by the rule below).
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
