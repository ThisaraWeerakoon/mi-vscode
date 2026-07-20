# Multi-Project Support for the MI Language Server — Architecture Assessment

*An architect's assessment of what it takes to move from "one language-server process per project" to "one process serving multiple MI projects", including the coupling analysis, the hard blockers, a target architecture, and a phased effort estimate.*

**Companion document:** [SYNAPSE_LS_FEATURE_ANALYSIS.md](SYNAPSE_LS_FEATURE_ANALYSIS.md) contains the full inventory and LS/non-LS classification of all 81 `synapse/*` endpoints. This document builds on those numbers rather than repeating them.

**Agreed scope for this assessment:**
- Multi-project = **one VS Code window with multiple MI projects as workspace folders**, served by a single LS process over a single client connection (not a machine-wide daemon shared by several windows).
- Mediator **try-out may remain "one active project at a time"** (the current server-swap behaviour), as long as it becomes project-aware.

---

## 1. Executive summary — the go/no-go answer

**Verdict: the re-architecture is justified and tractable. It does not require a rewrite.**

The headline numbers the decision hinges on:

- **Coupling is high in count**: 32 of the 73 non-LS endpoints (44%) depend on the LS analysis machinery (the DOM/syntax-tree pipeline), and roughly 30 endpoints implicitly assume the single stored `projectUri`.
- **But the coupling is concentrated, not smeared**: all of the per-project global state lives in **six classes plus one wiring point**. Everything else is either already per-instance (and becomes safe automatically) or already stateless.
- The 32-endpoint tree coupling is **not something to remove** — those features genuinely need to understand Synapse XML. The correct response is to make the tree machinery **project-scoped**, not to decouple the features from it.
- Estimated effort: **11–17 person-weeks of server work + ~2 person-weeks of VS Code extension work**, split into 4 phases, each of which leaves the product shippable. A big-bang rewrite was evaluated and rejected.

The single most important insight of this assessment: **multi-project is a *state* problem, not a *feature* problem.** The features are mostly fine; the process-wide singletons and the single-root initialization are what break.

---

## 2. The requirement, and what actually blocks it

### 2.1 How the server is single-project today

The lifecycle hard-codes "one process = one project" at four levels:

```
VS Code extension                         LS process
─────────────────                         ──────────────────────────────────────────
initialize(rootPath ──────────────────▶  XMLLanguageServer.initialize()
  = the ONE project)                        │ uses params.getRootPath()   ← level 1: single root
                                            │ (workspaceFolders IGNORED)
                                            ▼
                                          SynapseLanguageService.init(projectUri)
                                            │ stores projectUri, version,      ← level 2: instance
                                            │ legacy flag + ~10 collaborators     fields on the ONE
                                            │                                     service instance
                                            ▼
                                          ConnectorHolder.getInstance()      ← level 3: process-wide
                                          MediatorFactoryFinder.init(...)       singletons filled
                                          DynamicClassLoader.update(...)        with THIS project's
                                          static loadedResourceFinder = ...     data
                                            │
                                            ▼
                                          one temp XSD dir + one            ← level 4: process-wide
                                          connectors.xsd + one catalog.xml     validation config
```

