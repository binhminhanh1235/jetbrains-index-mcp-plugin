## 2024-05-18 - Avoid over-synchronization on UI Thread
**Learning:** Found an anti-pattern where an object locked by `synchronized(historyLock)` in a background thread blocks the UI thread if the same lock is taken during listener notifications or EDT execution.
**Action:** Always make sure that listener notifications executed via `invokeLater` do NOT happen inside a `synchronized` block. Wait, the `CommandHistoryService` already extracts the `event` first and then calls `notifyListeners` outside of `synchronized(historyLock)`. That's good.
## 2024-05-18 - Replacing ArrayDeque with CircularBuffer
**Learning:** Found an opportunity to improve performance and reduce memory churn. The application uses `ArrayDeque` to maintain fixed-size sliding windows (e.g., in `LifecycleEventLog` and `CommandHistoryService`). However, `ArrayDeque` performs structural modifications (growing/shrinking internal arrays, object allocations) when elements are constantly added and removed (e.g. `removeFirst()`). A pre-allocated ring buffer (CircularBuffer) can do this without any allocations after the initial setup. This makes logging and history recording faster and produces less garbage.
**Action:** Replace `ArrayDeque` with a custom `CircularBuffer` implementation where appropriate.

## 2025-02-12 - Avoid per-element string allocations during hot-path PSI traversals
**Learning:** Found a performance optimization in `OptimizedSymbolSearch` where checking a parent class's name (`parent.javaClass.simpleName.lowercase()`) allocated strings at every level of the PSI tree traversal for every matched element. Because PSI trees can be deep and this occurs during bulk operations, caching the result per-class using `ClassValue<Boolean>` prevents garbage collector pressure and speeds up searches significantly.
**Action:** Use `ClassValue<T>` to cache expensive reflection or string manipulation computations on a per-class basis, especially inside tree traversal loops.
