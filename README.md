# Payment Orchestrator

[![build](https://github.com/iTzTalha/payment-orchestrator/actions/workflows/build.yml/badge.svg)](https://github.com/<your-username>/<your-repo>/actions/workflows/build.yml)

A backend-only payment orchestration service, inspired by platforms like Yuno, Primer, and Spreedly. Accepts payment requests, routes them by method (CARD → Stripe, UPI → Razorpay) to a pluggable connector layer, retries transient PSP failures, fails over between PSPs, enforces idempotency, and tracks a strict status lifecycle.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Data JPA · H2 (in-memory) · Spring Retry · Lombok

**Status:** all 67 automated tests green. Slim, stable API contract. Real PSP HTTP integration is stubbed; the orchestration core is production-shaped.

---

## Request flow at a glance

One request, top to bottom. Each box is a real class; arrows are real method calls. The deep dive lives further down in [Architecture](#architecture).

```
                ┌──────────────────────┐
   HTTP ──────▶ │  PaymentController   │  validation, idempotency-key extraction
                └──────────┬───────────┘
                           │
                ┌──────────▼───────────┐     ┌──────────────────────┐
                │  IdempotencyService  │────▶│ IdempotencyRecord DB │
                └──────────┬───────────┘     └──────────────────────┘
                           │ once-only
                ┌──────────▼───────────┐     ┌──────────────┐
                │   PaymentService     │────▶│  Payment DB  │
                └──────────┬───────────┘     └──────────────┘
                           │
                ┌──────────▼───────────┐
                │   PaymentProcessor   │  routes + retries + failover
                └──────────┬───────────┘
                           │
              ┌────────────▼────────────┐
              │      RoutingTable       │   CARD → [stripe, razorpay]
              │     (EnumMap-backed)    │   UPI  → [razorpay, stripe]
              └────────────┬────────────┘
                           │
              ┌────────────▼────────────┐
              │     ConnectorInvoker    │   @Retryable (exponential backoff)
              └────────────┬────────────┘
                           │
            ┌──────────────┼──────────────────┐
            ▼              ▼                  ▼
     StripeConnector  RazorpayConnector  (future PSPs)
```

---

## Table of contents

## Quickstart

Five minutes from `git clone` to seeing a payment succeed.

### Prerequisites

- **JDK 21+** (`java -version`)
- *(Optional)* `curl` for the API tour below

No database, broker, or cache to install — H2 runs in-memory and the connectors are in-process stubs. **Maven is not required** either; the bundled wrapper (`./mvnw` / `mvnw.cmd`) downloads the right Maven version on first run.

### 1. Clone and build

```bash
git clone <repo-url> orchestrator
cd orchestrator
./mvnw -DskipTests package        # Linux / macOS
mvnw.cmd -DskipTests package      # Windows
```

Produces `target/orchestrator-0.0.1-SNAPSHOT.jar`.

### 2. Run

```bash
./mvnw spring-boot:run            # Linux / macOS
mvnw.cmd spring-boot:run          # Windows
```

Or run the packaged jar directly:

```bash
java -jar target/orchestrator-0.0.1-SNAPSHOT.jar
```

Service listens on **`http://localhost:8080`**. Startup logs end with `Started OrchestratorApplication in 2.X seconds`.

### 3. Make a payment

```bash
# Create
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-123-key-001' \
  -d '{"merchantReference":"order-123","amountMinor":1999,"currency":"USD","paymentMethod":"CARD"}'
```

You should see `201 Created` with a JSON body containing an `id` and `status: "SUCCEEDED"`.

```bash
# Replay (same key, same body → same response, no new side effect)
# Just run the exact same curl again.

# Fetch
curl http://localhost:8080/api/v1/payments/<id-from-above>

# Inspect the PSP attempts
curl http://localhost:8080/api/v1/payments/<id-from-above>/attempts
```

Full endpoint specs in the [API reference](#api-reference) section.

### 4. Run the tests

```bash
./mvnw test                       # Linux / macOS
mvnw.cmd test                     # Windows
```

Expected: **`Tests run: 67, Failures: 0, Errors: 0, Skipped: 0`** in ~30 s on a warm JDK. Surefire reports land in `target/surefire-reports/`. Full inventory in the [Test catalogue](#test-catalogue) section.

To run a single class or method:

```bash
./mvnw -Dtest=IdempotencyServiceIT test
./mvnw -Dtest=IdempotencyServiceIT#expiredCompletedRecord_isEvictedOnReplay_andTreatedAsFreshRequest test
```

---

## Configuration

All knobs live in `src/main/resources/application.yaml`. The interesting ones:

| Key | Default | Effect |
|---|---|---|
| `orchestrator.routing.rules.<METHOD>` | `[stripe, razorpay]` / `[razorpay, stripe]` | Ordered connector chain per payment method |
| `orchestrator.retry.max-attempts` | `3` | Per-PSP retry budget for transient (retryable) failures |
| `orchestrator.retry.initial-backoff-ms` | `200` | First retry delay |
| `orchestrator.retry.backoff-multiplier` | `2.0` | Multiplier on each subsequent retry |
| `orchestrator.retry.max-backoff-ms` | `2000` | Cap on retry delay |
| `orchestrator.retry.failover-on-non-retryable` | `true` | Whether a non-retryable failure (e.g. card declined) triggers the next PSP in the chain |
| `orchestrator.idempotency.ttl` | `PT24H` | How long an idempotency record blocks re-use of the key |
| `orchestrator.idempotency.cleanup-cron` | hourly | When the in-process sweeper purges expired records |

Override any of these from the command line:

```bash
java -jar target/orchestrator-0.0.1-SNAPSHOT.jar \
  --server.port=9090 \
  --orchestrator.retry.max-attempts=5 \
  --orchestrator.routing.rules.CARD=razorpay,stripe
```

The test profile (`src/test/resources/application-test.yaml`) lowers retry delays to single-digit millis and disables the cleanup cron (`"-"`) so the suite finishes sub-30s.

---

## API reference

Base URL: `http://localhost:8080`  ·  All payloads UTF-8 JSON  ·  All errors RFC 7807 `application/problem+json`.

### `POST /api/v1/payments`

Create a payment. Once-only per `Idempotency-Key`. Synchronous: the response carries the terminal status of the orchestration.

#### Request

```
POST /api/v1/payments HTTP/1.1
Content-Type: application/json
Idempotency-Key: order-123-key-001

{
  "merchantReference": "order-123",
  "amountMinor": 1999,
  "currency": "USD",
  "paymentMethod": "CARD"
}
```

| Header             | Required | Type    | Constraint                                  |
|--------------------|----------|---------|----------------------------------------------|
| `Content-Type`     | yes      | string  | exactly `application/json`                  |
| `Idempotency-Key`  | yes      | string  | `NotBlank`, length 8..128                   |

| Body field          | Required | Type    | Constraint                                                  |
|---------------------|----------|---------|--------------------------------------------------------------|
| `merchantReference` | yes      | string  | `NotBlank`, max 64 chars                                    |
| `amountMinor`       | yes      | integer | `Positive` (≥ 1) — minor units of `currency`                |
| `currency`          | yes      | string  | ISO-4217, `^[A-Z]{3}$`                                       |
| `paymentMethod`     | yes      | enum    | `CARD` · `UPI` · `NET_BANKING` · `WALLET` (routing rule must exist) |

#### Success — 201 Created

```json
{
  "id": "7c2c9e3a-0a52-4f6e-b1ce-3a6f8d4e2c11",
  "merchantReference": "order-123",
  "amountMinor": 1999,
  "currency": "USD",
  "paymentMethod": "CARD",
  "status": "SUCCEEDED"
}
```

| Field              | Type    | Notes                                          |
|--------------------|---------|------------------------------------------------|
| `id`               | UUID    | Server-generated, immutable                    |
| `merchantReference`| string  | Echo                                           |
| `amountMinor`      | integer | Echo                                           |
| `currency`         | string  | Echo                                           |
| `paymentMethod`    | enum    | Echo                                           |
| `status`           | enum    | `SUCCEEDED` or `FAILED` (terminal at response time) |

> A `FAILED` payment is still a successful API call — the orchestration completed; the payment didn't. Use `GET /{id}/attempts` for the per-PSP forensics.

#### Errors

| HTTP | `title`                         | Triggers                                                                 |
|------|----------------------------------|---------------------------------------------------------------------------|
| 400  | `Validation failed`             | Body field constraint fails (returns `errors` map keyed by field name)   |
| 400  | —                                | Missing `Idempotency-Key` header, malformed JSON, malformed UUID in path |
| 409  | `Idempotency key conflict`      | Same key + different body **or** key currently in progress                |
| 409  | `Invalid status transition`     | Internal state-machine violation (should never reach a caller)            |
| 415  | —                                | `Content-Type` is not `application/json`                                  |
| 422  | `Unsupported payment method`    | No routing rule configured for the supplied `paymentMethod`              |
| 500  | `Internal server error`         | Unexpected exception                                                      |

Example error body:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "errors": {
    "amountMinor": "must be greater than 0",
    "currency":    "must be a 3-letter ISO-4217 code"
  }
}
```

#### Idempotency semantics

- **Same key + same body** within TTL → original response returned, no new side effects.
- **Same key + different body** within TTL → 409.
- **Same key while original is still running** → 409 (`still in progress`).
- **Same key after TTL elapsed** → behaves as a new request; the stale record is evicted on the spot.
- **Key reuse after failure** → record is rolled back; key is reusable immediately.

JSON property order does not affect the idempotency comparison.

### `GET /api/v1/payments/{id}`

Fetch a single payment by its server-generated UUID.

#### Request

```
GET /api/v1/payments/7c2c9e3a-0a52-4f6e-b1ce-3a6f8d4e2c11 HTTP/1.1
```

| Path parameter | Required | Type | Constraint |
|---|---|---|---|
| `id` | yes | UUID | The server-generated `id` returned by `POST /api/v1/payments` |

#### Success — 200 OK

```json
{
  "id": "7c2c9e3a-0a52-4f6e-b1ce-3a6f8d4e2c11",
  "merchantReference": "order-123",
  "amountMinor": 1999,
  "currency": "USD",
  "paymentMethod": "CARD",
  "status": "SUCCEEDED"
}
```

| Field              | Type    | Notes                                                          |
|--------------------|---------|----------------------------------------------------------------|
| `id`               | UUID    | Echo of the path parameter                                     |
| `merchantReference`| string  | As supplied at creation time                                   |
| `amountMinor`      | integer | As supplied at creation time                                   |
| `currency`         | string  | As supplied at creation time                                   |
| `paymentMethod`    | enum    | As supplied at creation time                                   |
| `status`           | enum    | Current status — one of `INITIATED`, `PROCESSING`, `SUCCEEDED`, `FAILED` |

The response shape is identical to the `POST` 201 body. Unlike the POST response (which is always terminal), a `GET` may return a non-terminal status if the payment is still being processed.

#### Errors

| HTTP | `title`              | Triggers                  |
|------|----------------------|---------------------------|
| 400  | —                    | `{id}` is not a valid UUID|
| 404  | `Payment not found`  | No row with that UUID     |

### `GET /api/v1/payments/{id}/attempts`

List every PSP attempt the orchestrator made for this payment, ordered by `attemptNumber` ascending. There is **one row per PSP**, not per retry — internal retries against the same PSP collapse into a single row whose `status` reflects the final outcome on that PSP. Failover to the next PSP creates a new row.

#### Request

```
GET /api/v1/payments/7c2c9e3a-…/attempts HTTP/1.1
```

#### Success — 200 OK

```json
[
  {
    "id": "8a1f0d12-…",
    "attemptNumber": 1,
    "connectorId": "stripe",
    "status": "FAILED_RETRYABLE",
    "errorMessage": "boom",
    "startedAt": "2026-05-30T07:43:33.288Z",
    "endedAt":   "2026-05-30T07:43:33.301Z"
  },
  {
    "id": "9b2e1c34-…",
    "attemptNumber": 2,
    "connectorId": "razorpay",
    "status": "SUCCEEDED",
    "startedAt": "2026-05-30T07:43:33.305Z",
    "endedAt":   "2026-05-30T07:43:33.312Z"
  }
]
```

| Field           | Type     | Notes                                                       |
|-----------------|----------|--------------------------------------------------------------|
| `id`            | UUID     | Attempt-row primary key                                      |
| `attemptNumber` | integer  | 1-based, monotonic per payment                               |
| `connectorId`   | string   | `"stripe"`, `"razorpay"`, ... — the PSP that was contacted   |
| `status`        | enum     | `SUCCEEDED` · `FAILED_RETRYABLE` · `FAILED_NON_RETRYABLE`     |
| `errorMessage`  | string   | Omitted on `SUCCEEDED`                                       |
| `startedAt`     | instant  | ISO-8601 UTC — request dispatch to the PSP                   |
| `endedAt`       | instant  | ISO-8601 UTC — response (or final exception) received        |

#### Errors

| HTTP | `title`              | Triggers                       |
|------|----------------------|--------------------------------|
| 400  | —                    | `{id}` is not a valid UUID     |
| 404  | `Payment not found`  | No payment row with that UUID  |

---

## Architecture

### System context

```
                                  ┌──────────────┐
                                  │   Merchant   │
                                  │   Backend    │
                                  └──────┬───────┘
                                         │ HTTPS, JSON
                                         │ Idempotency-Key header
                                         ▼
                       ┌──────────────────────────────────┐
                       │   Bitforge Payment Orchestrator  │
                       │   (this service — single jar)    │
                       └─┬───────────────────────────┬────┘
                         │                           │
                         │ JDBC                      │ HTTPS (today: in-process stubs)
                         ▼                           ▼
                  ┌────────────┐            ┌──────────────────┐
                  │     DB     │            │   PSPs (Stripe,  │
                  │ (H2 / PG)  │            │ Razorpay, … )    │
                  └────────────┘            └──────────────────┘
```

The orchestrator sits between merchant backends and one or more payment service providers (PSPs). Callers see a uniform API regardless of which PSP fulfilled the request. PSPs are reached through a connector port, so adding a new PSP means adding one Spring bean — no changes to the orchestration core.

Today the connector layer is two in-process stubs (`StripeConnector`, `RazorpayConnector`) returning synthetic reference ids. Real HTTP integration is a flat extension.

### Components

```
┌──────────────────────────── controller ────────────────────────────┐
│  PaymentController       (REST surface, validation)                │
└────────────────────────────────┬───────────────────────────────────┘
                                 │
┌────────────────────────────────▼───────────────────────────────────┐
│  IdempotencyService     once-only guard, keyed by Idempotency-Key  │
└────────────────────────────────┬───────────────────────────────────┘
                                 │
┌────────────────────────────────▼───────────────────────────────────┐
│  PaymentService         create / read / list-attempts              │
└────────────────────────────────┬───────────────────────────────────┘
                                 │
┌────────────────────────────────▼───────────────────────────────────┐
│  PaymentProcessor       status machine + routing + failover loop   │
└────┬────────────────┬───────────────────────────────┬──────────────┘
     │                │                               │
┌────▼─────┐  ┌───────▼────────┐   ┌─────────────────▼─────────────┐
│ Routing  │  │  ConnectorReg. │   │  ConnectorInvoker             │
│  Table   │  │  (id → bean)   │   │  retry with backoff per PSP   │
└──────────┘  └────────────────┘   └─────────────┬─────────────────┘
                                                 │
                                  ┌──────────────▼──────────────┐
                                  │  PaymentConnector beans     │
                                  │  StripeConnector / Razorpay │
                                  └─────────────────────────────┘

Cross-cutting:
  • GlobalExceptionHandler — maps every exception to an RFC 7807 response
  • IdempotencyCleanupJob  — scheduled sweep of expired idempotency rows
```

Persistence is via Spring Data JPA: `PaymentRepository`, `PaymentAttemptRepository`, `IdempotencyRecordRepository`.

### Request lifecycle

A `POST /api/v1/payments` follows these steps:

1. **Validate** the body and the `Idempotency-Key` header. Invalid input → 400 before any side effect.
2. **Reserve the idempotency key.** Insert a record (`state=IN_PROGRESS`, expiry = now + TTL) in a short transaction.
    - If the key is already used: check expiry, body-hash and state, then either replay the original response, return 409, or evict the stale row and retry the insert.
3. **Create the payment** (`status=INITIATED`, server-assigned UUID) and persist it.
4. **Process the payment**:
    - Move to `PROCESSING`.
    - Look up the connector chain for the payment method.
    - Walk the chain: each PSP gets one or more retries on transient failures; non-retryable failures may or may not trigger failover (configurable). Every PSP attempt is persisted as a `PaymentAttempt` row.
    - On the first success → move to `SUCCEEDED`. If the chain is exhausted → move to `FAILED`.
5. **Mark the idempotency record `COMPLETED`** with the payment id, in a short transaction.
6. **Respond** `201 Created` with the payment.

If anything in step 4 throws unexpectedly, the idempotency record is deleted in a compensating transaction so the key is immediately reusable.

### Integration points

**Inbound** — Three endpoints under `/api/v1/payments`, documented in the [API reference](#api-reference).

**Outbound** — Defined by the `PaymentConnector` interface:

```java
public interface PaymentConnector {
    String id();                                   // unique slug ("stripe", "razorpay", ...)
    ConnectorResponse charge(ConnectorRequest r);  // throws Retryable/NonRetryableConnectorException
}
```

`ConnectorRequest(paymentId, amountMinor, currency)` is the minimal payload for a one-shot authorise+capture. `ConnectorResponse(connectorReferenceId)` is the opaque handle returned by the PSP.

Adding a new PSP:
1. Implement `PaymentConnector` as a `@Component` with a unique `id()`.
2. Reference that id in `orchestrator.routing.rules.<METHOD>` in `application.yaml`.

`ConnectorRegistry` discovers it automatically.

**Persistence** — Three tables (H2 today; trivially Postgres-compatible):

| Table | Purpose | Key columns |
|---|---|---|
| `payments` | One row per payment lifecycle | `id` (UUID PK), `merchant_reference`, `amount_minor`, `currency`, `payment_method`, `status` |
| `payment_attempts` | One row per PSP attempt | `id` (UUID PK), `payment_id`, `attempt_number`, `connector_id`, `status`, `error_message`, `started_at`, `ended_at` |
| `idempotency_records` | Once-only enforcement | `id` (Idempotency-Key, PK), `request_hash`, `state`, `payment_id`, `created_at`, `expires_at` |

Schema is JPA-managed (`ddl-auto: update`) for the POC; production would switch to Flyway or Liquibase.

### Concurrency model

- **One JVM thread per request** (Tomcat default). No app-level pooling.
- **Idempotency uniqueness** is enforced by the database's primary-key constraint — race-safe across threads *and* across orchestrator instances.
- **Retry sleeps** happen on the request thread; other threads continue serving traffic.
- **Cleanup job** runs single-threaded on its own schedule with its own transaction.

### Failure modes

| Failure | What the system does |
|---|---|
| TTL elapsed but cleanup hasn't run yet | Stale idempotency row is evicted on the spot; the request proceeds as new. |
| First PSP throws a retryable error | Retried with exponential backoff up to the configured limit. |
| First PSP exhausts retries | Move on to the next PSP in the chain. |
| First PSP throws a non-retryable error | Persist the attempt; failover only if the failover-on-non-retryable flag is on. |
| All PSPs fail | Payment ends `FAILED`; the API call still returns `201`, because the orchestration ran to completion — the *call* succeeded, the *payment* didn't. |
| Routing rule absent for the payment method | Reject with 422 before touching any PSP. |
| DB unavailable mid-flight | Spring rolls back the transaction; the idempotency record is removed in a compensating transaction; the caller sees 500 and can safely retry the same key. |

---

## Requirements

### Scope

A backend payment orchestration service that accepts payment requests over HTTP, routes them to one of several payment service providers (PSPs) based on the chosen payment method, retries transient failures, fails over between PSPs, prevents duplicate charges on retried client requests, and tracks the lifecycle of every payment.

### Functional requirements

| ID    | Requirement |
|-------|-------------|
| FR-01 | Create a payment via an HTTP API, given a merchant reference, amount, currency, and payment method. |
| FR-02 | Fetch a payment by its identifier. |
| FR-03 | List the PSP attempts made for a given payment. |
| FR-04 | Route each payment to a PSP based on the payment method (e.g. CARD → PSP-A, UPI → PSP-B), configurable. |
| FR-05 | Retry transient PSP failures and fail over to the next PSP when retries are exhausted. |
| FR-06 | Enforce idempotency via a client-supplied `Idempotency-Key` header — replays return the original response; conflicting reuse is rejected. |
| FR-07 | Track payment status through a strict lifecycle: *initiated → processing → succeeded \| failed*. |
| FR-08 | Validate every incoming request and return standard HTTP errors for invalid input. |

### Non-functional requirements

| ID     | Quality attribute | Requirement |
|--------|-------------------|-------------|
| NFR-01 | Scalability       | Horizontally scalable; no sticky sessions. |
| NFR-02 | Performance       | Predictable, bounded response time derivable from the retry policy. |
| NFR-03 | Reliability       | At-most-once payment per idempotency key, even under partial failures. |
| NFR-04 | Security          | Internal routing and PSP identifiers are not exposed on the merchant-facing response. |
| NFR-05 | Configurability   | Routing rules, retry policy, and idempotency TTL are changeable via configuration, not code. |
| NFR-06 | Observability     | Structured logs for errors and significant lifecycle events. |
| NFR-07 | Maintainability   | Adding a new PSP requires only a new connector + a routing-rule update, not changes to the orchestration core. |

---

## Test catalogue

**67 automated tests** — 27 unit, 40 integration · all green · ~30 s warm-JVM full run.

### How tests are classified

Every test gets two labels.

**Type**
- **Sanity** — the smoke set. If these go red, nothing else matters; the build is broken.
- **Regression** — everything else. Each one locks down a fixed bug or a non-obvious invariant.

(Sanity tests are also regression tests; "Sanity" just calls out the smoke subset.)

**Scenario**
- **Positive** — asserts the system does the right thing on good input.
- **Negative** — asserts the system rejects bad input or recovers from a failure.

Integration tests are the ones whose class name ends in `IT` — they boot the Spring context or hit the database. Everything else is a unit test.

### Unit tests (no Spring context)

#### `PaymentStatusTest`  (3)
| Test | Type | Scenario |
|---|---|---|
| `initiated_canOnlyTransitionTo_processing` | Sanity | Positive |
| `processing_canTransitionTo_succeededOrFailed` | Sanity | Positive |
| `terminalStates_cannotTransitionAnywhere` | Regression | Negative |

#### `PaymentTest`  (4)
| Test | Type | Scenario |
|---|---|---|
| `create_assignsIdAndInitiatedStatus` | Sanity | Positive |
| `transitionTo_advancesStatusForValidMove` | Sanity | Positive |
| `transitionTo_rejectsIllegalMove_andLeavesStatusUnchanged` | Regression | Negative |
| `transitionTo_rejectsMoveFromTerminalState` | Regression | Negative |

#### `RoutingTableTest`  (7)
| Test | Type | Scenario |
|---|---|---|
| `route_returnsChainForKnownMethod` | Sanity | Positive |
| `route_throwsForMethodWithoutRule` | Regression | Negative |
| `emptyRoutes_areRejectedAtConstruction` | Regression | Negative |
| `routingDecision_rejectsEmptyChain` | Regression | Negative |
| `routingDecision_rejectsBlankConnectorId` | Regression | Negative |
| `routingDecision_rejectsDuplicates` | Regression | Negative |
| `routingDecision_chainIsImmutable` | Regression | Negative |

#### `ConnectorRegistryTest`  (4)
| Test | Type | Scenario |
|---|---|---|
| `byId_returnsRegisteredConnector` | Sanity | Positive |
| `byId_throwsForUnknownSlug` | Regression | Negative |
| `construction_rejectsTwoConnectorsWithSameId` | Regression | Negative |
| `construction_acceptsEmptyConnectorList` | Regression | Negative |

#### `CreatePaymentRequestValidationTest`  (9)
| Test | Type | Scenario |
|---|---|---|
| `validRequest_hasNoViolations` | Sanity | Positive |
| `blankMerchantReference_violatesNotBlank` | Regression | Negative |
| `merchantReferenceTooLong_violatesSize` | Regression | Negative |
| `missingAmount_violatesNotNull` | Regression | Negative |
| `zeroAmount_violatesPositive` | Regression | Negative |
| `negativeAmount_violatesPositive` | Regression | Negative |
| `lowercaseCurrency_violatesPattern` | Regression | Negative |
| `twoLetterCurrency_violatesPattern` | Regression | Negative |
| `missingPaymentMethod_violatesNotNull` | Regression | Negative |

### Integration tests

#### `OrchestratorApplicationTests`  (1)
| Test | Type | Scenario |
|---|---|---|
| `contextLoads` | Sanity | Positive |

#### `ConnectorInvokerRetryTest`  (4)
| Test | Type | Scenario |
|---|---|---|
| `retryable_isRetried_uptoMaxAttempts` | Sanity | Positive |
| `retryable_thenSuccess_returnsResponseAndStopsRetrying` | Regression | Positive |
| `nonRetryable_isThrownImmediately_withoutRetry` | Regression | Negative |
| `unrelatedException_isNotRetried` | Regression | Negative |

#### `PaymentControllerCreateAndReadIT`  (9)
| Test | Type | Scenario |
|---|---|---|
| `postCardPayment_returns201_succeededViaFirstConnector_andRecordsOneAttempt` | Sanity | Positive |
| `postUpiPayment_routesToRazorpayFirst` | Sanity | Positive |
| `post_missingIdempotencyKey_returns400` | Sanity | Negative |
| `post_unsupportedPaymentMethod_returns422` | Regression | Negative |
| `get_existing_returnsSlimResponse` | Sanity | Positive |
| `get_unknownId_returns404Problem` | Regression | Negative |
| `get_malformedId_returns400Problem` | Regression | Negative |
| `getAttempts_returnsOrderedList` | Sanity | Positive |
| `getAttempts_forUnknownId_returns404` | Regression | Negative |

#### `PaymentControllerFailoverIT`  (4)
| Test | Type | Scenario |
|---|---|---|
| `primaryTransientThenSucceeds_oneAttemptRow_onStripe` | Sanity | Positive |
| `primaryExhaustsRetries_failsOverToRazorpay` | Sanity | Positive |
| `bothConnectorsFail_paymentEndsFailed_with2AttemptRows` | Regression | Negative |
| `primaryNonRetryable_failoverOnByDefault_succeedsOnRazorpay` | Regression | Positive |

#### `PaymentControllerNoFailoverIT`  (1)
| Test | Type | Scenario |
|---|---|---|
| `primaryNonRetryable_failoverOff_paymentFailed_andRazorpayUntouched` | Regression | Negative |

#### `PaymentControllerIdempotencyIT`  (3)
| Test | Type | Scenario |
|---|---|---|
| `replaySameKeySameBody_returnsSamePaymentId_andNoNewRow` | Sanity | Positive |
| `sameKeyDifferentBody_returns409Problem` | Sanity | Negative |
| `expiredKey_isReusableAsNew_afterCleanup` | Regression | Positive |

#### `PaymentControllerValidationIT`  (9)
| Test | Type | Scenario |
|---|---|---|
| `malformedJson_returns400Problem` | Regression | Negative |
| `wrongContentType_returns415Problem` | Regression | Negative |
| `unknownPaymentMethodEnum_returns400` | Regression | Negative |
| `negativeAmount_returns400_withErrorsMap` | Regression | Negative |
| `zeroAmount_returns400_withErrorsMap` | Regression | Negative |
| `lowercaseCurrency_returns400_withErrorsMap` | Regression | Negative |
| `blankMerchantReference_returns400_withErrorsMap` | Regression | Negative |
| `shortIdempotencyKey_returns400` | Regression | Negative |
| `tooLongIdempotencyKey_returns400` | Regression | Negative |

#### `IdempotencyServiceIT`  (9)
| Test | Type | Scenario |
|---|---|---|
| `newKey_persistsRecord_andReturnsWorkResult` | Sanity | Positive |
| `sameKey_sameBody_replaysPersistedPayment` | Sanity | Positive |
| `sameKey_differentBody_throwsConflict_withDiffersMessage` | Regression | Negative |
| `sameKey_inProgress_throwsConflict_withInProgressMessage` | Regression | Negative |
| `workThrows_recordIsDeleted_soKeyIsReusable` | Regression | Negative |
| `expiredCompletedRecord_isEvictedOnReplay_andTreatedAsFreshRequest` | Regression | Positive |
| `expiredRecord_withDifferentBody_isAlsoEvicted_andNewRequestSucceeds` | Regression | Positive |
| `expiredInProgressRecord_isEvicted_doesNotThrowInProgressConflict` | Regression | Positive |
| `cleanupJob_removesExpiredRecords_andLeavesLiveOnesIntact` | Regression | Positive |

---

## Repository layout

```
orchestrator/
├── pom.xml
├── README.md                   # this document
├── mvnw, mvnw.cmd              # Maven wrapper (Maven not required to build)
├── .github/workflows/build.yml # CI: runs the full test suite on every push
├── src/
│   ├── main/java/com/bitforge/payments/orchestrator/
│   │   ├── connector/          # ports + Stripe/Razorpay stubs
│   │   ├── controller/         # /api/v1/payments
│   │   ├── dto/                # CreatePaymentRequest, PaymentResponse, PaymentAttemptResponse
│   │   ├── entity/             # Payment, PaymentAttempt, IdempotencyRecord, enums
│   │   ├── exception/          # domain exceptions + GlobalExceptionHandler
│   │   ├── repository/         # Spring Data JPA interfaces
│   │   ├── routing/            # RoutingTable + ConfigurationProperties
│   │   └── service/            # PaymentService, PaymentProcessor, IdempotencyService, cleanup job
│   ├── main/resources/application.yaml
│   ├── test/java/...           # 67 tests (unit + IT)
│   └── test/resources/application-test.yaml
└── target/                     # build output, surefire reports (gitignored)
```
