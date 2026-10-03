package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap

/** Session/project-bound continuation storage for deterministic hierarchy BFS pages. */
@Service(Service.Level.APP)
class HierarchyContinuationRegistry @JvmOverloads constructor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val idGenerator: () -> String = Companion::newOpaqueCursor,
    private val serverEpoch: McpServerEpoch = McpServerEpoch.shared,
    private val maxTotalPointers: Long = MAX_TOTAL_POINTERS,
    private val maxTotalCharacters: Long = MAX_TOTAL_CHARACTERS,
    private val maxSnapshotsPerTraversal: Int = MAX_SNAPSHOTS_PER_TRAVERSAL
) : Disposable {

    internal sealed interface Continuation

    internal class ContinuationLimitException(message: String) : IllegalStateException(message)

    internal sealed interface CallWork

    /** Shared only by snapshots of the same exact declaration; synchronize when rebinding. */
    internal class MaterializedSymbolHandle(var symbolId: String? = null)

    internal data class PendingCallNode(
        val pointer: SmartPsiElementPointer<PsiElement>?,
        val snapshot: CallElement,
        val depth: Int,
        val modificationCount: Long = -1L,
        val handle: MaterializedSymbolHandle = MaterializedSymbolHandle(snapshot.symbolId)
    ) : CallWork

    internal data class PendingCallExpansion(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val depth: Int,
        val offset: Int,
        val nodeId: String = "n0"
    ) : CallWork

    internal data class CallContinuation(
        val rootPointer: SmartPsiElementPointer<PsiElement>,
        val root: CallElement,
        val frontier: List<CallWork>,
        val visited: Set<String>,
        val visitedPointers: List<SmartPsiElementPointer<PsiElement>> = emptyList(),
        val direction: String,
        val maxDepth: Int,
        val scope: BuiltInSearchScope,
        val excludeGenerated: Boolean,
        val rootModificationCount: Long = -1L,
        val rootHandle: MaterializedSymbolHandle = MaterializedSymbolHandle(root.symbolId)
    ) : Continuation

    internal sealed interface TypeWork

    internal data class PendingTypeNode(
        val pointer: SmartPsiElementPointer<PsiElement>?,
        val snapshot: TypeElement,
        val direction: TypeHierarchyDirection,
        val modificationCount: Long = -1L,
        val handle: MaterializedSymbolHandle = MaterializedSymbolHandle(snapshot.symbolId)
    ) : TypeWork

    internal data class PendingTypeExpansion(
        val pointer: SmartPsiElementPointer<PsiElement>,
        val direction: TypeHierarchyDirection,
        val offset: Int,
        val nodeId: String = "n0",
        val depth: Int = 0
    ) : TypeWork

    internal data class TypeContinuation(
        val rootPointer: SmartPsiElementPointer<PsiElement>,
        val root: TypeElement,
        val frontier: List<TypeWork>,
        val visited: Set<String>,
        val visitedPointers: List<SmartPsiElementPointer<PsiElement>> = emptyList(),
        val scope: BuiltInSearchScope,
        val excludeGenerated: Boolean,
        val rootModificationCount: Long = -1L,
        val rootHandle: MaterializedSymbolHandle = MaterializedSymbolHandle(root.symbolId)
    ) : Continuation

    private data class Entry(
        val project: WeakReference<Project>,
        val continuation: Continuation,
        val generation: Long,
        var lastAccessMillis: Long,
        val traversalId: String,
        val sequence: Long,
        val pointerCount: Long,
        val keyCharacters: Long,
        val characters: Long,
        val parentCursor: String?,
        val replayKey: String?
    )

    internal data class Lease(
        val continuation: Continuation,
        val generation: Long
    )

    private val entries = LinkedHashMap<String, Entry>()
    private val latestByTraversal = mutableMapOf<String, String>()
    private val counters = CacheCounters()
    private var storedPointers = 0L
    private var storedKeyCharacters = 0L
    private var storedCharacters = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
        require(maxTotalPointers > 0) { "maxTotalPointers must be positive" }
        require(maxTotalCharacters > 0) { "maxTotalCharacters must be positive" }
        require(maxSnapshotsPerTraversal > 0) { "maxSnapshotsPerTraversal must be positive" }
    }

    private val maintenance = CacheMaintenance(this, ::sweepExpired, ::removeProject)

    internal fun register(project: Project, continuation: Continuation): String {
        return register(project, serverEpoch.expectedForCurrentRequest(), continuation).getOrThrow()
    }

    /**
     * Registers only if the request still belongs to [expectedGeneration]. A hierarchy query can
     * outlive stop/start while it is inside a read action; without this check it could publish an
     * old-session cursor after [resetSession] had already cleared the registry.
     */
    internal fun register(
        project: Project,
        expectedGeneration: Long,
        continuation: Continuation,
        parentCursor: String? = null,
        replayKey: String? = null
    ): Result<String> = serverEpoch.ifCurrent(
        expectedEpoch = expectedGeneration,
        stale = ::staleGeneration
    ) {
        synchronized(this) {
            require(!project.isDisposed) { "Cannot register a hierarchy cursor for a disposed project" }
            val now = clock()
            evictExpired(now)
            val parent = if (parentCursor == null) null else {
                entries[parentCursor]?.takeIf { it.project.get() === project && it.generation == expectedGeneration }
                    ?: return@synchronized expired(parentCursor)
            }
            if (parentCursor != null && replayKey != null) {
                val replay = entries.entries.firstOrNull {
                    it.value.parentCursor == parentCursor && it.value.replayKey == replayKey &&
                        it.value.project.get() === project && it.value.generation == expectedGeneration
                }
                if (replay != null) {
                    val existingCursor = replay.key
                    val existingEntry = replay.value
                    entries.remove(existingCursor)
                    existingEntry.lastAccessMillis = now
                    entries[existingCursor] = existingEntry
                    return@synchronized Result.success(existingCursor)
                }
            }
            var cursor: String
            do cursor = idGenerator() while (entries.containsKey(cursor))
            val boundedContinuation = continuation.freeze()
            if (boundedContinuation.visitedKeyBudgetExceeded()) {
                return@synchronized Result.failure(
                    ContinuationLimitException(
                        "Hierarchy continuation exceeds the stable visited-key budget " +
                            "($MAX_VISITED_KEYS_PER_CONTINUATION keys / $MAX_VISITED_KEY_CHARS_PER_CONTINUATION characters). " +
                            "Narrow the scope or depth, or choose a more specific root, and start a new query."
                    )
                )
            }
            if (boundedContinuation.pointerCount() > MAX_POINTERS_PER_CONTINUATION) {
                return@synchronized Result.failure(
                    ContinuationLimitException(
                        "Hierarchy continuation exceeds the $MAX_POINTERS_PER_CONTINUATION smart-pointer limit. " +
                            "Narrow the scope or depth, or choose a more specific root, and start a new query."
                    )
                )
            }
            val pointers = boundedContinuation.pointerCount().toLong()
            val keyCharacters = boundedContinuation.visitedKeys().sumOf { it.length.toLong() }
            val characters = keyCharacters + boundedContinuation.snapshotCharacters()
            if (pointers > maxTotalPointers || characters > maxTotalCharacters) {
                return@synchronized Result.failure(ContinuationLimitException(
                    "Hierarchy continuation exceeds the cache's aggregate pointer/text budget. " +
                        "Narrow the hierarchy scope or depth and start a new query."
                ))
            }
            val traversalId = parent?.traversalId ?: cursor
            counters.insertions++
            entries[cursor] = Entry(
                WeakReference(project), boundedContinuation, expectedGeneration, now,
                traversalId, counters.insertions, pointers, keyCharacters, characters, parentCursor, replayKey
            )
            latestByTraversal[traversalId] = cursor
            storedPointers += pointers
            storedKeyCharacters += keyCharacters
            storedCharacters += characters
            evictTraversalHistory(traversalId, cursor)
            evictOverflow(cursor)
            Result.success(cursor)
        }
    }

    internal fun resolve(project: Project, cursor: String): Result<Continuation> {
        return resolveLease(project, cursor).map { it.continuation }
    }

    internal fun resolveLease(project: Project, cursor: String): Result<Lease> {
        val expectedGeneration = serverEpoch.expectedForCurrentRequest()
        return serverEpoch.ifCurrent(expectedGeneration, stale = { expired(cursor) }) {
            synchronized(this) {
                val now = clock()
                evictExpired(now)
                val entry = entries[cursor] ?: return@synchronized expiredLookup(cursor)
                val owner = entry.project.get()
                if (owner == null || owner.isDisposed) {
                    remove(cursor, CacheEvictionReason.PROJECT_CLOSED)
                    return@synchronized expiredLookup(cursor)
                }
                if (owner !== project || project.isDisposed || entry.generation != expectedGeneration) {
                    return@synchronized expiredLookup(cursor)
                }
                entries.remove(cursor)
                entry.lastAccessMillis = now
                entries[cursor] = entry
                counters.hits++
                Result.success(Lease(entry.continuation, entry.generation))
            }
        }
    }

    internal fun currentGeneration(): Long = serverEpoch.expectedForCurrentRequest()

    internal fun isCurrentGeneration(expectedGeneration: Long): Boolean =
        serverEpoch.isCurrent(expectedGeneration)

    /** Advances the shared MCP epoch and drops hierarchy continuations from the old session. */
    fun resetSession() {
        serverEpoch.advanceAndReset(::clearForSessionReset)
    }

    @Synchronized
    internal fun clearForSessionReset() {
        counters.removed(CacheEvictionReason.SESSION_RESET, entries.size)
        entries.clear()
        latestByTraversal.clear()
        storedPointers = 0
        storedKeyCharacters = 0
        storedCharacters = 0
    }

    @Synchronized
    internal fun sizeForTest(): Int {
        evictExpired(clock())
        return entries.size
    }

    override fun dispose() {
        maintenance.dispose()
        resetSession()
    }

    @Synchronized
    internal fun stats(): CacheStats =
        counters.snapshot(entries.size, storedPointers, storedCharacters)

    @Synchronized
    internal fun removeProject(project: Project) {
        entries.filterValues { it.project.get() === project }.keys.toList().forEach {
            remove(it, CacheEvictionReason.PROJECT_CLOSED)
        }
    }

    @Synchronized
    internal fun sweepExpired() {
        counters.maintenanceScans++
        evictExpired(clock())
    }

    private fun evictExpired(now: Long) {
        val expired = entries.mapNotNull { (cursor, entry) ->
            when {
                entry.project.get()?.isDisposed != false -> cursor to CacheEvictionReason.PROJECT_CLOSED
                now - entry.lastAccessMillis >= ttlMillis -> cursor to CacheEvictionReason.TTL
                else -> null
            }
        }
        expired.forEach { (cursor, reason) -> remove(cursor, reason) }
    }

    private fun evictTraversalHistory(traversalId: String, protectedCursor: String) {
        while (entries.values.count { it.traversalId == traversalId } > maxSnapshotsPerTraversal) {
            val oldest = entries.entries
                .filter { it.value.traversalId == traversalId && it.key != protectedCursor }
                .minByOrNull { it.value.sequence } ?: return
            remove(oldest.key, CacheEvictionReason.LRU)
        }
    }

    private fun evictOverflow(protectedCursor: String) {
        while (entries.size > maxEntries || storedPointers > maxTotalPointers || storedCharacters > maxTotalCharacters) {
            // Prefer obsolete replay snapshots to another traversal's newest frontier.
            val candidate = entries.entries.firstOrNull {
                it.key != protectedCursor && latestByTraversal[it.value.traversalId] != it.key
            } ?: entries.entries.firstOrNull { it.key != protectedCursor } ?: return
            remove(candidate.key, CacheEvictionReason.LRU)
        }
    }

    private fun remove(cursor: String, reason: CacheEvictionReason) {
        val removed = entries.remove(cursor) ?: return
        counters.removed(reason)
        storedPointers -= removed.pointerCount
        storedKeyCharacters -= removed.keyCharacters
        storedCharacters -= removed.characters
        if (latestByTraversal[removed.traversalId] == cursor) {
            val replacement = entries.entries.filter { it.value.traversalId == removed.traversalId }
                .maxByOrNull { it.value.sequence }
            if (replacement == null) latestByTraversal.remove(removed.traversalId)
            else latestByTraversal[removed.traversalId] = replacement.key
        }
    }

    /**
     * Keep exact pointer identity for the whole accepted traversal. Silently dropping old
     * pointers would make rename/line shifts turn previously visited declarations into new ones.
     * Persistent collections share unchanged history between immutable page snapshots.
     */
    private fun Continuation.freeze(): Continuation = when (this) {
        is TypeContinuation -> copy(
            frontier = frontier.toPersistentList(), visited = visited.toPersistentSet(),
            visitedPointers = visitedPointers.toPersistentList()
        )
        is CallContinuation -> copy(
            frontier = frontier.toPersistentList(), visited = visited.toPersistentSet(),
            visitedPointers = visitedPointers.toPersistentList()
        )
    }

    private fun Continuation.visitedKeys(): Set<String> = when (this) {
        is TypeContinuation -> visited
        is CallContinuation -> visited
    }

    private fun TypeElement.characters(): Long = name.length.toLong() + (file?.length ?: 0) + kind.length +
        (language?.length ?: 0) + (symbolId?.length ?: 0) + (nodeId?.length ?: 0) + (parentId?.length ?: 0) +
        supertypes.orEmpty().sumOf { it.characters() }

    private fun CallElement.characters(): Long = name.length.toLong() + file.length +
        (language?.length ?: 0) + (symbolId?.length ?: 0) + (nodeId?.length ?: 0) + (parentId?.length ?: 0) +
        children.orEmpty().sumOf { it.characters() }

    private fun Continuation.snapshotCharacters(): Long = when (this) {
        is TypeContinuation -> root.characters() + frontier.sumOf {
            if (it is PendingTypeNode) it.snapshot.characters() else 0L
        }
        is CallContinuation -> root.characters() + frontier.sumOf {
            if (it is PendingCallNode) it.snapshot.characters() else 0L
        }
    }

    /**
     * Stable keys preserve dedup when smart pointers are invalidated, so truncating them would
     * reintroduce duplicates. Reject an oversized continuation instead of silently weakening the
     * traversal invariant or retaining unbounded application-service memory.
     */
    private fun Continuation.visitedKeyBudgetExceeded(): Boolean {
        val keys = when (this) {
            is TypeContinuation -> visited
            is CallContinuation -> visited
        }
        if (keys.size > MAX_VISITED_KEYS_PER_CONTINUATION) return true
        var characters = 0L
        for (key in keys) {
            characters += key.length.toLong()
            if (characters > MAX_VISITED_KEY_CHARS_PER_CONTINUATION) return true
        }
        return false
    }

    /** Includes the root, bounded historical identity, and every live work item in the frontier. */
    private fun Continuation.pointerCount(): Int = when (this) {
        is TypeContinuation -> 1 + visitedPointers.size + frontier.count { work ->
            when (work) {
                is PendingTypeNode -> work.pointer != null
                is PendingTypeExpansion -> true
            }
        }
        is CallContinuation -> 1 + visitedPointers.size + frontier.count { work ->
            when (work) {
                is PendingCallNode -> work.pointer != null
                is PendingCallExpansion -> true
            }
        }
    }

    private fun <T> expired(cursor: String): Result<T> = Result.failure(
        IllegalArgumentException(
            "Hierarchy cursor '$cursor' expired, was evicted, or belongs to a different MCP session/project. " +
                "Run the hierarchy query again without cursor."
        )
    )

    @Synchronized
    private fun <T> expiredLookup(cursor: String): Result<T> {
        counters.misses++
        return expired(cursor)
    }

    private fun <T> staleGeneration(): Result<T> = Result.failure(
        IllegalStateException(
            "The MCP server session changed while the hierarchy page was being computed. " +
                "Run the hierarchy query again without cursor."
        )
    )

    companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1_000L
        internal const val MAX_POINTERS_PER_CONTINUATION = 8_192
        internal const val MAX_VISITED_KEYS_PER_CONTINUATION = 8_192
        internal const val MAX_VISITED_KEY_CHARS_PER_CONTINUATION = 256_000
        // At most the same ten maximum-sized page windows as a flat 5,000-result search.
        internal const val MAX_SNAPSHOTS_PER_TRAVERSAL =
            PaginationService.MAX_CACHED_RESULTS_PER_CURSOR / PaginationService.MAX_PAGE_SIZE
        // Retain at most the equivalent of twenty full contexts, even when 128 small pages fit.
        internal const val MAX_TOTAL_POINTERS =
            MAX_POINTERS_PER_CONTINUATION * 1L * PaginationService.MAX_CURSORS
        internal const val MAX_TOTAL_CHARACTERS =
            MAX_VISITED_KEY_CHARS_PER_CONTINUATION * 2L * PaginationService.MAX_CURSORS

        private val secureRandom = SecureRandom()
        private val base64Url = Base64.getUrlEncoder().withoutPadding()

        fun getInstance(): HierarchyContinuationRegistry =
            ApplicationManager.getApplication().getService(HierarchyContinuationRegistry::class.java)

        private fun newOpaqueCursor(): String {
            val bytes = ByteArray(18)
            secureRandom.nextBytes(bytes)
            return "hier_${base64Url.encodeToString(bytes)}"
        }
    }
}
