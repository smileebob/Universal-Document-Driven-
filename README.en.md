[中文](README.md) | **English**

# Universal Document-Driven Platform

## Positioning

An **open-source shared service for business documents and orders**, built for CRM, WMS, ERP, order management, and similar systems in manufacturing and sales. It consolidates entity/document modeling, state machines, validation rules, and transaction orchestration into one reusable capability, so each system does not rebuild its own document logic.

Problems it addresses:

- **Siloed applications** — inconsistent document logic, divergent semantics, and costly integration;
- **Data governance** — one set of document models, lifecycle semantics, and field constraints across systems, making aggregation, traceability, and reconciliation easier.

**Best fit:** teams building or unifying multiple lifecycle-driven business documents.
**Not a fit:** file (PDF/Word) management, a complete ERP, a full multi-level approval workflow engine, or a multi-tenant SaaS platform. These are outside the current scope.

## Design principles

1. **Metadata is an executable business definition** — fields, types, required/read-only/unique rules, precision, references, states, and validation rules come from configuration; UI rendering and server-side validation share the same definition.
2. **Configuration and runtime are separate** — `meta-service` manages definitions and publishing, `runtime-service` executes business operations, and `doc-gateway` is the only external entry point.
3. **A state transition is a business command** — document states can only change through the transition endpoint; ordinary updates cannot bypass the state machine, rules, concurrency control, or history.
4. **Consistency follows physical boundaries** — primary-key reads are strongly consistent; lists rely on asynchronous indexes; single-partition transactions, Saga compensation, and Outbox events have explicit limits and are not presented as global ACID transactions.
5. **Contract first, then implementation and verification** — requirements → design → API/storage constraints → staged delivery → contract-level acceptance. AI-assisted development is a delivery method, not a built-in business capability of the platform.

```text
Config console ──definitions/publish──▶ meta-service (entities, documents, state machines, rules, tx steps)
                                             │ invalidation broadcast
Business UI / external systems ──▶ doc-gateway ──▶ runtime-service (data, transitions, query, events, Saga)
                                             │
                                 Tablestore / Redis / RocketMQ
```

## Capabilities and boundaries

| Capability | Current implementation | Boundary |
|---|---|---|
| Entity/document modeling | Header fields, detail tables, references, unique, required, read-only, length/precision | No formula calculation, auto-assignment, or field linkage |
| Metadata-driven UI | Lists, forms, details, and state timeline | Specialized interaction pages still require custom code |
| State machine | Transition configuration, legality checks, concurrency control, state history | Not a general BPM / multi-level approval engine |
| Validation rules | SpEL expression and HTTP interface rules bound to states/actions | Rules validate only; they do not modify data |
| Transactions & events | Same-table immutable operation log, ordered external steps, reverse compensation, per-document ordered delivery, dead-letter blocking | History/audit projections are eventually consistent; unknown Saga outcomes go manual |
| Query & audit | Query DSL, pagination/filter/sort, operation audit and state history | Deep pagination is constrained by index semantics; retention policy is deployment-specific |
| Authorization | Functional roles: platform admin / business admin / business user | No multi-tenancy, organizational isolation, or field-level permissions in this phase |

## End-to-end flow

1. An administrator defines and publishes base entities (products, customers, etc.).
2. Define document headers, details and states; configure the state machine, validation rules and optional external steps.
3. Publish the definition and mount it as a menu.
4. Business users create documents and perform transitions (submit/review/release).
5. Transitions run validation, concurrency control, external steps and event publishing; results are traceable from detail, history and operations pages.

## Tech stack

Java 17 / Spring Boot 3.5 / Spring Cloud Alibaba 2025 (Nacos, Sentinel, Gateway) / Alibaba Cloud Tablestore (local simulation supported) / Redis / RocketMQ (local mock supported) / Vue 3 + Vite + Ant Design Vue + AntV G6.

## Module layout