- **Level 1** — [XMLLanguageServer.java:155](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLLanguageServer.java#L155) passes only the *deprecated* `params.getRootPath()` into `SynapseLanguageService.init`. If the client sends multiple workspace folders, they are ignored. The fork's own workspace-folder bookkeeping is literally commented out ([XMLWorkspaceService.java:88](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLWorkspaceService.java#L88)).
- **Level 2** — every per-project value (`projectUri`, `isLegacyProject`, `projectServerVersion`, the mediator handler, connector loader, resource finder, try-out manager, …) is an instance field on the **one** `SynapseLanguageService` ([SynapseLanguageService.java:216-234](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java#L216-L234)). `projectUri` alone is referenced 61 times in that file.
- **Level 3** — several collaborators escalate project state to **process-global** singletons/statics (§3.2 — these are the real blockers).
- **Level 4** — XML validation is configured process-wide for one project's MI version and connector set (§3.2, blocker B5).

So today, "multi-project" is achieved by running N processes. The requirement is to collapse that to one process with N *contexts*.

### 2.2 Why this matters more than the endpoint classification

The earlier analysis showed 32 non-LS endpoints consume the DOM/syntax-tree machinery. One might conclude "high coupling → huge refactor". That conclusion would be wrong, for a subtle reason:

> The syntax-tree pipeline (`Utils.getDOMDocument` → `SyntaxTreeGenerator.buildTree`) is **almost stateless**. It takes a file, returns a tree. Features calling it are *not* the problem — they'll work unchanged for any project whose file you hand them.
>
> The problem is the **three global lookups hiding inside that pipeline and around it**: the mediator factory dispatch consults a singleton initialized with *one* project's connectors and MI version; connector-aware features consult a singleton connector list; validation consults one process-wide schema catalog. Feed the pipeline a file from project B and it silently answers with project A's connector knowledge.

That is why the remediation below is measured in weeks, not months: the fix is to *scope* a handful of shared components, not to untangle 32 features.

---

## 3. Coupling analysis — the estimate that was asked for

### 3.1 Endpoint level (recap from the companion report, §8)

| | Count |
|---|---|
| Total `synapse/*` endpoints | **81** |
| LS-specific | **8** |
| Non-LS (tooling) | **73** |
| — of which coupled to the LS analysis machinery (DOM/syntax tree) | **32** (29 parse during the request, 3 serve tree-derived data) |
| — of which fully isolated | **41** |

Interpretation for this effort: the 41 isolated endpoints need only *request routing* changes (which project?); the 32 coupled ones need routing **plus** a project-scoped analysis kernel; the 8 LS-specific ones ride on LemMinX, which is already document-URI based.

### 3.2 State level — the blocker inventory (verified in code)

This is the decisive table. Severity: **BLOCKER** = multi-project is incorrect/broken while this exists; **REFACTOR** = must fix but mechanical; **OK** = harmless.

| # | Item | Where | Per-project state held | Blast radius | Severity |
|---|---|---|---|---|---|
| B1 | `ConnectorHolder` singleton | [ConnectorHolder.java:28-37](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectorHolder.java#L28-L37) | `private static List<Connector> connectors` — the loaded connector list is **static**, one per process | 13 `getInstance()` sites across 8 files; injected into MediatorFactoryFinder, MediatorHandler, ConnectionHandler, TryOutManager, SchemaGenerate, ConnectionFinder, AIConnectorHandler, resource finder, connector config/download managers | **BLOCKER** |
| B2 | `MediatorFactoryFinder` singleton | [MediatorFactoryFinder.java:155-196](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/factory/mediators/MediatorFactoryFinder.java#L155-L196) | `miVersion`, `projectPath`, `connectorHolder`; `init()` is guarded by an `initialized` flag — **first project wins, every later project silently gets the first project's mediator catalog** | 4 direct `getInstance()` sites (`SyntaxTreeUtils` — a static cached field, `MediatorHandler`, `ExpressionCompletionUtils`, `IsolatedTryOutHandler`) + ~35 factory classes routed through `SyntaxTreeUtils.createMediator` | **BLOCKER** |
| B3 | `DynamicClassLoader` | [DynamicClassLoader.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/dataService/DynamicClassLoader.java) | one static `URLClassLoader` loaded with the project's `deployment/libs` DB-driver jars | 9 call sites / 4 files (QueryGenerator, DBConnectionTester, DriverLoader, service init) | **BLOCKER** |
| B4 | Static `loadedResourceFinder` bridge | [SynapseLanguageService.java:196-214](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java#L196-L214) | the ONE project's dependent-resources finder, exposed via static `getLoadedDependentResources()` | consumed from the standard-LSP layer by [SynapseDiagnosticsParticipant.java:1395](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapseDiagnosticsParticipant.java#L1395) — a core→app back-reference | **BLOCKER** |
| B5 | Single XSD dir / `connectors.xsd` / XML catalog | [Utils.copyXSDFiles :1093](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/utils/Utils.java#L1093), [updateSynapseCatalogSettings :1147/:1166](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/utils/Utils.java#L1147), [SynapseLanguageService.updateConnectors :591](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java#L591) | one temp schema dir for one project's MI version; one `connectors.xsd` generated from the global holder; one `catalog.xml` registered in process-wide XML settings | all XML validation in the process — two projects with different MI versions/connectors corrupt each other's diagnostics | **BLOCKER** |
| B6 | Try-out embedded MI server | `TryOutConstants` (fixed ports 8290 / 9201 / 9005 / 9006), [MIServer.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediator/tryout/server/MIServer.java), `TryOutHandler.handleServerRestart` (~:668, machine-wide lock file with project-hash swap), `CAPPCacheManager` shared build temp | one OS process, fixed ports, machine-wide mutual exclusion | try-out, input/output schema, connection testing | **BLOCKER** (scoped down by agreement to "make the existing single-active swap project-registry-aware") |
| B7 | Single-root initialization | [XMLLanguageServer.java:155](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLLanguageServer.java#L155), [XMLWorkspaceService.java:88](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLWorkspaceService.java#L88) | `rootPath` only; folder change events forwarded but not tracked; `didChangeWatchedFiles` reloads the single project's connectors | server lifecycle | **BLOCKER** |
| B8 | Implicit `this.projectUri` request binding | [SynapseLanguageService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java) (61 refs) | ~30 endpoints assume the stored project; **13 endpoints carry no project hint at all** in their request; 7 carry a document URI but ignore it | RPC surface + the VS Code extension | **BLOCKER** (API change needed for the 13) |
| B9 | Client notifications without a project id | [SynapseLanguageClientAPI.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/SynapseLanguageClientAPI.java) (`addConnectorStatus`, `removeConnectorStatus`, `tryoutLog`) | n/a (protocol) | client-side routing of server pushes | **REFACTOR** (additive field) |
| B10 | Minor statics | `ConnectorFactory` static `List<String>`; `DirectoryTreeBuilder` static `projectPath`/`mainSequence`/`artifactResourcePaths` scratch fields (already a race under concurrent requests today); `CAPPCacheManager` shared build temp + static executor | request-scoped scratch data in statics | thread-safety | **REFACTOR** |

**Confirmed harmless** (no work needed): `FunctionRegistry`, `MediatorSerializerFinder`, `ExpressionSignatureProvider`'s signature cache (all process-wide constants or stateless), JDBC type maps, and the `~/.wso2-mi` caches, which are already partitioned by `<projectName>_<hash(projectPath)>`.

**The bottom line of the estimate:** ten findings, of which six are hard blockers — but B1–B4 are each a single class, B5 is one wiring point, and B7/B8 are the entry layer. The 32 tree-coupled endpoints inherit the fix automatically once B1/B2 are project-scoped. This is why the effort lands in weeks.

---

## 4. What is already multi-project-ready (the assets)

A surprising amount of the codebase already points the right way — these reduce both risk and effort:

1. **Upstream LemMinX core is project-agnostic.** `XMLTextDocumentService` and the whole standard-LSP pipeline key purely off document URIs; they have no concept of a project root. The LS-specific layer needs almost nothing.
2. **`SynapseDiagnosticsParticipant` is the template.** It already derives the owning project from the document URI ([deriveProjectPath :1570](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapseDiagnosticsParticipant.java#L1570)) and caches per project in a `ConcurrentHashMap` keyed by project path (line 73). Its *only* global dependency is the B4 static bridge.
3. **`ResourceParam` is the protocol template.** `availableResources` already accepts `projectPath`/`customProjectUri` and falls back to the stored project — exactly the pattern the other endpoints need.
4. **The `WorkspaceFolder`-parameterized endpoints** (`directoryTree`, `getProjectExplorerModel`, `getProjectIntegrationType`) already resolve the project from the request (their static scratch fields are the only defect).
5. **Most collaborators are per-instance already.** `MediatorHandler`, `InboundConnectorHolder`, `ConnectionHandler`, `ExpressionHelperProvider`, `TryOutManager`, and the resource finder are constructed per `SynapseLanguageService`; the moment there is one context per project, they become project-scoped for free. The singletons they *reach into* (B1–B3) are the only leak.
6. **Disk caches are hash-partitioned per project** under `~/.wso2-mi/...`, so shared-filesystem collisions are already largely solved.
7. **The "dependent projects" feature is precedent, not conflict.** It merges *other* projects' resources into one active project's context (a read-only, type-keyed map) — it is not multi-project itself, but it proves the resource-finder layer can hold more than one project's artifacts.
8. **Tests mostly pass project paths explicitly** into collaborators rather than constructing `SynapseLanguageService`, so the endpoint-layer changes break little. (The de-singletoning does break the `mockStatic(ConnectorHolder)` / `setLoadedResourceFinder` scaffolding — budgeted in Phase 1.)

---

## 5. Target architecture

### 5.1 The shape: one service, a registry of project contexts

```
XMLLanguageServer ── @JsonDelegate ──▶ SynapseLanguageService  (still ONE instance, 81 methods)
                                            │
                                            │ 1. ProjectContext ctx = resolver.resolve(request)
                                            │ 2. delegate to feature code with ctx
                                            ▼
                     ProjectRegistry  ◀── the ONE deliberate process singleton
                     Map<projectRootUri, ProjectContext>
                            │
        ┌───────────────────┴────────────────────────┐
        ▼                                            ▼
  ProjectContext "projectA"                    ProjectContext "projectB"
   ├─ projectUri, miVersion, isLegacyProject    ├─ ...
   ├─ ConnectorHolder        (instance)         │
   ├─ MediatorFactoryFinder  (instance)         │
   ├─ AbstractResourceFinder (dependent map)    │
   ├─ MediatorHandler / ConnectionHandler /     │
   │  InboundConnectorHolder / ExpressionHelper │
   ├─ ProjectClassLoader (deployment/libs)      │
   ├─ schemaDir (per-project XSDs +             │
   │  connectors.xsd)                           │
   └─ TryOut handle ──▶ shared TryOutCoordinator (single-active swap)
```

Key decisions and why:

- **One `SynapseLanguageService`, not N instances behind a router.** LSP4J resolves the `@JsonDelegate` once at launcher-build time, so routing must happen inside the process either way; a router would mean 81 hand-written delegation methods *and* would still not fix B1–B4, because those are process-wide statics that N service instances would still share. Rejected.
- **`ProjectRegistry` is the one intentional singleton.** It is genuinely process-scoped (one process = one workspace) and — crucially — gives the standard-LSP participants (L1 below) a clean seam to reach per-project state from just a document URI, replacing the B4 static bridge.
- **`ProjectContext` is the unit of ownership.** Everything currently initialized in `SynapseLanguageService.init()` becomes the context's constructor. Contexts are created **lazily** (full connector load on first touch of a project) to bound startup time and memory, and disposed on folder removal (close the classloader, delete the temp schema dir).

### 5.2 Per-request project resolution — the ladder

Every endpoint resolves its context through the same four steps, first hit wins:

1. **Explicit `projectUri` in the request** (the `ResourceParam` pattern, normalized by longest-prefix match so a path *inside* a project resolves to its root).
2. **Derive from a document URI / file path already in the request** — longest-prefix match against the registered workspace folders; the participant's `src/main/wso2mi` scan remains the tiebreaker for odd layouts.
3. **Single-project fallback** — if the registry holds exactly one project, use it. This is the backward-compatibility path: an old extension speaking the old protocol against a one-folder workspace keeps working unchanged.
4. **Fail fast** — multi-folder workspace and no way to resolve → a clean JSON-RPC error, never a silent guess. Silent guessing is how cross-project data corruption would be born.

### 5.3 Layering — the separation of concerns

Four layers with a strict dependency direction (enforced as a review rule first; a physical module split can come later):

| Layer | Contents | Rule |
|---|---|---|
| **L1 — LS core** | upstream LemMinX + `extensions/synapse` participants (diagnostics, code actions) | document-scoped; holds **no** project state; may reach L3 only via `ProjectRegistry` lookup by derived project path |
| **L2 — Analysis kernel** | `syntaxTree.*` (STNode, factories, `SyntaxTreeGenerator`), expression parsing, DOM utilities | pure functions of `(document, context)`; **no statics, no hidden lookups** — this is where B2 currently violates the rule |
| **L3 — Project layer** *(new package, e.g. `customservice.synapse.project`)* | `ProjectRegistry`, `ProjectContext`, `ProjectResolver` (the ladder), lifecycle (open/reload/dispose), `TryOutCoordinator` | owns all per-project state; the only layer allowed to construct contexts |
| **L4 — Tooling services** | the 73 non-LS endpoints, regrouped over time into stateless services (`ConnectorService`, `ResourceService`, `PomService`, `DebuggerService`, …) taking `ProjectContext` as their first argument | `SynapseLanguageService` shrinks to resolve-and-delegate |

A pleasant side effect: after this layering, the **41 fully isolated endpoints** end up in L4 services with zero LemMinX imports — a natural seam if the team ever wants to extract the tooling backend from the language server entirely. That extraction is explicitly *out of scope* here; the layering merely stops re-entangling them.

---

## 6. Per-blocker remediation

**B1 `ConnectorHolder` → de-staticize in place and thread it.** Remove `static` from the connector list and the instance, delete `getInstance()`, make the static helpers instance methods. `ProjectContext` constructs one per project. Thread it through consumers rather than a lookup-at-use-site registry: 8 of the 10 consumers *already* receive the holder via constructor or `init(...)` — the work is the 13 `getInstance()` call sites plus the static helper callers. (A keyed-registry alternative was rejected: it preserves the hidden global coupling and keeps tests on `mockStatic`.)

**B2 `MediatorFactoryFinder` → per-context instance, finder threaded through the factory chain.** Kill the static instance and the first-project-wins flag; the context owns a finder built with its version/path/holder. The call topology is smaller than it looks: 4 direct `getInstance()` sites (three of which live in per-project collaborators that just take the finder from their context), and the ~35 factory classes all route through `SyntaxTreeUtils.createMediator`. Since every factory is instantiated *per finder* in `loadMediatorFactories()` (which already does `fac.setMiVersion(...)`/`setProjectPath(...)`), add a `fac.setFinder(this)` back-reference there and give `createMediator` a finder parameter — a mechanical one-line edit per factory. `SyntaxTreeGenerator` is created per request and gets the finder from the resolved context.

**B3 `DynamicClassLoader` → per-context classloader.** Each context holds a `URLClassLoader` over its own `deployment/libs`. The JDBC risk is already defused: the code loads drivers via `Class.forName(className, true, loader)` and instantiates `Driver` directly rather than relying on `DriverManager`'s classloader rules, so isolated per-project loaders are safe. Two additions: close the loader and deregister its drivers on project disposal (classloader leak otherwise), and note that the same driver in two projects yields two independent `Class` identities — which is exactly what we want.

**B4 Static diagnostics bridge → registry lookup.** Replace `SynapseLanguageService.getLoadedDependentResources()` in the participant with `ProjectRegistry.get(derivedProjectPath).getResourceFinder().getDependentResourcesMap()` (empty map for unregistered projects — same semantics as today's pre-init state). Delete the static setter; its test usage migrates to seeding a test registry.

**B5 Single XSD/catalog → per-project schema dirs + a URI-resolver extension.** `Utils.copyXSDFiles` already creates a fresh temp dir per call — call it per context (also fixing the `connectors.xsd` collision, since `SchemaGenerate` then writes into the owning project's dir from the owning project's holder). Replace the global `catalog.xml` injection with one custom `URIResolverExtension` registered in the `URIResolverExtensionManager`: its `resolve(baseLocation, publicId, systemId)` receives the *document's* URI as `baseLocation`, so it maps document → project → that project's schema dir. (Per-folder `XMLFileAssociation` globs were considered and rejected: each association maps to a single systemId, while Synapse validation needs a multi-XSD catalog.) LemMinX validation settings are already per-URI capable, so no upstream surgery is needed.

**B6 Try-out → `TryOutCoordinator`.** `TryOutManager` becomes per-context; a process-wide coordinator serializes activation ("stop project A's embedded MI, start it for project B"), formalizing the machine-wide lock + hash-swap logic that already exists at ~`TryOutHandler:668`. Fixed ports make this mandatory anyway. The coordinator's interface leaves room for a port-allocating, concurrent implementation later — designed for, not built now. `tryoutLog` notifications gain a `projectUri`.

**B7 Initialization → workspace folders.** `initialize()` reads `params.getWorkspaceFolders()` (falling back to `rootPath`), registering each folder in the `ProjectRegistry`; `didChangeWorkspaceFolders` (currently forwarded but untracked) adds/disposes contexts; `didChangeWatchedFiles` connector-zip events route by URI prefix to the owning context's loader instead of the single `updateConnectors()`.

**B8 Request routing → the ladder + 13 API additions.** Detailed in §7.

**B9 Notifications → additive `projectUri` field** on `ConnectorStatusNotification` and the tryout log payload; the extension routes them to the right project view.

**B10 Minor statics** — `ConnectorFactory`'s list becomes an instance field (factories are per-finder now, so it falls out); `DirectoryTreeBuilder`'s static scratch fields become locals/instance state (this fixes an *existing* single-project race, a free win); the CAPP build temp gets project-hash partitioning like the rest of `~/.wso2-mi`.

---

## 7. Protocol changes (coordination with the VS Code extension)

**Endpoints requiring a new (optional) `projectUri` parameter — the 13 with no project hint today:**
`getResourceFiles`, `getConfigurableEntries`, `getConfigurableList`, `getOverviewModel`, `getOverviewPageDetails`, `updateConnectorDependencies`, `refetchIntegrationProjectDependencies`, `getDependencyStatusList`, `loadDependentResources`, `getMavenDeployPluginDetails`, `removeMavenDeployPlugin`, `getLocalInboundConnectors`, `getLocalInboundEndpointsListForCopilot`.
The parameter stays *optional on the wire*: an old client in a single-folder workspace resolves via ladder step 3.

**Endpoints fixed server-side only (they already carry a URI/path but ignore it):**
`syntaxTree`, `definition`, `dependencyTree`, `getMCPTools`, `getResourceUsages`, `getRegistryFiles`, `getArtifactFiles` — no wire change; derive the project from the param instead of `this.projectUri`.

**Endpoints with request bodies but no project field** (pom/config updates, the connector-config family, `connectorConnections`, `generateConnector`, `checkDBDriver`, `downloadDriverForConnector`, connector resolution): same additive-optional-field treatment, prioritized by how likely they are to be called for a non-active project.

**Notifications:** `projectUri` added to `addConnectorStatus` / `removeConnectorStatus` / `tryoutLog` payloads (additive JSON — non-breaking).

**Compatibility matrix:** old extension + new server works for single-folder workspaces (ladder step 3); new extension + old server requires the extension to tolerate missing `projectUri` in notifications. All wire changes stay additive through Phase 2 to keep both skews safe.

---

## 8. Phased migration roadmap

Assumptions: one senior engineer who knows the codebase, full-time; extension changes done in parallel by the extension team; estimates include unit-test migration and code review, exclude QA/release cycles. **Every phase ships a working product.**

| Phase | Scope | Server effort | Extension effort | Shippable state |
|---|---|---|---|---|
| **P0 — Protocol & routing scaffolding** | `ProjectRegistry`/`ProjectContext` skeleton wrapping today's single project; workspace-folders intake with `rootPath` fallback; `ProjectResolver` ladder; optional `projectUri` on the 13 endpoints; fix the 7 URI-ignoring endpoints; tag notifications | **2–3 pw** | 1–1.5 pw | Behaviour-identical, protocol future-proofed |
| **P1 — De-globalize state** | B1 (13 sites/8 files), B2 (4 sites + ~35 mechanical factory edits), B3 (9 sites/4 files + disposal), B4 (registry bridge), B10 minor statics; migrate tests off `mockStatic`/`setLoadedResourceFinder` | **4–6 pw** (≈1.5–2 pw of it is test migration) | — | Identical behaviour, race-free single project; the two-project cross-talk integration test lands here as the gate for everything after |
| **P2 — Multi-project activation** | N lazy contexts from workspace folders; per-project XSD dirs + `URIResolverExtension`, delete the global catalog; watched-files routing by prefix; folder add/remove lifecycle (classloader close, temp cleanup); concurrency audit of download/cache lock granularity | **3–5 pw** | 0.5–1 pw | **The headline feature ships** |
| **P3 — Try-out coordination & hardening** | `TryOutCoordinator` single-active swap wired to the registry; project-tagged tryout UX; cross-project soak tests (two projects, different MI versions + connector sets, concurrent edits); memory profiling with N contexts | **2–3 pw** | — | Complete, robust multi-root support |
| **P4 — L4 decomposition** *(optional)* | Split the 81-method service into stateless L4 services; isolate the 41 LemMinX-free endpoints behind their own interfaces | 3–4 pw | — | Maintainability; do opportunistically |

**Core total (P0–P3): 11–17 server person-weeks + 1.5–2.5 extension person-weeks** — roughly 3.5–4.5 elapsed months for one engineer including review latency. P4 is not required for the feature.

---

## 9. Effort split — LS core vs. non-LS tooling

This is the specific breakdown that was asked for: *of the 11–17 server person-weeks, how much buys multi-project for the **language-server core** (the 8 LS endpoints + the standard-LSP diagnostics/code-action participants + validation) versus the **non-LS tooling backend** (the 73 tooling endpoints)?*

### 9.1 The catch: the two halves are not cleanly separable

The naïve expectation is "8 LS endpoints are tiny, 73 tooling endpoints are the bulk, so the split tracks the count." It does not, and for one reason verified in code:

> **The language features stand on the same connector → mediator-catalog → validation spine that the tooling features do.** Per-project *diagnostics* are only correct if project B's own connectors are loaded (the `connectors.xsd` is generated from the connector holder — [SynapseLanguageService.java:598](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java#L598), B5→B1). `expressionCompletion` calls `MediatorFactoryFinder.getInstance().getMediator(node)` directly ([ExpressionCompletionUtils.java:450](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/expression/ExpressionCompletionUtils.java#L450), B2). The diagnostics participant reaches the static resource bridge ([SynapseDiagnosticsParticipant.java:1395](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapseDiagnosticsParticipant.java#L1395), B4).

So making the *LS core* multi-project-correct already forces you to de-globalize **B1 (ConnectorHolder)** and **B2 (MediatorFactoryFinder)** — the very same fix the 32 tree-coupled tooling endpoints need. That shared work cannot be assigned to one side. The honest way to answer the question is therefore **four buckets**, not two:

- **A — Shared foundation** (unattributable; needed by anything multi-project): `ProjectRegistry`/`ProjectContext`/`ProjectResolver`, workspace-folder intake, folder lifecycle, the cross-talk integration test.
- **B — Joint analysis spine** (jointly owned by LS *and* the 32 coupled tooling endpoints): B1 + B2.
- **C — LS-core-only**: work that exists *only* because of the language features.
- **D — Non-LS-only**: work that exists *only* because of the tooling backend.

### 9.2 The four-bucket estimate

| Bucket | Blockers / work | Server effort | Notes |
|---|---|---|---|
| **A. Shared foundation** | B7 (workspace-folders intake, folder add/remove lifecycle, watched-files routing), registry/context/resolver skeleton, cross-talk integration test, classloader/temp disposal | **2.5–4 pw** | Prerequisite for either half; built once |
| **B. Joint analysis spine** | B1 `ConnectorHolder` (13 sites/8 files), B2 `MediatorFactoryFinder` (4 sites + ~35 factory edits), incl. `mockStatic` test migration | **3–4 pw** | Correctness of LS diagnostics/completion **and** the 32 coupled tooling endpoints both depend on this |
| **C. LS-core-only** | B4 static diagnostics bridge → registry lookup; B5 per-project XSD dirs + `URIResolverExtension`, delete global catalog, validation regression suite; route the 2 LS endpoints (`syntaxTree`, `definition`) off `this.projectUri` | **2.5–3.5 pw** | B5 (per-project validation) dominates; it is core LS behaviour |
| **D. Non-LS-only** | B3 `DynamicClassLoader` (DB drivers, 9 sites/4 files + disposal); B6 try-out `TryOutCoordinator`; B9 notification tagging; B10 minor statics; routing for the 13 no-hint + 5 URI-ignoring tooling endpoints + optional `projectUri` params | **3.5–5.5 pw** | B6 (2–3 pw) is the single largest item and is 100% tooling |
| **Total** | | **11–17 pw** | Reconciles with the phased roadmap (§8) |

### 9.3 The answer to the question, stated two ways

Because buckets **A** and **B** are shared, the two halves are **not additive** — you pay A+B once, not twice. That gives two legitimate readings depending on what "the effort to convert X" means:

**Reading 1 — full standalone cost (if you did only that half, from today's single-project baseline):**

| Half | Buckets | Standalone effort |
|---|---|---|
| **LS core** made multi-project-correct | A + B + C | **8–11.5 pw** |
| **Non-LS tooling** made multi-project-correct | A + B + D | **9–13.5 pw** |
| *(naïve sum — wrong)* | | *17–25 pw* |
| **Actual combined** | A + B + C + D | **11–17 pw** |

The gap between the naïve sum (17–25) and the real total (11–17) is exactly the shared spine **A + B (5.5–8 pw)**, which would otherwise be double-counted. **You cannot buy the two halves independently and add the bills.**

**Reading 2 — marginal cost (build the shared spine once, then attribute only the incremental work):**

| Item | Effort | What it delivers |
|---|---|---|
| Shared spine **A + B** | **5.5–8 pw** | Nothing shippable alone, but unavoidable groundwork; makes the analysis kernel project-scoped |
| **+ LS-core increment (C)** | **+2.5–3.5 pw** | Correct per-project diagnostics, validation, completion, go-to-definition |
| **+ Non-LS increment (D)** | **+3.5–5.5 pw** | Correct per-project project views, connectors, forms, try-out, DB tooling, pom/config |

The practical planning conclusion: **the LS core is the *cheaper and lower-risk* half to finish (its only large item is per-project validation, B5), but it cannot ship without the 5.5–8 pw shared spine. The non-LS half is where the genuinely LS-independent cost lives — B3 (DB driver classloaders) and B6 (try-out) together are 3–4.5 pw that the language server would never need on its own.**

### 9.4 Extension effort is almost entirely non-LS

The ~1.5–2.5 person-weeks of VS Code extension work (§8) attributes **~0 to the LS core and ~all to the tooling backend.** The 8 LS endpoints are already driven by `textDocument`-style document URIs the extension sends today, so LemMinX resolves them per-document with no client change. Every extension task — passing `projectUri` to the 13 hint-less endpoints, routing project-view/connector/try-out notifications to the right project panel (B9) — exists because of the non-LS UI surface. So on the client side the split is starkly lopsided: **LS core ≈ 0, non-LS ≈ 1.5–2.5 pw.**

### 9.5 One-line summary of the split

| | Server | Extension | Character |
|---|---|---|---|
| Shared spine (A+B) | 5.5–8 pw | ~0 | Unavoidable; de-globalize the analysis kernel |
| LS core (C) | 2.5–3.5 pw | ~0 | Low-risk; dominated by per-project validation |
| Non-LS tooling (D) | 3.5–5.5 pw | 1.5–2.5 pw | The LS-independent cost: DB classloaders + try-out + routing |

---

## 10. Risks and mitigations

1. **Undiscovered statics or shared temp paths** beyond the inventory. *Mitigation:* a `static`-mutable-field grep gate over `customservice.synapse` in P1 review, plus the two-project cross-talk integration test (assert no bleed in connectors, syntax trees, validation) written in P1 and run as the gate for every later phase.
2. **Startup concurrency.** Connector loaders, `DependencyDownloadManager`, and the `~/.wso2-mi` caches were written under a one-project-at-a-time assumption; hash partitioning helps but lock-file granularity needs an explicit audit (P2).
3. **Memory footprint** — N× connector models, resource maps, schema dirs. Lazy context creation and disposal-on-folder-remove are **mandatory**, not nice-to-haves; profile with realistic 3–5 project workspaces in P3.
4. **Catalog → resolver-extension regression.** XML validation is the product's core feature; run a broad validation regression suite across MI versions before deleting the catalog path (P2).
5. **Client/server version skew** in the field (old extension + new server and vice versa). Keep every wire change additive/optional through P2; ladder step 3 covers old clients.
6. **Test-churn concentration.** The `mockStatic(ConnectorHolder)` removal touches many tests at once in P1 — it is budgeted (1.5–2 pw) explicitly, because unbudgeted test migration is how such phases silently slip.

---

## 11. Appendix

### A. Blocker → phase mapping

| Blocker | Fixed in |
|---|---|
| B7 single-root init, B8 request routing, B9 notifications | P0 (scaffolding) |
| B1 ConnectorHolder, B2 MediatorFactoryFinder, B3 DynamicClassLoader, B4 static bridge, B10 minor statics | P1 |
| B5 XSD/catalog, B7 folder lifecycle completion | P2 |
| B6 try-out | P3 |

### B. The 13 endpoints needing a project parameter

`getResourceFiles`, `getConfigurableEntries`, `getConfigurableList`, `getOverviewModel`, `getOverviewPageDetails`, `updateConnectorDependencies`, `refetchIntegrationProjectDependencies`, `getDependencyStatusList`, `loadDependentResources`, `getMavenDeployPluginDetails`, `removeMavenDeployPlugin`, `getLocalInboundConnectors`, `getLocalInboundEndpointsListForCopilot`

### C. The 7 endpoints fixed server-side only

`syntaxTree`, `definition`, `dependencyTree`, `getMCPTools`, `getResourceUsages`, `getRegistryFiles`, `getArtifactFiles`

### D. Key files

| Concern | File |
|---|---|
| Entry point / init / static bridge | [SynapseLanguageService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java) |
| Server lifecycle / rootPath / catalog wiring | [XMLLanguageServer.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLLanguageServer.java) |
| Workspace folder events | [XMLWorkspaceService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLWorkspaceService.java) |
| B1 | [ConnectorHolder.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectorHolder.java) |
| B2 | [MediatorFactoryFinder.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/factory/mediators/MediatorFactoryFinder.java) |
| B3 | [DynamicClassLoader.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/dataService/DynamicClassLoader.java) |
| B4 consumer / project derivation template | [SynapseDiagnosticsParticipant.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapseDiagnosticsParticipant.java) |
| B5 | [Utils.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/utils/Utils.java) (`copyXSDFiles`, `updateSynapseCatalogSettings`) |
| B6 | `mediator/tryout/` — `TryOutHandler.java`, `server/MIServer.java`, `TryOutConstants`, `CAPPCacheManager.java` |
| Protocol template | [ResourceParam.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/resourceFinder/pojo/ResourceParam.java) |
| Notifications | [SynapseLanguageClientAPI.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/SynapseLanguageClientAPI.java) |
