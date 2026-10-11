# Feature Exploration Workflow: Cold-Start Navigation Without Vector Search

This guide outlines a systematic workflow for AI coding assistants (such as Claude Code, Antigravity, Cursor, or Windsurf) to discover, trace, and understand features in large or unfamiliar codebases using the deterministic tools provided by **IDE Index MCP Server**.

---

## 1. The Cold-Start Problem & Why Semantic Search Falls Short

When an engineer asks an AI agent to investigate a feature with only a vague functional description (e.g., *"where is webhook retry handled?"* or *"how does wallet balance deduction work during checkout?"*), teams often wonder if they need **Semantic Vector Search**.

In practice, for IDE-based agents, semantic vector search introduces significant drawbacks:
* **The Semantic Gap:** Real-world codebases use project-specific abbreviations and domain jargon (e.g., `DepotReconciler` instead of `SyncInventory`). Embedding models frequently miss these unless docstrings are immaculate.
* **Loss of Topology (Call Graph):** Vector similarity returns isolated text chunks. It cannot tell whether a chunk is an entry-point controller, an internal utility, or test code.
* **IDE Resource Overhead:** Indexing hundreds of thousands of code chunks into a local vector database consumes 500MB–1.5GB of JVM heap and spikes CPU usage on every branch switch.

Instead, the **IDE Index MCP Server** provides compiler-grade Program Structure Interface (PSI) and AST navigation that solves this problem with **zero background RAM overhead and 100% precision**.

---

## 2. The 4-Phase Exploration Workflow

```
[Phase 1: Orientation]
       │  ide_get_project_overview
       ▼  (Prune 80-90% of irrelevant modules)
[Phase 2: Boundary Hunting]
       │  ide_find_file, ide_find_symbol, ide_search_text
       ▼  (Identify entry point: Controller, Consumer, Job)
[Phase 3: AST Tracing]
       │  ide_call_hierarchy, ide_type_hierarchy, ide_find_implementations
       ▼  (Map execution path from boundary to business logic)
[Phase 4: Surgical Reading]
          ide_symbol_info, ide_read_file (bounded ranges)
```

### Phase 1: Architecture & Topology Orientation

**Goal:** Understand project layout and narrow down the scope from 50,000 files to the relevant module or package in <100ms.

* **Tool:** `ide_get_project_overview`
* **What to inspect:**
  * `modules`: Identify which module owns the relevant domain (e.g., `billing`, `notification`, `catalog`).
  * `frameworks`: Detect underlying frameworks (`Spring`, `Ktor`, `FastAPI`, `Express`, `Django`).
  * `topLevelPackages`: Identify primary namespaces to scope subsequent searches.

---

### Phase 2: Domain Expansion & Boundary Hunting

**Core Principle:** All business features enter a system through 1 of 4 boundaries:
1. **HTTP / RPC Endpoints** (REST Controllers, GraphQL Resolvers, gRPC services)
2. **Message / Event Consumers** (Kafka listeners, RabbitMQ consumers, SQS workers)
3. **Scheduled Tasks / Background Jobs** (Cron jobs, Quartz triggers, scheduled workers)
4. **UI / CLI Commands**

#### Step 2.1: Domain Vocabulary Expansion
Expand the vague request into 4–8 technical and domain synonyms:
* For *"wallet debit"*: `wallet`, `balance`, `deduct`, `debit`, `charge`, `ledger`.
* For *"webhook retry"*: `webhook`, `retry`, `backoff`, `failure`, `resend`, `dispatch`.

#### Step 2.2: Fast File Pattern Matching (Sub-millisecond)
* **Tool:** `ide_find_file`
* File names offer the highest signal-to-noise ratio:
  * Check Controllers: `*Wallet*Controller*`, `*Payment*Resource*`, `*Webhook*Route*`
  * Check Jobs / Consumers: `*Sync*Job*`, `*Order*Consumer*`, `*Retry*Worker*`

#### Step 2.3: Targeted Symbol Search
* **Tool:** `ide_find_symbol`
* Leverages IntelliJ's `StubIndex` to search function/class names across all languages. Supports camelCase shortcuts (e.g., `dB` matches `deductBalance`).

#### Step 2.4: Scoped Text Search (Fallback)
* **Tool:** `ide_search_text`
* When exact symbols are not known, search code with strict scoping:
  * Set `context = "code"`
  * Specify `filePattern` (e.g., `*Controller.*`) or `paths = ["billing/**"]`.

---

### Phase 3: Deterministic AST Tracing

Once a candidate Entry Point is located, **do not read the entire file**. Use AST relationships to map execution flow deterministically:

1. **Examine Structure:**
   * Call `ide_file_structure` on the entry-point file to inspect all endpoint methods.
2. **Trace Downward (Callees):**
   * Call `ide_call_hierarchy` with `direction = "callees"` and `depth = 3`.
   * Maps the direct invocation path: Controller → Application Service → Domain Logic → Repository/Event Publisher.
3. **Resolve Interfaces & Implementations:**
   * When encountering an interface or abstract method, call `ide_find_implementations` or `ide_type_hierarchy` to locate the active implementation immediately.
4. **Trace Upward (Callers):**
   * If an investigation starts from a domain service rather than an endpoint, call `ide_call_hierarchy` with `direction = "callers"` to find what triggers the operation.

---

### Phase 4: Surgical Reading

**Goal:** Zero token waste in the LLM context window.

* Call `ide_symbol_info` to get exact signatures and doc comments without loading file contents.
* Call `ide_read_file` with explicit line ranges (e.g., lines 45–90) to inspect only the critical business rule validation or calculation logic.

---

## 3. Walkthrough Example

**Scenario:** Investigating *"How does wallet balance deduction work when an order is paid?"*

| Step | MCP Tool Called | Arguments | What it discovers |
| :--- | :--- | :--- | :--- |
| **1. Orientation** | `ide_get_project_overview` | `{}` | Project has `billing-service` and `order-service` modules; uses Spring Boot. |
| **2. Boundary Hunt** | `ide_find_file` | `{"filePattern": "*Wallet*Controller*"}` | Finds `billing-service/src/.../WalletController.java`. |
| **3. Structure** | `ide_file_structure` | `{"file": ".../WalletController.java"}` | Identifies `POST /api/v1/wallet/debit` mapped to method `debitBalance()`. |
| **4. Call Hierarchy** | `ide_call_hierarchy` | `{"file": "...", "line": 42, "column": 10, "direction": "callees"}` | Reveals: `debitBalance()` calls `WalletService.processDebit()`, which calls `AccountPolicy.verifyBalance()` and `LedgerRepository.save()`. |
| **5. Targeted Read** | `ide_read_file` | `{"file": ".../AccountPolicy.java", "startLine": 30, "endLine": 55}` | Reads the exact 25 lines implementing the balance sufficiency check. |

---

## 4. Automation via Agent Skills

This workflow is packaged as an automated skill in Antigravity / Gemini CLI (`feature-explorer`):
* Automatically expands domain keywords.
* Executes boundary-first searches.
* Generates an end-to-end Execution Flow Map for the engineer.