```text
Document-Driven/
├── doc-platform-parent/          # Backend (Maven aggregator)
│   ├── doc-gateway/              # Gateway: routing, JWT auth, rate limiting, CORS
│   ├── meta-service/             # Configuration domain: definitions, publishing, menus, cache invalidation
│   ├── runtime-service/          # Runtime domain: data, transitions, query, events, Saga, operations
│   ├── meta-service-api/         # Configuration-domain Feign contracts and DTOs
│   ├── runtime-service-api/      # Runtime-domain Feign contracts and DTOs
│   ├── doc-common/               # Storage/cache/messaging anti-corruption layer, errors, security components
│   ├── deploy/                   # Development/integration deployment script
│   └── docs/                     # Commit checklist, OpenAPI snapshots, performance baseline, alert rules
└── doc-web/                      # Vue 3 frontend (config console, runtime pages, operations)
```

## Quick start

### Prerequisites

| Dependency | Requirement | Notes |
|---|---|---|
| JDK / Maven | JDK 17+, Maven 3.8+ | Backend build |
| Node.js / npm | Node 20+ | Frontend build |
| Nacos | A local standalone instance is sufficient | Required for gateway/service discovery |
| Redis | A local standalone instance is sufficient | Locks, cache, and mock-message bridge |

**"Zero cloud resources" does not mean "zero local middleware":** under the dev profile Tablestore runs in memory simulation and RocketMQ runs as an in-process mock, but Nacos and Redis must still be available locally (defaults `127.0.0.1:8848` / `127.0.0.1:6379`). Simulated data is lost on restart; mock messaging does not prove real broker delivery semantics.

### 1. Build the backend

```bash
cd doc-platform-parent
mvn clean package -DskipTests
```

### 2. Start the three services (one terminal each, dev profile)

```bash
export SPRING_PROFILES_ACTIVE=dev
export OTS_MODE=simulation
export DOC_MQ_MODE=mock
```

```bash
# Terminal A: gateway (:8080; dev profile disables auth for local demo)
java -jar doc-gateway/target/doc-gateway-*.jar

# Terminal B: configuration service (:8081)
java -jar meta-service/target/meta-service-*.jar

# Terminal C: runtime service (:8082)
java -jar runtime-service/target/runtime-service-*.jar
```

PowerShell: `$env:SPRING_PROFILES_ACTIVE="dev"; $env:OTS_MODE="simulation"; $env:DOC_MQ_MODE="mock"`.

A service is considered started only when the log has no sustained errors and `curl http://localhost:8080/actuator/health` (and the same for `:8081`/`:8082`) returns `UP`. The first administrator definition must be created via the API or UI; the project does not ship reusable production accounts.

### 3. Start the frontend

```bash
cd doc-web        # from the repository root
npm install
npm run dev
```

Open `http://localhost:5173`. Local login is a **demo login** (role selection is allowed for development only and does not represent the real permission model). Production must integrate a real identity provider and enable gateway authentication.

### 4. Stop

For development: `cd doc-platform-parent && ./deploy/app.sh stop doc-gateway` (development script only; production should use systemd or a container orchestrator with health probes).

## Environment variables

### Required for development

| Variable | Local default | Purpose |
|---|---|---|
| `NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` | `127.0.0.1:8848` / empty | Registry and configuration center |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | Cache, locks, mock bridge |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | empty | Redis ACL credentials (mandatory in production) |
| `OTS_MODE` | `simulation` under dev | `simulation` (dev/test only) or `ots` |
| `DOC_MQ_MODE` | `mock` | `mock` (dev/test only) or `rocketmq` |
| `SERVER_PORT` | 8080/8081/8082 | Per-service port override |

### Production / real middleware

