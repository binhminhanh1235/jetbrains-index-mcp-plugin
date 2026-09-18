# 2. MCP Server & Transport

The IDE Index MCP Server plugin embeds its own HTTP server to handle the Model Context Protocol communication. It deliberately avoids using the IDE's built-in HTTP server to maintain strict control over ports, CORS, and threading.

## The Embedded Ktor Server

The plugin runs a custom **Ktor CIO (Coroutine I/O)** HTTP server. The server lifecycle is tied to the IntelliJ Application level via the `McpServerService`. 

- **Port Strategy**: Each JetBrains IDE has a unique default port to prevent conflicts if a user opens multiple IDEs simultaneously (e.g., IntelliJ on 29170, PyCharm on 29172).
- **Security**: The server binds to `127.0.0.1` by default to prevent external network access, though this can be configured to `0.0.0.0` for remote development setups like WSL.
- **Auto-restart**: If the port is changed in settings, the `McpServerService` automatically restarts the embedded server.

## Dual-Transport System

To maximize compatibility with both legacy and cutting-edge MCP clients, the server supports two transports concurrently:

### 1. Streamable HTTP Transport (MCP 2025-03-26)
This is the primary transport method. It is stateless and highly efficient.
- **Endpoint**: `POST /index-mcp/streamable-http`
- **Flow**: The client sends a JSON-RPC POST request, and the server responds directly with the JSON-RPC response. No session state or connection persistence is required.

### 2. Legacy SSE Transport (MCP 2024-11-05)
Maintained for backward compatibility and to support tools like the MCP Inspector.
- **Endpoint**: `GET /index-mcp/sse`
- **Flow**: The client connects to the SSE endpoint and receives an `endpoint` event containing a unique session URL (e.g., `/index-mcp?sessionId=123`). The client then sends `POST` requests to that session URL, and the server streams responses back through the open SSE connection.

## Multi-Project and Workspace Resolution

A significant architectural challenge is handling multiple open projects. An AI agent connects to a single global port, but the user might have three different project windows open.

The `ProjectResolver` handles this:
1. Every MCP tool request specifies a `project_path`.
2. The resolver checks the requested path against all open IDE projects (`ProjectManager.getInstance().openProjects`).
3. **Workspace Support**: JetBrains allows a single window to contain multiple modules with distinct content roots. The `ProjectResolver` scans not only the `basePath` of a project but also all module content roots. If `project_path` matches any module's root (or a subdirectory within it), that project is selected.
4. If no `project_path` is provided, and exactly one project is open, the resolver automatically falls back to it. If multiple projects are open, an error is returned detailing the `available_projects` so the agent can self-correct.
