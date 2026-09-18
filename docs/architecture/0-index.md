# IDE Index MCP Server - Architecture Documentation

Welcome to the architectural deep dive of the IDE Index MCP Server plugin. This documentation provides a comprehensive overview of how the plugin works under the hood, detailing its design decisions, components, and interactions with the IntelliJ Platform.

## Documentation Index

The architecture is broken down into the following key sections:

1. **[The Big Picture](1-big-picture.md)**: A high-level overview of the MCP Plugin, the integration between the JetBrains PSI (Program Structure Interface) and the MCP protocol, and the general flow of a request.
2. **[MCP Server & Transport](2-mcp-server-transport.md)**: Details on the embedded Ktor CIO Server, the dual-transport system (Streamable HTTP vs Legacy SSE), multi-project workspace support, and port management.
3. **[Tools & Pagination](3-tools-and-pagination.md)**: How tools are registered, the `AbstractMcpTool` base class, automatic VFS/PSI synchronization, the `PaginationService`, and the `SchemaBuilder`.
4. **[Language Handlers](4-language-handlers.md)**: The multi-language reflection-based architecture, `PluginDetectors`, static vs. reflection PSI access, and the `OptimizedSymbolSearch` system.
5. **[Lifecycle & State Management](5-lifecycle-and-state.md)**: A deep dive into Project Lifecycle Management, window focus state transitions, and memory management for open projects.

These documents are meant for contributors, maintainers, and anyone looking to understand the technical foundations of this plugin.
