## 2024-05-18 - Avoid over-synchronization on UI Thread
**Learning:** Found an anti-pattern where an object locked by `synchronized(historyLock)` in a background thread blocks the UI thread if the same lock is taken during listener notifications or EDT execution.
**Action:** Always make sure that listener notifications executed via `invokeLater` do NOT happen inside a `synchronized` block. Wait, the `CommandHistoryService` already extracts the `event` first and then calls `notifyListeners` outside of `synchronized(historyLock)`. That's good.

## 2024-05-18 - Replacing ArrayDeque with CircularBuffer
**Learning:** Found an opportunity to improve performance and reduce memory churn. The application uses `ArrayDeque` to maintain fixed-size sliding windows (e.g., in `LifecycleEventLog` and `CommandHistoryService`). However, `ArrayDeque` performs structural modifications (growing/shrinking internal arrays, object allocations) when elements are constantly added and removed (e.g. `removeFirst()`). A pre-allocated ring buffer (CircularBuffer) can do this without any allocations after the initial setup. This makes logging and history recording faster and produces less garbage.
**Action:** Replace `ArrayDeque` with a custom `CircularBuffer` implementation where appropriate.

## 2024-05-18 - ClassNameCache for PSI Tree Traversals
**Learning:** String allocations during PSI tree traversals (such as using `.javaClass.simpleName` heavily) can cause significant CPU overhead and garbage generation. This is especially true for deep syntax trees and frequent analysis queries over PSI elements.
**Action:** Created `ClassNameCache` utilizing `ClassValue<String>` to cache simple class names, reducing allocations significantly during string-heavy PSI operations. Replaced instances of `.javaClass.simpleName` with `ClassNameCache.getSimpleName()` or `ClassNameCache.getLowercaseSimpleName()` across the codebase to cache PSI elements class names globally.
