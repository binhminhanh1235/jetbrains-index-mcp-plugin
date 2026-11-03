#!/bin/bash
find src/main -name "*.kt" -type f -exec sed -i 's/\.javaClass\.getMethod(\(.*?\))/ReflectionCache.getMethod(\.javaClass, \1)/g' {} +
