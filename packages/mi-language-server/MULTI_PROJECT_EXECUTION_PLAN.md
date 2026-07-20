# Multi-Project Support for the WSO2 MI Language Server — Architectural Execution Plan

## Context

The MI Language Server (a fork of Eclipse LemMinX) is architecturally **single-project**. One
`SynapseLanguageService` instance holds a single ambient `projectUri` (set once from
`InitializeParams.getRootPath()`), and ~81 custom `synapse/*` RPC endpoints resolve against that one
field. Today the IDE works around this by spawning a **separate LS process per project**, which wastes
resources and breaks a true multi-folder workspace experience (e.g. cross-project navigation).

A partial attempt exists. **Stage 1 (merged)** replaced the namespace-based XML *catalog* with per-folder
LemMinX **file associations**, so plain XSD validation is genuinely multi-root: each workspace folder's
`*.xml` binds to its own version-specific `synapse_config.xsd`. This is proven by
`CleanMultiRootValidationTest`. **Stage 2 (not started)** is only a design sketch —
`ProjectContext.java` and `WorkspaceManager.java` live under `docs/multi-workspace-support/resources/`
and are **not** compiled or referenced anywhere in `src`.

### Assessment of the existing pathway

The chosen direction is **architecturally correct — build on it**, with two corrections:

1. **Keep Stage 1 file associations.** It is the right LemMinX-native mechanism for per-folder schema
   isolation, already merged and tested. No rework.
2. **Adopt the `ProjectContext` + `WorkspaceManager` registry** (per-URI context, longest-prefix
   document→project resolution) — this is the standard multi-root LSP pattern (TS server, rust-analyzer).
   **But the sketch has a fatal flaw**: `ProjectContext` calls `ConnectorHolder.getInstance()` (a
   process-wide static singleton), so every context would share one connector list. The core of Stage 2
   is **de-globalizing shared singletons/statics into per-context instances** — the registry is worthless
   without it.

A ground-up rewrite is unwarranted: the on-disk caches are **already partitioned per project** (keyed by
`<projectName>_<hash(projectPath)>` under `~/.wso2-mi/...`), the dependency-download subsystems
(`DependencyDownloadManager`, `IntegrationProjectDownloadManager`, `ConnectorConfigService`) are already
multi-project-safe (method-local state, per-project dirs, recursive transitive resolution with cycle +
version-mismatch detection), and most helpers already thread `projectPath` as a parameter. The blockers
are a **bounded set of in-memory singletons/statics** plus the single `SynapseLanguageService` facade.

### Confirmed decisions
- **RPC dispatch**: server-side URI resolution (longest-prefix match on the document/param URI already in
  most requests); add an explicit `projectUri` only to endpoints that carry no URI. Minimal client change.
- **Inter-project deps**: keep current artifact-based resolution (from built `.car` artifacts under
  `~/.wso2-mi/integration-project-dependencies/<projectId>/Extracted`). Live open-source resolution is a
  later enhancement.
- **Try Out**: keep one global `TryOutManager` (single MI server port), rebind it to the project that
  initiated the try-out; document the single-instance limitation.

---

## Target Architecture

```
XMLLanguageServer (1 per process)
 ├─ workspaceSchemas: Map<folderUri, schemaDir>        [Stage 1 — keep]
 ├─ WorkspaceManager  (NEW, wired)                     [Stage 2]
 │    └─ Map<projectUri, ProjectContext>
 │         ProjectContext = { projectUri, isLegacy, version, synapseXsdPath,
 │                            connectorHolder(INSTANCE), inboundConnectorHolder,
 │                            connectorLoader, mediatorHandler, mediatorFactory,
 │                            resourceFinder, connectionHandler, expressionHelperProvider }
 └─ SynapseLanguageService (RPC facade, stateless re: project)
       every @JsonRequest → ctx = workspaceManager.resolve(param) → delegate to ctx's handlers
 GlobalServices (per-process, intentionally shared): TryOutManager, DynamicClassLoader,
       extensionPath, miServerPath
```

`SynapseLanguageService` stops owning project state and becomes a **dispatcher**: it resolves the
`ProjectContext` for each request and delegates to that context's handlers.

---

## Execution Plan

### Phase 0 — Promote the design classes into `src` (foundation)
- Move `ProjectContext.java` and `WorkspaceManager.java` from `docs/multi-workspace-support/resources/`
  into `org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/` (their declared
  package). Keep `WorkspaceManager` as-is (it is sound). **Fix `ProjectContext`** per Phase 1.

### Phase 1 — De-globalize the shared singletons/statics (the critical path)
Convert each of these from process-wide to per-`ProjectContext` instance. Most-severe first:

