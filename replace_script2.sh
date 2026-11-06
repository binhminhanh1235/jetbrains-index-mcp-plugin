#!/bin/bash
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/\b\([a-zA-Z0-9_.]*\)\.javaClass\.simpleName\b/com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassNameCache.getSimpleName(\1.javaClass)/g' {} +
