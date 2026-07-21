# 5. Lifecycle & State Management

AI coding agents frequently jump between different projects, especially when operating on large monorepos or microservice architectures. In JetBrains IDEs, keeping multiple projects fully loaded consumes massive amounts of RAM and CPU.

To mitigate this, the MCP server implements a complex automated **Project Lifecycle Management** system.

## The State Machine

When a project is targeted by an MCP request for the first time, it is "enrolled" into the lifecycle manager. It then navigates through an automated state machine based on inactivity and window focus.

The states are:

1. **Active**: 
   - The user has focus on the project window, or an MCP tool has just executed.
   - Power Save Mode is OFF. 
   - Editors are open, PSI cache is hot, and background inspections run normally.

2. **Background**:
   - Triggered when focus is lost for `N` minutes.
   - Power Save Mode is turned ON to suspend CPU-heavy background inspections.
   - Editors remain open and the PSI cache remains loaded. The project is ready to instantly respond to an MCP request.

3. **Dormant**:
   - Triggered when the project is idle (no MCP calls) for another `N` minutes.
   - All editor tabs are automatically closed.
   - Memory management: Closing editors allows IntelliJ to free massive amounts of PSI and AST cache, drastically reducing memory footprint.
   - The project is still formally "open" in the IDE, but uses minimal resources.

4. **Closed**:
   - Triggered after extended dormancy (`N` minutes).
   - The project window is completely closed (`ProjectManager.getInstance().closeProject()`), releasing all associated memory.
   - **Auto-Reopen Guarantee**: If an AI agent later invokes an MCP tool targeting a closed project, the plugin intercepts the request, forces the IDE to re-open the project transparently, waits for indexing, and then executes the tool.

## Memory Protections

To prevent the AI agent from getting locked out:
- The lifecycle manager will **never** close the last open project. The final project is permitted to reach the `Dormant` state but will not transition to `Closed`. This ensures the embedded MCP Server remains alive and connected.

## Observability

The AI agent can observe and interact with this lifecycle using MCP tools:
- `ide_project_status`: Provides a snapshot of all managed and unmanaged open projects and their current states.
- `ide_lifecycle_log`: Queries an in-memory ring buffer of recent state transitions, detailing exactly *why* a project changed state (e.g., `trigger: focus_lost`, `trigger: mcp_call`).
