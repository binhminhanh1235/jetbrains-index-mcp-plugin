1. **Optimize `ReflectionCache` for zero allocations on fast path.**
   - Change `getMethod` to have overloads for 0, 1, and 2 arguments to avoid vararg array allocation.
   - Use specialized key classes for 1 and 2 arguments instead of `joinToString`.
2. **Review other performance bottlenecks.**
   - I'll search for `ReflectionCache.getMethod` usages. Currently they mostly pass 0 or 1 arguments.
   - Example usages: `ReflectionCache.getMethod(ktClass.javaClass, "isInterface")`, `ReflectionCache.getMethod(lightClassUtils, "toLightMethods", PsiElement::class.java)`.