| Blocker | File | Change |
|---|---|---|
| `ConnectorHolder` static list + singleton | `connectors/ConnectorHolder.java:28,30,37,101` | Make it a normal instantiable class (drop `static` list + `getInstance`); one instance per `ProjectContext`. Update ~10 call sites (`ConnectorDownloadManager`, `AbstractResourceFinder`, `MediatorSchemaVisitor`, `AIConnectorFactory`, `AIConnectorHandler`) to receive the holder from context instead of `getInstance()`. |
| `MediatorFactoryFinder` static singleton, one-shot `init` (first-project-wins) | `syntaxTree/factory/mediators/MediatorFactoryFinder.java:155,162-170` | Make instantiable per context (holds that project's `factoryMap`, `miVersion`, `projectPath`, `connectorHolder`). Update callers `SyntaxTreeUtils`, `MediatorHandler`, `ExpressionCompletionUtils`, `IsolatedTryOutHandler`. |
| `loadedResourceFinder` static | `SynapseLanguageService.java:196,202-214` | Move the finder into `ProjectContext`; `SynapseDiagnosticsParticipant.java:1395` must obtain the finder for the document's project (see Phase 3 participant wiring). |
| `ExpressionCompletionsProvider.projectPath` static cache | `expression/ExpressionCompletionsProvider.java:62,153-164` | Remove the static cache; derive project from the document URI per call (keep the existing regex, drop the `if (isNotEmpty(projectPath)) return` cache). |
| `DirectoryTreeBuilder.projectPath` / `LegacyDirectoryTreeBuilder.projectPath` statics | `directoryTree/DirectoryTreeBuilder.java:72,74`; `.../legacyBuilder/LegacyDirectoryTreeBuilder.java:41` | Make instance-scoped or pass as parameter (these RPCs already receive a `WorkspaceFolder`). |
| `connectors.xsd` written to first-folder dir only | `SynapseLanguageService.java:591-600` | Generate per project into **that project's** `synapseXsdPath/mediators/connectors.xsd` from **that project's** connector holder. |

Serializer/expression singletons that hold only immutable/version-independent lookup data
(`MediatorSerializerFinder`, `FunctionRegistry`, `ExpressionCompletionUtils.FUNCTIONS`,
`SyntaxTreeGenerator.componentNames`) can stay shared — **but verify** none mutate per-project state
before leaving them global.

**Fix the `ProjectContext` sketch**: constructor must `new ConnectorHolder()` (not `getInstance()`), and
`initProject` must build a **per-context** `MediatorFactoryFinder`. Mirror the exact init order in
`SynapseLanguageService.init` (`SynapseLanguageService.java:248-278`): inbound holder → connector loader →
mediator handler → connection handler → mediator factory → expression helper → resource finder →
`Utils.copyXSDFiles`.

### Phase 2 — Lifecycle: create/destroy contexts on workspace events
- **`initialize`** (`XMLLanguageServer.java:123-187`): iterate `params.getWorkspaceFolders()` (fall back
  to `getRootPath()` for single-root clients). For each folder that is an MI project (guard with
  `Utils.isLegacyProject` / presence of `pom.xml` MI runtime), build a `ProjectContext` and register it in
  `WorkspaceManager`. **Remove the "temporary bridge"** `setSynapseXSDPath(workspaceSchemas.values()
  .iterator().next())` (`XMLLanguageServer.java:146`) once the facade resolves per request.
- **`didChangeWorkspaceFolders`** (`XMLWorkspaceService.java:87-116`): the file-association add/remove is
  already here — extend it to also `workspaceManager.addProject(...)` (create + `initProject`) on add and
  `removeProject(...)` (dispose) on remove, alongside the existing `addWorkspaceSchema`/`removeWorkspaceSchema`.
- **`didChangeWatchedFiles`** (`XMLWorkspaceService.java:118-134`): route connector `.zip` changes to the
  owning project's `connectorLoader.updateConnectors()` (resolve project from the changed file URI) instead
  of the single service.
- Keep `extensionPath`/`miServerPath` as process-global settings (read once in `initialize`).

### Phase 3 — Make `SynapseLanguageService` a dispatcher (server-side resolution)
- Add a private resolver: `ProjectContext resolve(<param>)` that extracts a URI from the request
  (`TextDocumentIdentifier.getUri()`, `param.getDocumentUri()`, `WorkspaceFolder.getUri()`, or an explicit
  `projectPath`/`customProjectUri` where present — e.g. `availableResources` already supports this) and
  calls `workspaceManager.getProjectForDocument(uri)`. Return a clear error/empty result on no match.
- Rewrite each `@JsonRequest` (`SynapseLanguageService.java`, ~81 methods) to `ctx = resolve(param)` then
  delegate to `ctx.getMediatorHandler()` / `ctx.getResourceFinder()` / `ctx.getConnectorHolder()` etc.,
  instead of the removed instance fields. Representative anchors: `syntaxTree:292`, `definition:~382`,
  `availableResources:~391`, `availableConnectors`, `dependencyTree:760`, `getOverviewModel:767`.
- For the handful of endpoints carrying **no** URI, add an explicit `projectUri` field to their param
  POJO (coordinate with the extension for those few only).
- **Diagnostics participant** (`SynapseDiagnosticsParticipant.java:1395`): resolve the `ProjectContext`
  from the validated document's URI to get that project's dependent-resource map (replacing the static
  `getLoadedDependentResources()`).

### Phase 4 — Try Out (single active, project-scoped)
- Keep one `TryOutManager` as a process-global concern (single MI server port). On a try-out request,
  (re)bind it to the initiating request's `ProjectContext` (project path + that project's connector
  holder). Surface a clear message if a try-out for another project is already active. Same for
  `DynamicClassLoader.updateClassLoader` — apply the initiating project's `deployment/libs`.

