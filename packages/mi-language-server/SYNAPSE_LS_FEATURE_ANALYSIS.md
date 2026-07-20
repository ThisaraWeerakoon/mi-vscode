# MI Language Server — Feature Classification Report

*An analysis of the entry points exposed by `SynapseLanguageService`, which of them are "real" language-server features, which are not, and which of the non-language-server features still depend on XML parse trees under the hood.*

---

## 1. What this product actually is

This repository is **not a language server written from scratch**. It is a **fork of [Eclipse LemMinX](https://github.com/eclipse/lemminx)** — a general-purpose XML language server — that WSO2 has extended to understand **Synapse configuration files**, the XML dialect used by the **WSO2 Micro Integrator (MI)** (APIs, sequences, proxies, endpoints, mediators, and so on).

A quick primer for context:

- A **language server** is a background process that an editor (VS Code, IntelliJ, …) talks to over a protocol called the **Language Server Protocol (LSP)**. The editor sends JSON-RPC messages like *"the user's cursor is at line 12, column 8 — what completions can you offer?"* and the server answers. Classic LSP features are things like **diagnostics (error squiggles), code completion, go-to-definition, hover, signature help**.
- The **MI VS Code extension** (the "MI Copilot" / integration-designer UI) is the client of this server. But that extension needs far more than classic editor smarts: it renders **flow diagrams**, a **project explorer**, **mediator property forms**, it **downloads connectors**, **tests database connections**, even **converts PDFs to images**. Rather than building a second backend, WSO2 stuffed all of that into this same server process as **custom JSON-RPC methods**.

That is exactly why your question ("which features are LS-specific and which are not?") is the right one to ask: this server is really **two products sharing one process** — a language server, and a general-purpose backend for the MI extension's UI.

The repo has a single Maven module, [org.eclipse.lemminx/](org.eclipse.lemminx/). Upstream LemMinX code lives under `src/main/java/org/eclipse/lemminx/...`, and essentially all WSO2 additions live in two places:

- [customservice/](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/) — the custom `synapse/*` endpoints and their implementations (the subject of this report).
- [extensions/synapse/](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/) — plug-ins into LemMinX's *standard* LSP pipeline (see §2.2).

---

## 2. How requests reach `SynapseLanguageService`

### 2.1 The custom `synapse/*` channel (the file you asked about)

The wiring is a three-piece chain:

1. **The contract** — [ISynapseLanguageService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/ISynapseLanguageService.java) is annotated with `@JsonSegment("synapse")`. Every method on it becomes a JSON-RPC method named `synapse/<methodName>` — e.g. `syntaxTree()` is callable as **`synapse/syntaxTree`**, `getMediators()` as **`synapse/getMediators`**.
2. **The implementation** — [SynapseLanguageService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java) implements that interface. It is deliberately a **thin dispatcher**: each method is a few lines that delegate to a feature class (`SyntaxTreeGenerator`, `MediatorHandler`, `DebuggerHelper`, `RestApiAdmin`, …).
3. **The registration** — [XMLLanguageServer.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/XMLLanguageServer.java) creates the service (line 117) and exposes it via `@JsonDelegate` on `getSynapseLanguageServer()` (~line 288). `@JsonDelegate` is the LSP4J mechanism that merges a custom method segment into the same server endpoint — so the one process answers both standard `textDocument/*` calls **and** custom `synapse/*` calls.

On `initialize`, the server also calls [SynapseLanguageService.init()](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/SynapseLanguageService.java#L248), which does a lot of *non-LSP* startup work: detecting legacy vs new project layout, reading the MI runtime version, loading connectors, initializing the mediator catalog, spinning up the try-out manager, and pre-scanning the project's resources.

### 2.2 The standard LSP channel (easy to miss)

Not every Synapse feature goes through `SynapseLanguageService`. Some plug into LemMinX's official extension point (`IXMLExtension`): [SynapsePlugin.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapsePlugin.java) registers

- [SynapseDiagnosticsParticipant](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/SynapseDiagnosticsParticipant.java) — semantic MI validations that an XSD can't express, and
- [SynapseCodeActionParticipant](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/extensions/synapse/codeactions/SynapseCodeActionParticipant.java) — quick fixes (add missing attribute, insert child element, …).

These ride the *standard* `textDocument/publishDiagnostics` and `textDocument/codeAction` flows. They aren't part of your entry-point file, but they matter for the "LS-specific" picture: **the truly classic LSP behaviour lives partly here, not only in `SynapseLanguageService`.**

---

## 3. The two XML trees you must know about

Almost every interesting finding below comes down to which of **two different tree representations** a feature uses. Understanding them first makes everything else obvious.

```
                    ┌──────────────────────────────────────────────────────┐
  file on disk      │  LemMinX DOM tree (DOMDocument)                      │
  or open editor ──▶│  generic XML: elements, attributes, text,            │
  buffer            │  WITH character offsets/positions (for LSP ranges)   │
                    └───────────────────────┬──────────────────────────────┘
                                            │ SyntaxTreeGenerator.buildTree(...)
                                            │ + per-tag factories (APIFactory,
                                            │   ProxyFactory, MediatorFactoryFinder…)
                                            ▼
                    ┌──────────────────────────────────────────────────────┐
                    │  Synapse syntax tree (STNode hierarchy)              │
                    │  Synapse-SEMANTIC: typed objects like API,           │
                    │  APIResource, NamedSequence, LogMediator, LocalEntry │
                    └──────────────────────────────────────────────────────┘
```

**Tree #1 — the LemMinX `DOMDocument`.** A lightweight, position-aware XML DOM (`DOMDocument`, `DOMElement`, `DOMAttr`). It knows *"there is an element named `log` spanning characters 210–260"* but has no idea what a "log mediator" is. It is produced two ways:

- For **open editor documents**: `xmlTextDocumentService.computeDOMAsync(...)` hands you the already-parsed, cached DOM of the buffer (used by e.g. `syntaxTree`, `diagnostic`, `definition`).
- For **arbitrary files on disk**: [Utils.getDOMDocument(File)](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/utils/Utils.java#L224) reads the file and runs `DOMParser` on it. Dozens of "non-LS" features call this — that's the crux of your third question.

**Tree #2 — the Synapse syntax tree (`STNode`).** Built **from** Tree #1 by [SyntaxTreeGenerator.buildTree()](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/SyntaxTreeGenerator.java#L102): it looks at the root tag (`api`, `proxy`, `sequence`, `endpoint`, …), dispatches to the matching factory ([APIFactory](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/factory/APIFactory.java), `ProxyFactory`, …), and for mediators inside flows uses [MediatorFactoryFinder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/factory/mediators/MediatorFactoryFinder.java) to map each element (`log`, `call`, `foreach`, connector operations, …) to a typed `STNode` subclass. The result is a **semantic model of the integration artifact** — exactly what the VS Code extension renders as the flow diagram.

So when this report says a feature *"consumes the XML tree"*, it can mean either level — and the tables below distinguish them, because a feature that builds the syntax tree implicitly builds the DOM first.

---

## 4. Full endpoint inventory and classification

### 4.1 What "LS-specific" means here

The classification criterion used: a feature is **LS-specific** if it is a classic language-intelligence service — it takes a *document* (and usually a *cursor position*) and returns *analysis of that text*: errors, completions, definitions, signatures, or a parse of the document. These map naturally onto standard LSP concepts even when exposed under a custom method name.

Everything else — project browsing, artifact scaffolding, dependency/connector management, database utilities, debugging support, runtime try-out, file conversion — is **not** a language-server task. It lives here only because the server was a convenient always-running backend for the extension.

### 4.2 LS-specific endpoints (8)

| Endpoint (`synapse/…`) | What it does | Classic LSP analogue |
|---|---|---|
| `syntaxTree` | Parses the open document into the Synapse syntax tree and returns it as JSON (drives the diagram view) | Document parsing / `textDocument/documentSymbol`-like model service |
| `diagnostic` | Runs full XML + Synapse validation on an open document and returns diagnostics on demand | `textDocument/publishDiagnostics` (pull-style) |
| `codeDiagnostic` | Same, but for a raw code *string* sent by the client (used by Copilot-generated code before it is saved) | Diagnostics |
| `definition` | Resolves the artifact referenced at the cursor (e.g. a `sequence key="…"`) to its defining file/position, including cross-project resources | `textDocument/definition` |
| `expressionCompletion` | Completions inside Synapse `${...}` expressions | `textDocument/completion` |
| `signatureHelp` | Function-signature popups for expression functions | `textDocument/signatureHelp` |
| `expressionValidation` | Validates a Synapse expression string (ANTLR grammar + semantic visitor) | Diagnostics for an embedded language |
| `expressionHelperData` | Returns the variables/properties/params available at a position, for the expression-builder helper panel | Rich completion context (LSP-adjacent) |

A note on two borderline cases, so the reasoning is transparent:

- **`syntaxTree`** is mechanically the *most* language-servery thing here (parse the open buffer, return a tree), even though its consumer is a diagram rather than a text editor. It is classified LS-specific.
- **`expressionHelperData`** has no exact LSP equivalent, but it is pure "analyse the document around a position" work, so it belongs with the language features.

### 4.3 Non-LS-specific endpoints (73), grouped by purpose

These are IDE/tooling backend features. Grouping makes the list digestible:

| Group | Endpoints | What they're for |
|---|---|---|
| **Project & workspace views** | `directoryTree`, `getProjectExplorerModel`, `getProjectIntegrationType`, `getOverviewModel`, `getOverviewPageDetails` | Build the project-explorer tree, the "overview" home page, and the integration-type badges shown in the extension |
| **Resource discovery** | `availableResources`, `loadDependentResources`, `getRegistryFiles`, `getResourceFiles`, `getArtifactFiles`, `getResourceUsages`, `dependencyTree` | List the artifacts/registry resources a dropdown can reference; find where a resource is used; compute an artifact's dependency graph |
| **Connector management** | `availableConnectors`, `getConnectorInfo`, `resolveConnector`, `isDuplicateConnector`, `connectorConnections`, `getConnectionUISchema`, `generateConnector`, `updateConnectorDependencies`, `refetchIntegrationProjectDependencies`, `getDependencyStatusList`, `getConnectorDependencies`, `updateConnectorDependencyOverride`, `resetConnectorDependencyOverrides`, `updateConnectorFlags`, `updateGlobalConnectorFlags`, `initConnectorConfig` | Everything about connectors: list/download/read metadata, find configured connections, manage the connector-dependency config, even *generate* a new connector from an OpenAPI/proto file |
| **Inbound connectors** | `getInboundInfo`, `saveInboundConnectorSchema`, `getInboundConnectorSchema`, `getLocalInboundConnectors`, `getLocalInboundEndpointsListForCopilot` | Manage inbound-endpoint connector UI schemas (mostly JSON) |
| **Mediator forms & code generation** | `getMediators`, `getMediatorUISchema`, `getMediatorUISchemaWithValues`, `generateSynapseConfig`, `getMCPTools` | Back the mediator palette and property forms: which mediators are valid at a position, the form schema (optionally pre-filled with current values), and generating/updating the XML when the user hits Save |
| **Try-out / runtime** | `tryOutMediator`, `shutDownTryoutServer`, `mediatorInputOutputSchema`, `testConnectorConnection` | Run a mediator against a real embedded MI server to preview input/output payloads; test connector connections |
| **Debugger support** | `getBreakpointInfo`, `validateBreakpoints`, `stepOverBreakpoint` | Translate editor line/column breakpoints into MI runtime debug coordinates (API/sequence/mediator position paths) |
| **API ⇄ OpenAPI generation** | `generateAPI`, `swaggerFromAPI`, `isEqualSwaggers` | Scaffold a Synapse API from a Swagger/WSDL; generate Swagger from an API; compare two Swaggers |
| **Data services & databases** | `testDBConnection`, `loadDriverAndTestConnection`, `checkDBDriver`, `addDBDriver`, `removeDBDriver`, `modifyDBDriver`, `generateQueries`, `fetchTables`, `getDynamicFields`, `getStoredProcedures`, `getInputOutputMappings`, `downloadDriverForConnector`, `getDriverMavenCoordinates` | JDBC utilities for the data-service and DB-connector wizards: test connections, manage driver JARs on the classpath, introspect tables/procedures, generate data-service queries |
| **Build files (pom.xml) & config** | `updateProperty`, `updateDependency`, `updateMavenDeployPlugin`, `getMavenDeployPluginDetails`, `removeMavenDeployPlugin`, `updateConfigFile`, `getConfigurableEntries`, `getConfigurableList` | Read/edit `pom.xml` properties, dependencies, and the CAR-deploy plugin; read/edit `config.properties`/`.env` configurables |
| **Payload schema generation** | `generateSchema`, `generateSchemaFromContent` | Generate a JSON Schema from a sample XML/JSON/CSV/XSD payload (for the DataMapper) |
| **Misc utilities** | `getArtifactType`, `pdfToImagesBase64` | Classify an XML file's artifact type; convert a PDF to base64 images (for the IDP/document-processing feature — about as far from a language server as it gets) |

---

## 5. The punchline: non-LS features that secretly consume XML trees

This is the heart of your question. **Fourteen of the "non-LS" feature areas above cannot do their job without parsing project XML into the very same trees the language features use.** They are tooling features by *purpose*, but language-analysis features by *implementation*.

Legend: **DOM** = LemMinX `DOMDocument`; **ST** = Synapse syntax tree (`STNode`).

### 5.1 Direct consumers

**1. Debugger breakpoint mapping — DOM + ST.**
[DebuggerHelper](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/debugger/DebuggerHelper.java#L191) parses the artifact file (`Utils.getDOMDocument` → `SyntaxTreeGenerator.buildTree`, lines 194–195) the moment it is constructed. To tell the MI runtime *"break at mediator #3 inside resource `/foo` of API `bar`"*, it must know the artifact's semantic structure — a line number alone is meaningless to the runtime. Tag-specific visitors (`ApiVisitor`, `ProxyVisitor`, …) walk the syntax tree to translate each editor breakpoint into runtime debug coordinates. All three debugger endpoints go through this.

**2. Dependency tree — DOM + ST.**
[DependencyScanner.analyzeArtifact()](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/dependency/tree/DependencyScanner.java#L43) DOM-parses the artifact, reads its name from the DOM, then builds the syntax tree and walks it with `AbstractDependencyVisitor`s to discover every referenced sequence/endpoint/local entry. You can't know what an API depends on without understanding its mediators — hence the tree.

**3. Project overview model — ST (indirectly).**
[OverviewModelGenerator](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/dependency/tree/OverviewModelGenerator.java#L48) does no parsing itself, but it calls `DependencyScanner.analyzeArtifact` for **every artifact in the project**. The overview page is therefore a whole-project syntax-tree sweep.

**4. Resource discovery — DOM.**
[AbstractResourceFinder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/resourceFinder/AbstractResourceFinder.java) (behind `availableResources` and `loadDependentResources`) DOM-parses every artifact/registry/local-entry file it finds (e.g. `createResource` ~line 907, `createLocalEntryResource` ~line 761) to read root tag, `name`/`key`/`version` attributes and validate the artifact type. A pure filename scan wouldn't work because an artifact's identity lives *inside* the XML. It stops at the DOM — no syntax tree needed for identity checks.

**5. Resource usage finding — DOM + ST (indirectly).**
[ResourceUsageFinder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/resourceFinder/ResourceUsageFinder.java#L36) answers *"who uses this resource?"* by running `DependencyScanner` (syntax tree) over every artifact and `ConnectionFinder` (DOM) over local entries, then matching keys. It writes no parser code itself, but its result is 100% tree-derived.

**6. Connector connection finding — DOM.**
[ConnectionFinder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectionFinder.java#L97) (behind `connectorConnections`) DOM-parses each local-entry XML and inspects its first child element (`<salesforce.init>` → connector "salesforce", plus `connectionType` and each parameter). This is how the extension knows which connections exist for a connector dropdown.

**7. Directory tree / project explorer — DOM + ST.**
[DirectoryTreeBuilder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/directoryTree/DirectoryTreeBuilder.java) *looks* like a filesystem walk, but per file it: DOM-parses to get artifact names (`getArtifactName`, ~line 759), DOM-parses `pom.xml` for the main-sequence profile (~line 280), DOM-parses local entries and API resources — and in [setSubType (~line 623)](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/directoryTree/DirectoryTreeBuilder.java#L623) it builds the **full Synapse syntax tree** just to display whether an endpoint is HTTP/WSDL/failover, a template is sequence/endpoint, etc. Both `directoryTree` and `getProjectExplorerModel` ride on this; `getProjectIntegrationType` consumes it transitively. (`getOverviewPageDetails`, by contrast, only reads `pom.xml` via SAX and config files as text — it does **not** touch these trees.)

**8. Mediator palette & property forms — DOM + ST.**
[MediatorHandler](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediatorService/MediatorHandler.java):
- `getMediators` DOM-parses the document and inspects the node before/around the cursor to decide which mediators are legal there.
- `getMediatorUISchemaWithValues` and `generateSynapseConfig` locate the mediator at the requested position via [getMediatorNodeAtPosition (line 514)](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediatorService/MediatorHandler.java#L514): `document.findNodeAt(offset)` (DOM) → `MediatorFactoryFinder.getInstance().getMediator(node)` (line 524, syntax tree) — the STNode is then fed to reflection-selected processors that fill the form or compute the new values.
- Interesting nuance: the **output** XML of `generateSynapseConfig` is *not* serialized from a tree — it is rendered from **Mustache templates** and wrapped in an LSP `TextEdit`. The tree is used to *read* the existing state, strings to *write* the new one.

**9. Mediator try-out & input/output schemas — DOM + ST.**
Behind `tryOutMediator` and `mediatorInputOutputSchema`, [TryOutHandler](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediator/tryout/TryOutHandler.java) (~lines 528–529), `IsolatedTryOutHandler`, and [ServerLessTryoutHandler](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediator/schema/generate/ServerLessTryoutHandler.java) (~lines 202–206) all do `Utils.getDOMDocument` + `SyntaxTreeGenerator.buildTree` to find which mediator sits at the requested line/column and to simulate the message context (properties/variables) flowing up to that point. (`testConnectorConnection` itself does not parse XML.)

**10. API ⇄ Swagger generation — DOM + ST.**
[RestApiAdmin](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/api/generator/RestApiAdmin.java): `swaggerFromAPI` parses the API XML into a DOM and builds the typed `API` syntax-tree node via `new APIFactory().create(...)` (lines 394–395), which the OpenAPI processor then converts. `generateAPI`'s WSDL path does the same round-trip (line 189–190): generate XML → parse into an `API` STNode → programmatically attach mediators → serialize back with `APISerializer`. (`isEqualSwaggers` uses only the swagger-parser library — no XML tree.)

**11. Inbound connector schema with values — DOM + ST.**
[InboundConnectorHolder](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/inbound/conector/InboundConnectorHolder.java#L221) is mostly a JSON uischema store, *but* when `getInboundConnectorSchema` is called with a `documentPath`, it parses the inbound-endpoint XML into a syntax tree (`InboundEndpoint` STNode, lines 221–224) to pre-fill the form with the file's current parameter values.

**12. Artifact type detection — DOM + ST.**
[SyntaxTreeGenerator.getArtifactType()](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/syntaxTree/SyntaxTreeGenerator.java#L148) doesn't just peek at the root tag string: it DOM-parses the file and **builds the full syntax tree**, then switches on the typed node — necessary because e.g. a `<localEntry>` whose child is a connector `.init` element must be classified as a *connection*, which only the semantic tree can tell.

**13. Connector metadata reading — DOM.**
[ConnectorReader](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectorReader.java#L63) DOM-parses each connector's `connector.xml` (and per-operation files) to extract names, operations, and display metadata. This underlies `getConnectorInfo`, `resolveConnector`, and all connector loading at startup — so even "download a zip and register it" ends in a DOM parse.

**14. MCP tool listing — DOM.**
[AIConnectorHandler.fetchMcpTools()](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/mediatorService/AIConnectorHandler.java#L1352) (behind `getMCPTools`) gets the tool list over HTTP/JSON-RPC from the MCP server, but first calls `Utils.getDOMNode(documentUri, position)` to DOM-resolve the `<tools>` element in the document and read which tools are already selected. It also relies on `ConnectionFinder` (DOM, item 6) to locate the MCP connection.

### 5.2 Why this pattern exists

Notice the common shape: each of these features needs to answer a question **about the meaning of a Synapse XML file** — *which mediator is at line 42? what does this API depend on? what type of endpoint is this?* The project's answer everywhere is the same pipeline: `file → Utils.getDOMDocument → SyntaxTreeGenerator/MediatorFactoryFinder → STNode`. The syntax-tree infrastructure, nominally built for the `syntaxTree` LS endpoint, is in practice **the shared semantic backbone of the whole product** — debugger, diagrams, forms, dependency analysis, and generators all stand on it.

Two practical consequences worth knowing as a newcomer:

- **A change to the syntax-tree factories has a very wide blast radius.** Touching `MediatorFactoryFinder` or a mediator factory affects not just the diagram, but breakpoints, try-out, dependency scanning, resource usages, and the property forms.
- **Many "simple" listing endpoints are I/O-heavy.** `getOverviewModel`, `directoryTree`, and `getResourceUsages` re-parse large numbers of files per call — useful context when you eventually look at performance.

---

## 6. Honourable mentions: XML, but not *those* trees

A few non-LS features do parse XML, but with **third-party/standard parsers instead of the LemMinX DOM or Synapse syntax tree**. If your definition of "consumes the XML tree" is strictly the in-house trees, these are excluded — but they're worth knowing about:

| Feature | Parser used | Detail |
|---|---|---|
| `generateSchema` / `generateSchemaFromContent` (XML input) | **Apache Axiom** | [SchemaGeneratorForXML](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/schemagen/util/SchemaGeneratorForXML.java#L97) builds an `OMElement` tree via `AXIOMUtil.stringToOM`, converts it to JSON, then generates the schema |
| `updateProperty`, `updateDependency`, deploy-plugin endpoints, `getOverviewPageDetails` (pom part) | **SAX + StAX (Woodstox) + W3C DOM** | [PomParser](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/parser/pom/PomParser.java) reads `pom.xml` with SAX, finds edit positions with a Woodstox stream reader, and builds/serializes snippets with `DocumentBuilder`/`Transformer` |
| `generateQueries` | **W3C DOM (output only)** | [QueryGenerator](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/dataService/QueryGenerator.java#L72) introspects the DB via JDBC and *emits* the `.dbs` data-service XML with `DocumentBuilder` |
| Connector-config init | **W3C DOM** | [ConnectorConfigService](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/parser/connectorConfig/ConnectorConfigService.java#L554) scans `localEntries/*.xml` with an XXE-hardened `DocumentBuilder` |

And for completeness, the non-LS endpoints that touch **no XML tree of any kind**:

- **Plain directory scans / string work:** `getRegistryFiles`, `getResourceFiles`, `getArtifactFiles` (recursive `listFiles()` + regex), `getConfigurableEntries` / `getConfigurableList` / `updateConfigFile` (line-by-line `.properties`/`.env` parsing).
- **JDBC only:** `testDBConnection`, `loadDriverAndTestConnection`, `fetchTables`, `getDynamicFields`, `getStoredProcedures`, `getInputOutputMappings`, `checkDBDriver` and the driver add/remove/modify endpoints.
- **Network/file/library work:** connector & driver downloads, `generateConnector` (external `mi-connector-generator` library), `isEqualSwaggers` (swagger-parser), `pdfToImagesBase64` (PDFBox-style rendering), `getConnectionUISchema` / `getLocalInboundConnectors` / `saveInboundConnectorSchema` (JSON only).
- **Expression-string-only LS features** (listed here since it surprises people): `expressionValidation` and `signatureHelp` never open the document — they work purely on the expression text (ANTLR grammar / preloaded function metadata). Their siblings `expressionCompletion` and `expressionHelperData` **do** parse the document (DOM + syntax tree via `ServerLessTryoutHandler`) to know which variables exist at the cursor.

---

## 7. Key takeaways

1. **This server is a language server plus a full IDE backend in one process.** Of the **81** custom `synapse/*` endpoints (counted from the 81 `@JsonRequest`/`@JsonNotification` methods on `ISynapseLanguageService`), only **8** are classic language-server features; the other **73** exist to power the MI VS Code extension's UI and tooling. Of those 73, **32 depend on the language-analysis machinery** (29 parse the DOM/syntax tree during the request, 3 serve data produced by DOM parsing at load time) and **41 are fully isolated** — see §8.
2. **Two trees, one pipeline.** LemMinX's position-aware `DOMDocument` is the low-level parse; the Synapse `STNode` syntax tree is the semantic model built on top of it. `Utils.getDOMDocument` + `SyntaxTreeGenerator.buildTree` is the idiom you will see everywhere.
3. **Fourteen non-LS feature areas depend on those trees anyway** — debugger, dependency/overview analysis, resource discovery & usage finding, connection finding, project explorer, mediator forms & config generation, try-out, Swagger generation, inbound-endpoint forms, artifact-type detection, connector metadata reading, and MCP tool listing. Their *purpose* is tooling; their *implementation* is language analysis.
4. **The syntax-tree factories are the product's real backbone.** If you modify them, expect effects far beyond the `syntaxTree` endpoint.
5. **Watch the parser zoo.** Besides the two in-house trees, the codebase also uses Axiom, SAX, StAX (Woodstox), and W3C DOM in specific corners (schema generation, pom editing, data-service generation) — don't assume "XML parsing" means the same thing in every package.

---

## 8. Exact count distribution

The interface [ISynapseLanguageService.java](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/ISynapseLanguageService.java) declares exactly **81** RPC methods (81 `@JsonRequest`/`@JsonNotification` annotations).

```
Total endpoints ............................................. 81
├── LS-specific ..............................................  8   (9.9%)
└── Non-LS-specific .......................................... 73   (90.1%)
    ├── Depend on the LS analysis machinery (DOM/syntax tree)  32   (43.8% of non-LS)
    │   ├── Parse trees DURING the request (direct) ......... 29
    │   └── Serve tree-derived data loaded at init (indirect)   3
    └── Fully isolated (no DOM / no Synapse syntax tree) ..... 41   (56.2% of non-LS)
```

"Depend on the LS analysis machinery" means the endpoint's data pipeline uses LemMinX `DOMDocument` and/or the Synapse syntax tree (`STNode`) — the same infrastructure that powers the 8 LS-specific endpoints. XML parsed with *other* parsers (Axiom/SAX/StAX/W3C DOM) does not count as a dependency.

### 8.1 LS-specific (8)

`syntaxTree`, `diagnostic`, `codeDiagnostic`, `definition`, `expressionCompletion`, `signatureHelp`, `expressionValidation`, `expressionHelperData`

### 8.2 Non-LS, tree-dependent — direct (29)

These parse the DOM and/or build the syntax tree while serving the request:

| Area | Endpoints |
|---|---|
| Project views | `directoryTree`, `getProjectExplorerModel`, `getProjectIntegrationType`, `getOverviewModel` |
| Resources & dependencies | `availableResources`, `loadDependentResources`, `getResourceUsages`, `dependencyTree` |
| Connections & connectors | `connectorConnections`, `getConnectionUISchema` ([ConnectionHandler.java:82](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectionHandler.java#L82)), `getConnectorInfo`, `resolveConnector`, `isDuplicateConnector` (via [ConnectorReader.getConnectorName](org.eclipse.lemminx/src/main/java/org/eclipse/lemminx/customservice/synapse/connectors/ConnectorReader.java#L583)), `updateConnectorDependencies` (triggers connector re-load → `ConnectorReader` DOM parses) |
| Mediator forms | `getMediators`, `getMediatorUISchema`, `getMediatorUISchemaWithValues`, `generateSynapseConfig`, `getMCPTools` |
| Try-out | `tryOutMediator`, `mediatorInputOutputSchema`, `testConnectorConnection` (delegates to the tree-parsing `TryOutHandler`) |
| Debugger | `getBreakpointInfo`, `validateBreakpoints`, `stepOverBreakpoint` |
| Generation & typing | `generateAPI` (WSDL path), `swaggerFromAPI`, `getInboundConnectorSchema` (documentPath variant), `getArtifactType` |

### 8.3 Non-LS, tree-dependent — indirect (3)

These do no parsing at request time, but the data they serve was produced by `ConnectorReader`'s DOM parsing of `connector.xml` when connectors were loaded:

`availableConnectors`, `downloadDriverForConnector`, `getDriverMavenCoordinates` (all read from the in-memory `ConnectorHolder`)

### 8.4 Non-LS, fully isolated (41)

No LemMinX DOM and no Synapse syntax tree anywhere in their pipeline:

| Area | Count | Endpoints |
|---|---|---|
| Databases / JDBC | 11 | `testDBConnection`, `loadDriverAndTestConnection`, `checkDBDriver`, `addDBDriver`, `removeDBDriver`, `modifyDBDriver`, `generateQueries`, `fetchTables`, `getDynamicFields`, `getStoredProcedures`, `getInputOutputMappings` |
| Plain file scanners | 3 | `getRegistryFiles`, `getResourceFiles`, `getArtifactFiles` |
| Config files (text) | 3 | `getConfigurableEntries`, `getConfigurableList`, `updateConfigFile` |
| pom.xml (SAX/StAX/W3C) | 5 | `updateProperty`, `updateDependency`, `updateMavenDeployPlugin`, `getMavenDeployPluginDetails`, `removeMavenDeployPlugin` |
| Dependency downloads | 2 | `refetchIntegrationProjectDependencies`, `getDependencyStatusList` |
| Connector config (JSON/W3C) | 6 | `getConnectorDependencies`, `updateConnectorDependencyOverride`, `resetConnectorDependencyOverrides`, `updateConnectorFlags`, `updateGlobalConnectorFlags`, `initConnectorConfig` |
| Inbound connectors (JSON) | 4 | `getInboundInfo`, `saveInboundConnectorSchema`, `getLocalInboundConnectors`, `getLocalInboundEndpointsListForCopilot` |
| Payload schema gen (Axiom) | 2 | `generateSchema`, `generateSchemaFromContent` |
| Overview page | 1 | `getOverviewPageDetails` (SAX pom + text config only) |
| Misc | 4 | `isEqualSwaggers`, `pdfToImagesBase64`, `shutDownTryoutServer`, `generateConnector` |

*(11+3+3+5+2+6+4+2+1+4 = 41 ✓; 29+3+41 = 73 ✓; 73+8 = 81 ✓)*
