# 4. Language Handlers

The IDE Index MCP Server operates across a wide variety of JetBrains IDEs (IntelliJ Ultimate, PyCharm, WebStorm, PhpStorm, RustRover, etc.). Each IDE has its own specific language plugins and unique PSI implementations.

## The Problem
A Java-based plugin running inside IntelliJ Ultimate can statically link against Java PSI classes (like `PsiClass` or `PsiMethod`). However, if that exact same plugin binary is installed in WebStorm, the Java PSI classes do not exist. Any attempt to load a class that statically references them will result in a fatal `NoClassDefFoundError`.

## The Multi-Language Architecture

To solve this, the plugin uses a highly dynamic, interface-driven handler architecture.

### The `LanguageHandlerRegistry`
All language-specific capabilities (type hierarchy, call hierarchy, finding implementations) are abstracted behind generic interfaces (e.g., `ImplementationsHandler`).

At startup, the `PluginDetectors` class securely probes the IDE environment to see which language plugins are active (using string IDs like `"com.intellij.java"` or `"PythonCore"`). 

If a language plugin is detected, the `LanguageHandlerRegistry` instantiates the corresponding language handlers and registers them. The MCP Tool definitions then query this registry at runtime to determine if a specific operation (like `ide_find_super_methods`) is supported for the current codebase.

### Static vs Reflection PSI Access

1. **Static Access (Java/Kotlin)**
   Because this plugin is compiled using the IntelliJ Platform SDK with Java/Kotlin dependencies, it can safely use direct static imports for Java and Kotlin PSI elements. Handlers in `handlers/java/JavaHandlers.kt` operate at maximum performance with full type safety.

2. **Reflection-Based Access (Python, JavaScript, Go, PHP, Rust)**
   To support other languages without introducing fatal compile-time dependencies, the handlers for these languages use deep Java reflection. 
   - They use `Class.forName("com.jetbrains.python.psi.PyFunction")` to load PSI classes at runtime.
   - Method calls to find base classes or super methods are executed via `Method.invoke()`.
   - While reflection introduces a microscopic performance overhead, it completely isolates the plugin from classpath issues, allowing a single unified `.zip` release to run perfectly in PyCharm, WebStorm, and RustRover.

## Optimized Symbol Search

When an AI agent needs to locate a class or function without knowing the file path, the plugin delegates to the `OptimizedSymbolSearch` system.

Instead of performing a slow, brute-force text search across all files, it taps into IntelliJ's powerful "Go to Symbol" internals (`ChooseByNameContributor`).
- It applies a `MinusculeMatcher`, the exact same engine used by the IDE UI, which understands CamelCase initials (e.g., matching `NPE` to `NullPointerException`), substrings, and minor typos.
- The search can dynamically filter results down to the specific languages currently supported by the active `LanguageHandlerRegistry`.
