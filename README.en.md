[中文](README.md) | **English**

# Universal Document-Driven Platform

## Positioning

The Universal Document-Driven Platform is an **open-source shared service for documents and orders**, built for business systems in manufacturing and sales — CRM, WMS, ERP, order management, and the like. It lets each business system gain document and order capabilities fast, on top of a **single technical standard** (unified entity/document modeling, state machines, rule engine, and transaction model) — instead of reinventing them one system at a time.

The core problems it solves:

- **Siloed applications**: without a shared standard, every business system rebuilds its own document logic, leading to inconsistent standards, divergent semantics, and painful integration;
- **Data governance at scale**: document models, state semantics, and data standards are unified globally, so cross-system data stays consistent by design — making aggregation, traceability, and governance dramatically easier down the road.

## Core Capabilities

- **Metadata-driven entity & document modeling**: field definitions, validation rules, reference relations, and uniqueness constraints — all configured without code, fully driven by metadata;
- **State machine engine**: document states and transition events, concurrency and consistency control (optimistic concurrency + state transition timeline), visual editing with AntV G6;
- **Rule engine**: validation and computation rules bound to states and events, executed in a sandboxed SpEL environment;
- **Transactions & messaging**: Transactional Outbox, Saga orchestration, cache-invalidation broadcasting;
- **Unified runtime data service**: document create/read, state transitions, and detail timelines all served through one set of runtime APIs;
- **Operations & monitoring**: health checks, observability endpoints, audit traceability.

Tech stack: Spring Boot 3.5 / Spring Cloud Alibaba (Nacos / Sentinel / Gateway), Alibaba Cloud Tablestore (with a local simulation mode), Redis, RocketMQ (with a local mock mode), Vue 3 + Vite + Ant Design Vue + AntV G6.

## Module Layout

```
Document-Driven/
├── doc-platform-parent/        # Backend (Maven multi-module aggregator)
│   ├── doc-gateway/            # API gateway (routing, JWT auth, rate limiting)
│   ├── meta-service/           # Metadata service (entity/document definitions, state machines, rule config)
│   ├── runtime-service/        # Runtime service (document data, state transitions, transactions)
│   ├── meta-service-api/       # meta-service external contracts (Feign/DTO)
│   ├── runtime-service-api/    # runtime-service external contracts (Feign/DTO)
│   ├── doc-common/             # Shared library (Tablestore anti-corruption layer, caching, messaging facade, domain basics)
│   ├── deploy/                 # Deployment scripts (app.sh: start/stop/status/restart)
│   └── docs/                   # Engineering docs (including the pre-commit review checklist)
└── doc-web/                    # Frontend (Vue 3 + Vite: config console + runtime pages + ops monitoring)
```

## Quick Start

### Run Locally with Zero Cloud Resources (dev profile + simulation mode)

The backend supports a **zero-cloud-dependency** launch: under the dev profile, Tablestore defaults to the in-memory simulation mode (`OTS_MODE=simulation`) and messaging defaults to the mock mode (`DOC_MQ_MODE=mock`) — the full flow runs locally with no Alibaba Cloud resources at all.

```bash
# 1. Build (inside doc-platform-parent)
mvn clean package -DskipTests

# 2. Start the three services in order (ports 8080/8081/8082)
java -jar doc-gateway/target/doc-gateway-*.jar         # Gateway :8080
java -jar meta-service/target/meta-service-*.jar       # Metadata service :8081
java -jar runtime-service/target/runtime-service-*.jar # Runtime service :8082

# 3. Start the frontend (port 5173)
cd doc-web
npm install
npm run dev
```

> Note: under the dev profile, if no local Nacos/Redis is present, the corresponding components degrade gracefully to the placeholder defaults in application.yml. To connect real Nacos/Redis/Tablestore/RocketMQ, just inject the values via environment variables (see below).

### Sensitive Configuration Is Always Injected via Environment Variables

This project follows a **zero-plaintext-secrets** policy: keys, endpoints, and credentials are never written into code or config files. They are injected exclusively through environment variables (application.yml references them as `${ENV:default}` placeholders, falling back to dev-only local defaults when unset).

## Environment Variables

