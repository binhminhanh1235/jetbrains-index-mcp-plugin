package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.withOriginalFileIdentity
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.rethrowIfControlFlow
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.VisibleForTesting
import java.lang.ref.WeakReference
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

@Service(Service.Level.APP)
class PaginationService @JvmOverloads constructor(
    private val coroutineScope: CoroutineScope,
    private val serverEpoch: McpServerEpoch = McpServerEpoch.shared
) : Disposable {

    companion object {
        const val TTL_MINUTES = 10L
        const val MAX_CURSORS = 20
        const val MAX_CACHED_RESULTS_PER_CURSOR = 5000
        const val SWEEP_INTERVAL_MINUTES = 5L
        const val DEFAULT_OVERCOLLECT = 500
        const val MAX_PAGE_SIZE = 500
        internal const val UNMATERIALIZED_SYMBOL_ID = "sym_unmaterialized"
        private const val SESSION_CHANGED_MESSAGE =
            "Search context invalidated because the MCP server session changed. Please re-search."

        fun getInstance(): PaginationService =
            ApplicationManager.getApplication().getService(PaginationService::class.java)
    }

    class CursorEntry(
        val id: String,
        val toolName: String,
        val results: MutableList<SerializedResult>,
        val seenKeys: MutableSet<String>,
        val searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        val psiModCount: Long,
        val generation: Long,
        val projectBasePath: String,
        val project: WeakReference<Project>?,
        val createdAt: Instant,
        @Volatile var lastAccessedAt: Instant,
        val metadata: Map<String, String> = emptyMap(),
        val serializedMetadata: Map<String, SerializedResult> = emptyMap(),
        var searchExhausted: Boolean = searchExtender == null,
        var resultLimitReached: Boolean = false,
        val mutex: Mutex = Mutex()
    ) {
        @Volatile var cachedResultCount = results.size
        @Volatile var cachedPointerCount = results.count { it.symbolPointer != null } +
            serializedMetadata.values.count { it.symbolPointer != null }
    }

    /**
     * Cached wire data plus an optional exact PSI target whose symbol handle is materialized only
     * when this item is actually returned. Pre-binding every over-collected search result can evict
     * handles that clients are still using before those cached results are ever visible.
     */
    data class SerializedResult(
        val key: String,
        val data: JsonElement,
        val symbolPointer: SmartPsiElementPointer<PsiElement>? = null,
        @Volatile var materializedSymbolId: String? = null
    ) {
        internal val exactSymbolPointer = symbolPointer?.withOriginalFileIdentity()
    }

    data class PaginationPage(
        val items: List<JsonElement>,
        val nextCursor: String?,
        val offset: Int,
        val pageSize: Int,
        val totalCollected: Int,
        val hasMore: Boolean,
        val stale: Boolean,
        val metadata: Map<String, String> = emptyMap(),
        internal val serializedItems: List<SerializedResult> = emptyList(),
        internal val serializedMetadata: Map<String, SerializedResult> = emptyMap(),
        internal val entryId: String? = null,
        internal val generation: Long? = null,
        internal val psiModCount: Long? = null
    )

    sealed interface GetPageResult {
        data class Success(val page: PaginationPage) : GetPageResult
        data class Error(val reason: CursorError, val message: String) : GetPageResult
    }

    enum class CursorError {
        MALFORMED,
        EXPIRED,
        NOT_FOUND,
        WRONG_PROJECT,
        WRONG_TOOL,
        SEARCH_INVALIDATED
    }

    private val cursors = ConcurrentHashMap<String, CursorEntry>()
    private val counters = CacheCounters()
    // The coroutine below already performs periodic sweeping; only add immediate project cleanup.
    private val maintenance = CacheMaintenance(this, null, ::removeProject)

    init {
        coroutineScope.launch {
            while (true) {
                delay(SWEEP_INTERVAL_MINUTES * 60 * 1000)
                sweepExpired()
            }
        }
    }

    /** Compatibility helper for focused registry tests; dispatchers use [McpServerEpoch] directly. */
    internal fun generationContext(expectedGeneration: Long = serverEpoch.capture()): ThreadContextElement<Long?> =
        serverEpoch.requestContext(expectedGeneration)

    private fun expectedGenerationForCurrentRequest(): Long =
        serverEpoch.expectedForCurrentRequest()

    data class DecodedCursor(val entryId: String, val offset: Int, val pageSize: Int? = null)

    fun encodeCursor(entryId: String, offset: Int, pageSize: Int? = null): String {
        val raw = if (pageSize != null) "$entryId:$offset:$pageSize" else "$entryId:$offset"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decodeCursor(token: String): DecodedCursor? {
        if (token.isEmpty()) return null
        return try {
            val decoded = Base64.getUrlDecoder().decode(token).toString(Charsets.UTF_8)
            // Format: "entryId:offset" (legacy) or "entryId:offset:pageSize"
            // entryId is a 32-char hex string (no colons), so we split from the right.
            val lastColon = decoded.lastIndexOf(':')
            if (lastColon < 0) return null
            val beforeLast = decoded.substring(0, lastColon)
            val afterLast = decoded.substring(lastColon + 1).toInt()

            val secondLastColon = beforeLast.lastIndexOf(':')
            val cursor = if (secondLastColon < 0) {
                // Legacy format: entryId:offset
                DecodedCursor(beforeLast, afterLast)
            } else {
                // New format: entryId:offset:pageSize
                val entryId = beforeLast.substring(0, secondLastColon)
                val offset = beforeLast.substring(secondLastColon + 1).toInt()
                DecodedCursor(entryId, offset, pageSize = afterLast)
            }
            cursor.takeIf {
                it.entryId.isNotBlank() &&
                    it.offset >= 0 &&
                    (it.pageSize == null || it.pageSize in 1..MAX_PAGE_SIZE)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun createCursor(
        toolName: String,
        results: List<SerializedResult>,
        seenKeys: Set<String>,
        searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        psiModCount: Long,
        projectBasePath: String,
        metadata: Map<String, String> = emptyMap(),
        serializedMetadata: Map<String, SerializedResult> = emptyMap()
    ): String = createCursorInternal(
        toolName = toolName,
        results = results,
        seenKeys = seenKeys,
        searchExtender = searchExtender,
        psiModCount = psiModCount,
        projectBasePath = projectBasePath,
        project = null,
        metadata = metadata,
        serializedMetadata = serializedMetadata,
        expectedGeneration = expectedGenerationForCurrentRequest()
    )

    /** Production overload: cursors belong to one exact open [Project] instance, not its path. */
    fun createCursor(
        toolName: String,
        results: List<SerializedResult>,
        seenKeys: Set<String>,
        searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        psiModCount: Long,
        project: Project,
        metadata: Map<String, String> = emptyMap(),
        serializedMetadata: Map<String, SerializedResult> = emptyMap()
    ): String {
        require(!project.isDisposed) { "Cannot create a pagination cursor for a disposed project" }
        return createCursorInternal(
            toolName = toolName,
            results = results,
            seenKeys = seenKeys,
            searchExtender = searchExtender,
            psiModCount = psiModCount,
            projectBasePath = ProjectResolver.normalizePath(project.basePath ?: ""),
            project = project,
            metadata = metadata,
            serializedMetadata = serializedMetadata,
            expectedGeneration = expectedGenerationForCurrentRequest()
        )
    }

    private fun createCursorInternal(
        toolName: String,
        results: List<SerializedResult>,
        seenKeys: Set<String>,
        searchExtender: (suspend (Set<String>, Int) -> List<SerializedResult>)?,
        psiModCount: Long,
        projectBasePath: String,
        project: Project?,
        metadata: Map<String, String>,
        serializedMetadata: Map<String, SerializedResult>,
        expectedGeneration: Long
    ): String {
        val entryId = UUID.randomUUID().toString().replace("-", "")
        val now = Instant.now()
        val boundedResults = ArrayList(results.take(MAX_CACHED_RESULTS_PER_CURSOR))
        val boundedSeenKeys = HashSet(seenKeys).apply {
            boundedResults.forEach { add(it.key) }
        }
        val entry = CursorEntry(
            id = entryId,
            toolName = toolName,
            results = boundedResults,
            seenKeys = boundedSeenKeys,
            searchExtender = searchExtender,
            psiModCount = psiModCount,
            generation = expectedGeneration,
            projectBasePath = projectBasePath,
            project = project?.let(::WeakReference),
            createdAt = now,
            lastAccessedAt = now,
            metadata = metadata,
            serializedMetadata = serializedMetadata,
            resultLimitReached = results.size > boundedResults.size ||
                (boundedResults.size == MAX_CACHED_RESULTS_PER_CURSOR && searchExtender != null)
        )

        return serverEpoch.ifCurrent(
            expectedEpoch = expectedGeneration,
            stale = { throw IllegalStateException(SESSION_CHANGED_MESSAGE) }
        ) {
            synchronized(this) {
                evictExpiredLocked(now)
                while (cursors.size >= MAX_CURSORS) {
                    val oldest = cursors.entries.minWithOrNull(
                        compareBy<Map.Entry<String, CursorEntry>>(
                            { it.value.lastAccessedAt },
                            { it.value.createdAt },
                            { it.key }
                        )
                    ) ?: break
                    cursors.remove(oldest.key, oldest.value)
                    counters.removed(CacheEvictionReason.LRU)
                }
                cursors[entryId] = entry
                counters.insertions++
                encodeCursor(entryId, 0)
            }
        }
    }

    /** Legacy path-only overload retained for headless unit tests and old in-process callers. */
    @VisibleForTesting
    internal suspend fun getPage(
        cursorToken: String,
        requestedPageSize: Int?,
        projectBasePath: String,
        currentModCount: Long,
        expectedToolName: String? = null,
        currentModCountAfterSuspension: () -> Long = { currentModCount }
    ): GetPageResult = getPageInternal(
        cursorToken = cursorToken,
        requestedPageSize = requestedPageSize,
        projectBasePath = projectBasePath,
        project = null,
        currentModCount = currentModCount,
        currentModCountAfterSuspension = currentModCountAfterSuspension,
        expectedToolName = expectedToolName,
        expectedGeneration = expectedGenerationForCurrentRequest()
    )

    /** Test-only compatibility overload. Production callers must provide their tool name. */
    @VisibleForTesting
    internal suspend fun getPage(
        cursorToken: String,
        requestedPageSize: Int?,
        project: Project,
        currentModCount: Long
    ): GetPageResult = getPageInternal(
        cursorToken = cursorToken,
        requestedPageSize = requestedPageSize,
        projectBasePath = ProjectResolver.normalizePath(project.basePath ?: ""),
        project = project,
        currentModCount = currentModCount,
        currentModCountAfterSuspension = { currentModCount },
        expectedToolName = null,
        expectedGeneration = expectedGenerationForCurrentRequest()
    )

    /** Production overload: validates the exact live project and originating tool. */
    suspend fun getPage(
        cursorToken: String,
        requestedPageSize: Int?,
        project: Project,
        currentModCount: Long,
        expectedToolName: String
    ): GetPageResult = getPageInternal(
        cursorToken = cursorToken,
        requestedPageSize = requestedPageSize,
        projectBasePath = ProjectResolver.normalizePath(project.basePath ?: ""),
        project = project,
        currentModCount = currentModCount,
        currentModCountAfterSuspension = {
            PsiModificationTracker.getInstance(project).modificationCount
        },
        expectedToolName = expectedToolName,
        expectedGeneration = expectedGenerationForCurrentRequest()
    )

    private suspend fun getPageInternal(
        cursorToken: String,
        requestedPageSize: Int?,
        projectBasePath: String,
        project: Project?,
        currentModCount: Long,
        currentModCountAfterSuspension: () -> Long,
        expectedToolName: String?,
        expectedGeneration: Long
    ): GetPageResult {
        val decoded = decodeCursor(cursorToken)
            ?: return GetPageResult.Error(CursorError.MALFORMED, "Invalid cursor format. Please re-search.")

        val pageSize = requestedPageSize
            ?: decoded.pageSize
            ?: return GetPageResult.Error(CursorError.MALFORMED, "No pageSize provided and cursor does not contain one. Please re-search.")
        if (pageSize !in 1..MAX_PAGE_SIZE) {
            return GetPageResult.Error(
                CursorError.MALFORMED,
                "pageSize must be between 1 and $MAX_PAGE_SIZE. Please re-search."
            )
        }
        val (entryId, offset) = decoded

        val entryAndError: Pair<CursorEntry?, GetPageResult.Error?> = serverEpoch.ifCurrent(
            expectedEpoch = expectedGeneration,
            stale = { null to searchInvalidated() }
        ) {
            synchronized(this) {
                val candidate = cursors[entryId]
                    ?: return@synchronized null to GetPageResult.Error(
                        CursorError.NOT_FOUND, "Cursor not found. Please re-search."
                    )
                if (candidate.generation != expectedGeneration) {
                    return@synchronized null to searchInvalidated()
                }

                val now = Instant.now()
                if (Duration.between(candidate.lastAccessedAt, now).toMinutes() >= TTL_MINUTES) {
                    cursors.remove(entryId, candidate)
                    counters.removed(CacheEvictionReason.TTL)
                    return@synchronized null to GetPageResult.Error(
                        CursorError.EXPIRED, "Cursor expired. Please re-search."
                    )
                }

                val owner = candidate.project?.get()
                if (candidate.project != null && (owner == null || owner.isDisposed)) {
                    cursors.remove(entryId, candidate)
                    counters.removed(CacheEvictionReason.PROJECT_CLOSED)
                    return@synchronized null to GetPageResult.Error(
                        CursorError.NOT_FOUND, "Cursor project was closed. Please re-search."
                    )
                }
                if (candidate.projectBasePath != projectBasePath ||
                    (project == null && candidate.project != null) ||
                    (project != null && owner !== project)
                ) {
                    return@synchronized null to GetPageResult.Error(
                        CursorError.WRONG_PROJECT, "Cursor belongs to a different project."
                    )
                }
                if (expectedToolName != null && candidate.toolName != expectedToolName) {
                    return@synchronized null to GetPageResult.Error(
                        CursorError.WRONG_TOOL,
                        "Cursor belongs to tool '${candidate.toolName}', not '$expectedToolName'. Please re-search."
                    )
                }

                candidate.lastAccessedAt = now
                candidate to null
            }
        }
        val (entry, lookupError) = entryAndError
        if (lookupError != null) {
            synchronized(this) { counters.misses++ }
            return lookupError
        }
        entry ?: return searchInvalidated()

        return entry.mutex.withLock {
            if (!isEntryCurrent(entry, expectedGeneration)) return@withLock searchInvalidated()
            val stale = entry.psiModCount != currentModCount
            val requestedEnd = offset.toLong() + pageSize.toLong()

            if (stale && offset >= entry.results.size && !entry.searchExhausted) {
                return@withLock GetPageResult.Error(
                    CursorError.SEARCH_INVALIDATED,
                    "The cached search results are exhausted and files have changed. Please re-search."
                )
            }

            // Cached rows remain usable after edits; never mix them with a fresh search.
            // Extend cache if needed and possible.
            // Use >= so that when we're exactly at the boundary we still probe the extender,
            // allowing an accurate hasMore answer without an extra client round-trip.
            val shouldExtend = !stale && requestedEnd >= entry.results.size.toLong()
                && entry.searchExtender != null
                && !entry.searchExhausted
                && entry.results.size < MAX_CACHED_RESULTS_PER_CURSOR
            if (shouldExtend) {
                if (!isEntryCurrent(entry, expectedGeneration)) {
                    return@withLock searchInvalidated()
                }
                try {
                    val remainingCapacity = MAX_CACHED_RESULTS_PER_CURSOR - entry.results.size
                    val extensionLimit = minOf(DEFAULT_OVERCOLLECT, remainingCapacity + 1)
                    val newResults = entry.searchExtender!!(entry.seenKeys.toSet(), extensionLimit)

                    val modCountAfterExtension = currentModCountAfterSuspension()
                    if (modCountAfterExtension != entry.psiModCount ||
                        !isEntryCurrent(entry, expectedGeneration)
                    ) {
                        return@withLock searchInvalidated()
                    }

                    var foundUnseenResult = false
                    for (result in newResults) {
                        if (!entry.seenKeys.add(result.key)) continue
                        foundUnseenResult = true
                        if (entry.results.size < MAX_CACHED_RESULTS_PER_CURSOR) {
                            entry.results.add(result)
                            entry.cachedResultCount = entry.results.size
                            if (result.symbolPointer != null) entry.cachedPointerCount++
                        } else {
                            entry.resultLimitReached = true
                        }
                    }
                    if (!foundUnseenResult) entry.searchExhausted = true
                    if (entry.results.size == MAX_CACHED_RESULTS_PER_CURSOR && !entry.searchExhausted) {
                        entry.resultLimitReached = true
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: IndexNotReadyException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (_: LinkageError) {
                    return@withLock GetPageResult.Error(
                        CursorError.SEARCH_INVALIDATED,
                        "Search context invalidated due to IDE/plugin API incompatibility. Please re-search."
                    )
                } catch (e: Exception) {
                    e.rethrowIfControlFlow()
                    return@withLock GetPageResult.Error(
                        CursorError.SEARCH_INVALIDATED,
                        "Search context invalidated due to file changes. Please re-search."
                    )
                }
            }

            val end = minOf(requestedEnd, entry.results.size.toLong()).toInt()
            if (offset.toLong() >= entry.results.size.toLong()) {
                val hasMore = entry.resultLimitReached || !entry.searchExhausted
                val page = PaginationPage(
                    items = emptyList(),
                    nextCursor = null,
                    offset = offset,
                    pageSize = 0,
                    totalCollected = entry.results.size,
                    hasMore = hasMore,
                    stale = stale,
                    metadata = entry.metadata,
                    serializedMetadata = entry.serializedMetadata,
                    entryId = entry.id,
                    generation = entry.generation,
                    psiModCount = entry.psiModCount
                )
                return@withLock returnPageIfCurrent(entry, expectedGeneration, page)
            }

            val serializedItems = entry.results.subList(offset, end).toList()
            val items = serializedItems.map { it.data }
            val actualPageSize = serializedItems.size
            val hasCachedResults = end < entry.results.size
            val canContinue = hasCachedResults ||
                (!stale && !entry.searchExhausted && entry.results.size < MAX_CACHED_RESULTS_PER_CURSOR)
            val hasMore = canContinue || entry.resultLimitReached || (stale && !entry.searchExhausted)
            val nextCursor = if (canContinue && actualPageSize > 0) {
                encodeCursor(entryId, offset + actualPageSize, pageSize)
            } else {
                null
            }

            val page = PaginationPage(
                items = items,
                nextCursor = nextCursor,
                offset = offset,
                pageSize = actualPageSize,
                totalCollected = entry.results.size,
                hasMore = hasMore,
                stale = stale,
                metadata = entry.metadata,
                serializedItems = serializedItems,
                serializedMetadata = entry.serializedMetadata,
                entryId = entry.id,
                generation = entry.generation,
                psiModCount = entry.psiModCount
            )
            returnPageIfCurrent(entry, expectedGeneration, page)
        }
    }

    /**
     * Revalidates cursor ownership around lazy PSI-backed symbol materialization. Edits make the
     * search snapshot stale, but surviving exact smart pointers can still supply current handles.
     */
    internal fun isPageSnapshotCurrent(
        page: PaginationPage,
        project: Project,
        expectedToolName: String
    ): Boolean {
        val expectedGeneration = serverEpoch.expectedForCurrentRequest()
        return serverEpoch.ifCurrent(expectedGeneration, stale = { false }) {
            synchronized(this) {
                val pageEntryId = page.entryId ?: return@synchronized false
                val pageGeneration = page.generation ?: return@synchronized false
                val entry = cursors[pageEntryId] ?: return@synchronized false
                pageGeneration == expectedGeneration &&
                    entry.generation == expectedGeneration &&
                    entry === cursors[pageEntryId] &&
                    entry.project?.get() === project &&
                    entry.toolName == expectedToolName &&
                    entry.psiModCount == page.psiModCount
            }
        }
    }

    private fun returnPageIfCurrent(
        entry: CursorEntry,
        expectedGeneration: Long,
        page: PaginationPage
    ): GetPageResult = serverEpoch.ifCurrent(expectedGeneration, stale = ::searchInvalidated) {
        synchronized(this) {
            if (isEntryCurrentLocked(entry, expectedGeneration)) {
                counters.hits++
                GetPageResult.Success(page)
            } else {
                searchInvalidated()
            }
        }
    }

    private fun isEntryCurrent(entry: CursorEntry, expectedGeneration: Long): Boolean =
        serverEpoch.ifCurrent(expectedGeneration, stale = { false }) {
            synchronized(this) { isEntryCurrentLocked(entry, expectedGeneration) }
        }

    private fun isEntryCurrentLocked(entry: CursorEntry, expectedGeneration: Long): Boolean {
        return entry.generation == expectedGeneration && cursors[entry.id] === entry
    }

    private fun searchInvalidated(): GetPageResult.Error = GetPageResult.Error(
        CursorError.SEARCH_INVALIDATED,
        SESSION_CHANGED_MESSAGE
    )

    private fun sweepExpired() {
        synchronized(this) {
            counters.maintenanceScans++
            evictExpiredLocked(Instant.now())
        }
    }

    private fun evictExpiredLocked(now: Instant) {
        cursors.entries.removeIf { (_, entry) ->
            val closed =
                entry.project?.get()?.isDisposed == true ||
                (entry.project != null && entry.project.get() == null)
            val expired = Duration.between(entry.lastAccessedAt, now).toMinutes() >= TTL_MINUTES
            if (closed || expired) {
                counters.removed(if (closed) CacheEvictionReason.PROJECT_CLOSED else CacheEvictionReason.TTL)
                true
            } else false
        }
    }

    @Synchronized
    internal fun removeProject(project: Project) {
        cursors.entries.removeIf { (_, entry) ->
            (entry.project?.get() === project).also {
                if (it) counters.removed(CacheEvictionReason.PROJECT_CLOSED)
            }
        }
    }

    @Synchronized
    internal fun stats(): CacheStats = counters.snapshot(
        entries = cursors.size,
        pointers = cursors.values.sumOf { it.cachedPointerCount.toLong() },
        results = cursors.values.sumOf { it.cachedResultCount.toLong() }
    )

    /** Advances the shared MCP epoch and drops ordinary-search cursors from the old session. */
    fun resetSession() {
        serverEpoch.advanceAndReset(::clearForSessionReset)
    }

    @Synchronized
    internal fun clearForSessionReset() {
        counters.removed(CacheEvictionReason.SESSION_RESET, cursors.size)
        cursors.clear()
    }

    @VisibleForTesting
    @Synchronized
    internal fun expireEntryForTesting(entryId: String) {
        cursors[entryId]?.lastAccessedAt = Instant.MIN
    }

    @VisibleForTesting
    @Synchronized
    internal fun sizeForTesting(): Int = cursors.size

    override fun dispose() {
        maintenance.dispose()
        resetSession()
    }

}
