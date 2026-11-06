#!/bin/bash
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/val parentClass = parent?.javaClass?.simpleName ?: "Unknown"/val parentClass = parent?.let { com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassNameCache.getSimpleName(it.javaClass) } ?: "Unknown"/g' {} +
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e/val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e/g' {} +
find src/main/kotlin -type f -name "*.kt" -exec sed -i 's/cause?.javaClass?.simpleName ?: "unknown error"/cause?.let { com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ClassNameCache.getSimpleName(it.javaClass) } ?: "unknown error"/g' {} +