| Variable | Description |
| --- | --- |
| `NACOS_SERVER_ADDR` | Nacos registry/config-center address |
| `NACOS_NAMESPACE` | Nacos namespace |
| `REDIS_HOST` / `REDIS_PORT` | Redis host and port |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | Redis ACL credentials |
| `REDIS_TIMEOUT_MS` / `REDIS_CONNECT_TIMEOUT_MS` | Redis timeout parameters |
| `OTS_MODE` | Tablestore assembly mode: `ots` (real instance, default) / `simulation` (in-memory, dev/test only) |
| `OTS_ENDPOINT` / `OTS_INSTANCE` | Tablestore endpoint and instance name |
| `OTS_ACCESS_KEY_ID` / `OTS_ACCESS_KEY_SECRET` | Tablestore access keys |
| `OTS_AUTO_CREATE` | Auto-create tables on startup (dev/test only; disabled in production) |
| `DOC_MQ_MODE` | Messaging facade mode: `mock` (default, in-process simulation + Redis Pub/Sub bridge) / `rocketmq` (real RocketMQ) |
| `ROCKETMQ_ENDPOINT` | RocketMQ endpoint (name-server semantics) |
| `ROCKETMQ_ACCESS_KEY` / `ROCKETMQ_SECRET_KEY` | RocketMQ access keys |
| `GATEWAY_JWT_SECRET` | Gateway JWT signing secret (HS256) |
| `GATEWAY_AUTH_ENABLED` | Gateway authentication switch |

> The authoritative list and injection methods (export / `/etc/environment` / systemd EnvironmentFile) are documented in the "Environment Variable Injection" section of [`doc-platform-parent/deploy/app.sh`](doc-platform-parent/deploy/app.sh).

### Runtime Tuning Variables (Optional, All with Defaults)

The following variables are for production tuning; when unset, they fall back to the defaults in the config files:

| Variable | Default | Description |
| --- | --- | --- |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Outbox relay polling interval (ms) |
| `OUTBOX_BATCH_SIZE` | `50` | Outbox relay batch size |
| `OUTBOX_MAX_RETRY` | `16` | Max retries for Outbox messages |
| `DOC_CACHE_L1_TTL_MINUTES` | `60` | Document-definition L1 (Redis) cache TTL (minutes) |
| `DOC_CACHE_L2_TTL_MINUTES` | `5` | Document-definition L2 (local Caffeine) cache TTL (minutes) |
| `DOC_CACHE_WARMUP_ENABLED` | `true` | Cache warm-up on startup |
| `DOC_LOCK_WAIT_MS` | `0` | Distributed-lock acquisition wait time for state transitions (ms) |
| `DOC_LOCK_LEASE_MS` | `30000` | Distributed-lock lease duration for state transitions (ms) |
| `MQ_TOPIC_DOC_EVENT` | `doc-event` | Topic for document events delivered by the Outbox |
| `MQ_TOPIC_CACHE_INVALIDATE` | `doc-cache-invalidate` | Topic for cache-invalidation broadcasts |
| `MQ_GROUP_CACHE_INVALIDATE` | `doc-cache-invalidate-cg` | Consumer group for cache-invalidation broadcasts |
| `SCHEMA_CACHE_TTL_SECONDS` | `300` | meta-service schema cache TTL (seconds; TTL fallback for broadcast invalidation) |
| `RECONCILE_UNIQUE_ORPHAN` | `false` | Scheduled reconciliation of uniqueness-constraint orphan records (the manual trigger endpoint is unaffected by this switch) |
| `DOC_SCHEMA_INVALIDATION_MQ` | `true` | MQ broadcast switch for schema invalidation (falls back to Caffeine TTL when off) |

## Contributing

Issues and pull requests are welcome. Please note:

- **Every commit must first pass the pre-commit review checklist defined in [`doc-platform-parent/docs/commit-review-checklist.md`](doc-platform-parent/docs/commit-review-checklist.md)** (blacklisted-path check, zero-tolerance sensitive-string scan, repo-size spot check). Commits that fail the checklist are rejected outright;
- The backend follows Spring Cloud Alibaba conventions and the frontend follows Vue conventions — see the in-repo documentation for details;
- Commit messages should clearly describe the motivation and impact of the change.

## License

This project is licensed under **GPL-3.0-or-later** (GNU General Public License v3.0 or later). The full license text is available in [LICENSE](LICENSE).

Copyright © 2026 Document-Driven Platform contributors.
