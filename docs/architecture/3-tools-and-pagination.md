# 3. Tools & Pagination

The plugin exposes an array of features to the AI agent through MCP tools. This system is designed to be highly extensible and robust against the complexities of the IntelliJ threading model.

## Tool Registry and SchemaBuilder

All tools are registered in the `ToolRegistry`. Rather than manually maintaining JSON schemas, the plugin utilizes a fluent `SchemaBuilder`.

```kotlin
override val inputSchema = SchemaBuilder.tool()
    .projectPath()
    .file()
    .lineAndColumn()
    .intProperty("maxResults", "Maximum results to return.")
    .build()
```

The schema builder enforces consistency, ensures all descriptions are populated, and handles the intricacies of the MCP JSON Schema requirements.

## The `AbstractMcpTool` Base Class

Every tool extends `AbstractMcpTool`. This base class abstracts away critical boilerplate related to threading and synchronization.

### VFS/PSI Synchronization
When an AI agent modifies a file externally (e.g., via standard file I/O operations), the IntelliJ Virtual File System (VFS) and the PSI tree are immediately out of date. If a tool runs a search without synchronization, it will miss the new code.

Before `doExecute()` is called, `AbstractMcpTool` forces a VFS refresh and commits all documents (`PsiDocumentManager.getInstance(project).commitAllDocuments()`). This ensures the PSI perfectly reflects reality.

### Threading Guarantees
IntelliJ has strict threading rules. `AbstractMcpTool` ensures that reads are performed inside a `ReadAction` on background threads, preventing UI freezes while safely accessing the PSI.

## Pagination and the Search Processor Pattern

Many IDE queries (like finding all references of `String`) yield massive result sets. Sending 10,000 results in a single JSON-RPC response would crash the agent's context window.

### Processor Pattern
Instead of eagerly loading results (`findAll()`), the plugin uses IntelliJ's `Processor` interface. Tools stream results into memory and can terminate early once a page size limit is reached.

### Cursor-Based Pagination
The plugin implements a robust `PaginationService`:
- Tools return opaque, base64url-encoded cursor tokens containing `{entryId}:{offset}:{pageSize}`.
- Cursors are heavily cached in an LRU cache with an inactivity-based TTL.
- The `searchExtender` lambda pattern allows tools to lazily fetch the next batch of results when a cached page is exhausted.
- **Staleness Detection**: The pagination service checks the `PsiModificationTracker`. If the user (or the agent) modifies code while paginating, the response flags `stale: true`, warning the agent that the index has shifted since the first page was fetched.