| Variable | Purpose |
|---|---|
| `OTS_ENDPOINT` / `OTS_INSTANCE` | Tablestore endpoint and instance |
| `OTS_ACCESS_KEY_ID` / `OTS_ACCESS_KEY_SECRET` | Tablestore credentials |
| `OTS_AUTO_CREATE` | Startup table creation (must be false in production) |
| `ROCKETMQ_ENDPOINT` / `ROCKETMQ_ACCESS_KEY` / `ROCKETMQ_SECRET_KEY` | RocketMQ connection settings |
| `GATEWAY_JWT_SECRET` | JWT signing secret (HS256; at least 32 bytes in production) |
| `GATEWAY_JWT_ISSUER` / `GATEWAY_JWT_AUDIENCE` | Optional strict validation; mismatching tokens are rejected |
| `GATEWAY_TRUSTED_PROXIES` | Trusted reverse proxies (comma-separated; only their XFF is honored) |
| `GATEWAY_AUTH_ENABLED` | Must be true in production; false by default under dev |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | Gateway origin allowlist (never `*` with credentials) |
| `SENTINEL_DASHBOARD` | Sentinel console (monitoring only; rules are governed via Nacos) |

### Tuning (optional, defaults provided)

`DOC_CACHE_L1_TTL_MINUTES` (60), `DOC_CACHE_L2_TTL_MINUTES` (5), `DOC_CACHE_WARMUP_ENABLED` (true), `DOC_SCHEMA_INVALIDATION_MQ` (true), `RECONCILE_UNIQUE_ORPHAN` (false), `REDIS_TIMEOUT_MS` (2000ms), `REDIS_CONNECT_TIMEOUT_MS` (2000ms).

> Production startup guards: under the prod profile, simulation storage, mock messaging, and startup auto-create are rejected; the gateway must enable authentication with a strong secret. Misconfiguration fails fast instead of silently degrading.

## Known limitations and verification status

- The rule engine validates only; there is no amount calculation, assignment, or field linkage. `unique` currently applies to document header fields only; uniqueness of detail sub-fields is not defined;
- The business row and its immutable operation-log row commit in the same Tablestore local transaction. History, events, and audit are idempotently projected from that durable log and may lag briefly. After eight consecutive projection failures the log becomes manual, and the source log is retained; the system never fabricates a successful projection;
- Publishing a new searchable field uses a `{index}_reindex` brother candidate → full sync → `switchIndexName` → keep the old index. The publish request fails while the candidate is not ready and leaves the definition in draft; the old index keeps serving. Old-index cleanup is disabled by default and requires an explicit rollback window; OTS retains only one brother rollback slot at a time;
- Multidimensional indexes are built asynchronously: newly written rows may briefly be invisible to list filters, while detail reads by primary key are unaffected;
- Events are delivered in per-document `event_seq` + checkpoint order. A failed event blocks later events until retry succeeds; exhausted retries block the document's sequence and raise an alert. A crash after send but before acknowledgement re-sends the same `event_id` (at-least-once), so consumers must be idempotent;
- Outbox rows created before this upgrade have no `event_seq`; they must be drained or replayed through a migration window before strict ordering is enabled;
- Existing deployments must run the V2 bootstrap first (new `event_delivery_cursor`, `index_publish_task`, and two incremental indexes); production must not rely on startup auto-create and should run it explicitly in the release pipeline;
- Multi-tenancy, complex approval, print templates, and bulk Excel import/export are out of scope;
- The [performance baseline](doc-platform-parent/docs/perf-report.md) comes from a local simulation environment and **does not represent production capacity**; re-test with real OTS/RocketMQ/Redis;
- This round was verified on a real OTS test instance for atomic operation logs, projection replay, ordered delivery, Saga crash recovery across two instances, and online index switchover. Browser E2E still needs to be run in the target environment.

## API and development documents

- [Pre-commit review checklist](doc-platform-parent/docs/commit-review-checklist.md)
- [OpenAPI snapshots](doc-platform-parent/docs/openapi/)
- [Alert rules](doc-platform-parent/docs/alert-rules.md)
- [Simulation performance report](doc-platform-parent/docs/perf-report.md)

## Contributing

Issues and pull requests are welcome. Every commit must pass the [pre-commit review checklist](doc-platform-parent/docs/commit-review-checklist.md) (blacklisted paths, zero-tolerance secret scan, repository-size check). The backend follows Spring Cloud Alibaba conventions and the frontend follows Vue conventions.

## License

Licensed under **GPL-3.0-or-later**. See [LICENSE](LICENSE).

Copyright © 2026 Document-Driven Platform contributors.
