## 2024-05-18 - Read Actions vs Write Actions
**Learning:** `ThreadingUtils.readActionSuspend` is not a thing, but `readAction` and `platformReadAction` are used frequently, along with a `suspendingReadAction` available inside `AbstractMcpTool`. The code uses `com.intellij.openapi.application.readAction` for coroutine read actions, which yields to write actions.
**Action:** Use `suspendingReadAction` inside tools, and `readAction` (or `com.intellij.openapi.application.readAction`) elsewhere when suspending read actions are needed.

## 2024-05-18 - ReflectionCache Object
**Learning:** The `ReflectionCache` is caching methods, but its keys are constructed via string interpolation: `if (paramTypes.isEmpty()) name else "$name(${paramTypes.joinToString(",") { it.name }})"`. This interpolates a new string and potentially allocates an array on every invocation, defeating the purpose of the fast-path cache hit.
**Action:** Optimize `ReflectionCache.getMethod` to avoid allocating an array and string. Actually, since varargs always allocates an array, the method should ideally avoid varargs or have overloads for 0, 1, and 2 parameters to avoid array allocations completely.
