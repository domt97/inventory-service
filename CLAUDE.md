# inventory-service — Inventory bounded context

Part of the OMS. System-wide map, rules and other services: **`oms-workspace`**
(https://github.com/domt97/oms-workspace — `CLAUDE.md`, `architecture/`). Read it before any change
that publishes/consumes events or depends on another service.

## Owns

Stock truth per (store, SKU): quantity, reservations for orders, and an audit trail of every stock change.
Postgres DB `inventory`, schema `inventory`; tenant info from DynamoDB. Port **8058**.
Consumes `ProductCreated` (Kafka) and a reservation-expire tick (EventBridge → SQS, every minute).
Publishes nothing yet (no outbox table).

## Layout (`com.dotran.oms.inventory`)

| Layer | Package | Contains |
|-------|---------|----------|
| Domain (no Spring) | `domain/model` | `Inventory` (aggregate), `StockReservation` (aggregate), `StockMovement` (audit entity) |
| | `domain/enums`, `exception` | `ReservationStatus`, `StockMovementType`, `InventoryStatus`; `InsufficientStockException`, `InvalidQuantityException`, `InvalidReservation*` |
| Application | `application/usecase/{inventory,reservation,movement}` | Inbound ports `XxxUseCase` |
| | `application/service` | `@UseCase` implementations (flat package), `@Transactional` here |
| | `application/command`, `dto`, `repository`, `jobs` | `XxxCmd`, `XxxDto`, outbound ports |
| | `application/config` | `@ConfigurationProperties` used by use cases (`ReservationExpiryProperties`) |
| Infrastructure | `infrastructure/rest/{api,internal,dto,mapper,exception}` | Public controllers under `/v1/inventory/inventories`; internal controller under `/internal/v1/inventory/inventories` (`@Hidden`); `InventoryExceptionHandler` |
| | `infrastructure/persistence/{entity,jpa}` | Adapters, entities, `SpringDataXxxRepository` (native SQL for stock updates) |
| | `infrastructure/message` (+ `dto`) | Kafka/SQS consumers = anti-corruption layer; external event DTOs live here, never in `domain` |

## Stock invariants — read before touching stock

- `available = quantity − reservedQuantity`; neither may go negative.
- Stock is changed with **atomic conditional SQL** in `SpringDataInventoryRepository`, e.g.
  `reserve`: `UPDATE ... SET reserved_quantity = reserved_quantity + :q WHERE id = :id AND (quantity - reserved_quantity) >= :q`.
  The service checks the returned row count (`0` → `InsufficientStockException`). The domain method
  (`Inventory.reserve()` etc.) validates first, but **the SQL guard is what prevents oversell** under concurrency.
  Keep this pattern for any new stock operation; do not load-modify-save quantities.
- `StockReservation` transitions only from `RESERVED` → `CONFIRMED` | `RELEASED` | `EXPIRED`. TTL is 30 min (`StockReservation.reserve`).
  The domain method states the rule; the **guarded UPDATE enforces it**: `StockReservationRepository.markConfirmed` / `markExpired`
  run `UPDATE ... WHERE id = :id AND status = 'RESERVED'` per id (ascending) and return the ids that won. Change
  `reserved_quantity` only for the winners. Never save a reservation status via load-modify-save; a new transition
  (e.g. release) gets its own guarded `markXxx` method.
- **Lock order**: reservation status updates by id ascending, then inventory rows in Java `UUID.compareTo` order
  (`ReserveStockService` sorts lines, `ConfirmStockReservationService` uses a `TreeMap` with the same comparator).
  Java and Postgres order UUIDs differently, so any new multi-inventory locker must use the same Java comparator
  (not SQL `ORDER BY id ... FOR UPDATE`). Keep it, or deadlocks return.
- Confirm is idempotent per order: all `CONFIRMED` → no-op; any `EXPIRED`/`RELEASED` → `InvalidReservationStateException` ("not RESERVED", final);
  a lost guarded update → `InvalidReservationStateException` ("modified concurrently", retry resolves it) and the whole transaction rolls back.
- Expiry runs per inventory in its own transaction (`ExpireByInventoryTransaction`, `REQUIRES_NEW`) so one failure does not roll back others;
  `ExpireStockReservationService` catches any `RuntimeException` per inventory, logs `Reservation expiry failed for inventory {}` and continues
  (the rolled-back reservations stay `RESERVED` and are retried on the next tick; the SQS message is still acknowledged).
- **The expiry tick is only a signal; each sweep is bounded.** `ExpireStockReservationService` pages `RESERVED` rows past `expires_at`
  in `(expires_at, id)` order with a keyset cursor (`ExpiryCursor`, `batch-size` 500) and stops starting pages after `time-budget` (40 s);
  the rest waits for the next tick. The cursor only moves forward, so a failing inventory cannot block the rest of a run. The query keeps
  `status = 'RESERVED'` as a literal so the partial index `idx_stock_reservation_expiry` (V2) is used.
  `SqsReservationExpireConsumer` takes one tick at a time (`maxConcurrentMessages = 1`, visibility 90 s > time budget) and drops a tick older
  than `max-tick-age` (2 min) **only if this instance swept within `max-tick-age`** (fails open: missing/bad/future timestamp → run).
  There is no cross-instance lock: overlapping sweeps on two instances are safe because of the guarded `markExpired`. Settings live in
  `app.reservation-expiry` (`ReservationExpiryProperties`, validated at startup).
- `inventories` is unique on (store_id, sku).

## Adding a use case — checklist

1. **Domain**: rule in the aggregate method; throw a domain exception.
2. **Port + command**: `application/usecase/<area>/XxxUseCase`, `application/command/XxxCmd` (carry `TenantId`, `StoreId`).
3. **Service**: `application/service/XxxService` with `@UseCase` + `@Transactional`. Stock changes through a guarded repository method (see invariants).
4. **Audit**: write a `StockMovement` with the right `StockMovementType`, `reference_type` and `reference_id` for every quantity change.
5. **Entry point**: REST controller or a consumer in `infrastructure/message`. Consumers map the external DTO to a command and must be **idempotent** (Kafka delivery is at-least-once).
6. **Errors**: map in `InventoryExceptionHandler` (extends `oms-core` `GlobalExceptionHandler`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (latest is `V2`); every table has `tenant_id`.
8. **Tests** mirroring the package: domain, service, adapter, controller/consumer.

**Pitfalls**
- There is **no `lombok.config`**, so Lombok does not copy `@Value` to the constructor that `@RequiredArgsConstructor`
  generates. `@Value` on a `final` field then makes Spring look for a bean of the field's type (e.g. `Duration`) and
  startup fails, while unit tests that call `new` still pass. Put settings in a `@ConfigurationProperties` class
  (see `ReservationExpiryProperties`) or write the constructor and put `@Value` on its parameter.
- Java 17: `Duration.isPositive()` does not exist (Java 18+); use `!d.isNegative() && !d.isZero()`.

## Tests

Plain JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`), no Spring context, no DB.
The conditional SQL is therefore **not** covered by tests — reason about it explicitly when you change it.
Run: `./gradlew test`.

## Git workflow

Branch **from `develop`** and open PRs **into `develop`** (`feature/...`, `fix/...`, `docs/...`).
`master` only receives `develop` through release PRs — never target `master` directly.

## Build & run

Needs `../oms-core` next to this repo (composite build) — use `oms-workspace/scripts/sync-repos.sh`.
Infra from the `development` repo (Postgres, Kafka, LocalStack with SQS queue + EventBridge rule via Terraform).
`./gradlew bootRun --args='--spring.profiles.active=localdev'` → Swagger at `http://localhost:8058/swagger-ui.html`.

## Known gaps (do not assume these work)

- **`ReserveStockService` is not idempotent per order item**: replaying the same order reserves twice.
- **Reserve / confirm / expire do not write `StockMovement`** — only manual adjustment does, so the audit trail is incomplete.
- **Kafka consumers have no dead-letter topic**: spring-kafka default = 10 attempts, no backoff, then the record is skipped. SQS `reservation-expire` has no DLQ.
- `ReserveStock` and `ConfirmStockReservation` have no entry point; `OrderCreatedEventConsumer` is commented out; `ReleaseStockReservationUseCase` has no implementation.
- REST endpoints take no `tenantId` (stores-service uses `/v1/store/tenants/{tenantId}/...`); `Inventory.validateTenantAndStore` exists but tenant scoping of the API is undecided.
- `ProductCreatedEvent` DTO duplicates the producer's class in stores-service — keep them in sync.