### Phase 5 — Client (VS Code extension) — Stage 3, minimal
- Send **all** workspace folders on `initialize` and folder add/remove events (most clients already do).
- No per-call `projectUri` needed for URI-bearing requests (server resolves). Add it only for the few
  no-URI endpoints identified in Phase 3.
- Keep `useAssociationSettings` defaulting to `true`.

---

## Key Challenges (call-outs)

1. **Connector version conflicts across projects.** Global `ConnectorHolder` makes Project A's
   `salesforce v1` collide with Project B's `salesforce v2`; `SchemaGenerate` iterates the whole holder.
   Per-context holders (Phase 1) resolve this — each project generates its own `connectors.xsd`.
2. **First-project-wins version lock.** `MediatorFactoryFinder.init` is a one-shot no-op after the first
   call, so a second project on a different MI version silently reuses the first's factories. Must become
   per-context.
3. **Inter-project dependencies.** Transitive `.car` resolution, cycle detection, and versioning-type
   mismatch checks already exist (`IntegrationProjectDownloadManager`, `AbstractResourceFinder`) and are
   per-project-safe. Risk: with artifact-based resolution, edits to an open dependency project are **not**
   reflected in the dependent until rebuild — accepted for now (documented limitation).
4. **Single MI-server port** for Try Out — accepted single-active limitation (Phase 4).
5. **Shared `DynamicClassLoader`** — DB-driver classloading is process-wide; concurrent projects with
   different drivers can collide. Rebind to the active project; full isolation deferred.
6. **Not every workspace folder is an MI project** — guard context creation with an MI-project check to
   avoid extracting schemas / loading connectors for unrelated folders.
7. **Thread-safety** — concurrent LSP requests for different projects; `WorkspaceManager` uses
   `ConcurrentHashMap`, and per-context handlers remove the shared-mutable-static races
   (`DirectoryTreeBuilder.projectPath`, `ExpressionCompletionsProvider.projectPath`).

---

## Critical Files
- **New (promote from docs)**: `customservice/synapse/ProjectContext.java`, `.../WorkspaceManager.java`
- **Facade/lifecycle**: `SynapseLanguageService.java` (init `248-278`, ~81 RPCs), `XMLLanguageServer.java`
  (`123-187`, remove bridge `146`, `287-300`), `XMLWorkspaceService.java` (`87-134`)
- **De-globalize**: `connectors/ConnectorHolder.java`, `syntaxTree/factory/mediators/MediatorFactoryFinder.java`,
  `expression/ExpressionCompletionsProvider.java`, `directoryTree/DirectoryTreeBuilder.java` (+ legacy),
  connector schema write `SynapseLanguageService.java:591-600`
- **Reuse as-is (per-project-safe)**: `Utils.updateSynapseFileAssociationSettings` / `copyXSDFiles`,
  `parser/DependencyDownloadManager`, `parser/IntegrationProjectDownloadManager`,
  `parser/connectorConfig/ConnectorConfigService`, `resourceFinder/*`, all `~/.wso2-mi/...` caches
- **Participant**: `contentmodel/participants/diagnostics/SynapseDiagnosticsParticipant.java:1395`

## Verification
1. **Extend `CleanMultiRootValidationTest`** (it already runs one LS over two folders, MI 4.3.0 + 4.4.0)
   into a real Stage-2 test: stop stubbing `SynapseLanguageService.init` to a no-op; instead register two
   real `ProjectContext`s and assert:
   - `workspaceManager.getProjectForDocument(uriInA)` / `(uriInB)` return distinct contexts.
   - A connector added to Project A's holder appears in A's `connectors.xsd` but **not** B's (proves
     per-context connector isolation — the current test mutates the global singleton, which must change).
   - `syntaxTree` / `definition` for a doc in A resolve against A's `projectUri`, and B's against B's.
2. **Regression**: `mvn -Dtest=CleanMultiRootValidationTest test` plus the full suite
   (`./mvnw clean verify`) — no single-project regressions.
3. **End-to-end in VS Code**: `mvn clean install -DskipTests`, copy the uber-jar into the extension's
   `ls/` folder, open a workspace with **two** MI projects on different MI versions (one depending on the
   other). Verify per project: correct diagnostics, connector completions, go-to-definition (incl.
   cross-project via built artifact), overview/dependency tree — each resolving to the correct project.
   Confirm one try-out at a time binds to the initiating project.
