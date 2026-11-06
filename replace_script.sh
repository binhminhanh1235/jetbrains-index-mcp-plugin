#!/bin/bash
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/\b\([a-zA-Z0-9_.]*\)\.javaClass\.simpleName\.lowercase()/com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassNameCache.getLowercaseSimpleName(\1.javaClass)/g' {} +
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/\b\([a-zA-Z0-9_.]*\)\.simpleName\.lowercase()/com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassNameCache.getLowercaseSimpleName(\1)/g' {} +
