# inventory-service — Inventory bounded context

Part of the OMS. System-wide map, rules and other services: **`oms-workspace`**
(https://github.com/domt97/oms-workspace — `CLAUDE.md`, `architecture/`). Read it before any change
that publishes/consumes events or depends on another service.

## Owns

Stock truth per (store, SKU): quantity, reservations for orders, and an audit trail of every stock change.
Postgres DB `inventory`, schema `inventory`; no DynamoDB (merchant id arrives with `ProductCreated`). Port **8058**.
Consumes `ProductCreated` and `ProductUpdated` (Kafka, through `SyncProductInventoryService`), the stock commands of
ADR 0006 `ReserveStock`, `ConfirmStockReservation`, `ReleaseStockReservation` (Kafka, sent by order-service — not built
yet; consumers `ReserveStockConsumer`, `ConfirmStockReservationConsumer`, `ReleaseStockReservationConsumer`) and a
reservation-expire tick (EventBridge → SQS, every minute).
Failed Kafka records go to `<topic>.DLT` (`KafkaErrorHandlingConfig`).
Publishes the stock replies of ADR 0006 (`StockReserved`, `StockReservationRejected`, `StockReservationConfirmed`,
`StockReservationConfirmationFailed`, `StockReservationReleased`, `StockReservationExpired`) through `outbox_events`;
published by the connector `inventory-outbox-cdc` (`development` repo, `docs/connectors.md`; insert-only publication,
heartbeat table `debezium_heartbeat`).

## Layout (`com.dotran.oms.inventory`)

| Layer | Package | Contains |
|-------|---------|----------|
| Domain (no Spring) | `domain/model` | `Inventory` (aggregate), `StockReservation` (aggregate), `StockReservationOrder` (order header, + `ReservationRejection`), `StockMovement` (audit entity) |
| | `domain/enums`, `exception` | `ReservationStatus`, `StockMovementType`, `InventoryStatus`, `ProductStatus`, `ProductSkuStatus`, `OrderReservationStatus`, `LineRejectionReason`, `OrderRejectionReason`; `InsufficientStockException`, `InvalidQuantityException`, `InvalidReservation*`, `InvalidCatalogEventException`, `CatalogOwnershipMismatchException`, `InventoryNotOrderableException`, `InvalidMovementTypeException`, `InvalidStockCommandException`, `StockCommandOwnershipMismatchException` |
| Application | `application/usecase/{inventory,reservation,movement}` | Inbound ports `XxxUseCase` |
| | `application/service` | `@UseCase` implementations (flat package), `@Transactional` here |
| | `application/command`, `dto`, `repository`, `jobs` | `XxxCmd`, `XxxDto`, outbound ports |
| | `application/event` | `StockReply` (reply payloads, plain values only), `OutboxMessage`, `StockReplyFactory` |
| | `application/config` | `@ConfigurationProperties` used by use cases (`ReservationExpiryProperties`, `ReservationProperties`) |
| Infrastructure | `infrastructure/rest/{api,internal,dto,mapper,exception}` | Public controllers under `/v1/inventory/inventories`; internal controller under `/internal/v1/inventory/inventories` (`@Hidden`); `InventoryExceptionHandler` |
| | `infrastructure/persistence/{entity,jpa}` | Adapters, entities, `SpringDataXxxRepository` (native SQL for stock updates) |
| | `infrastructure/message` (+ `dto`) | Kafka/SQS consumers = anti-corruption layer; external event and command DTOs live here, never in `domain`; `StockCommandMapping` (shared mapping of the stock commands) |

## Stock invariants — read before touching stock

- `available = quantity − reservedQuantity`; neither may go negative.
- Stock is changed with **atomic conditional SQL** in `SpringDataInventoryRepository`, e.g.
  `reserve`: `UPDATE ... SET reserved_quantity = reserved_quantity + :q WHERE id = :id AND merchant_id = :m AND store_id = :s
  AND status = 'ACTIVE' AND (quantity - reserved_quantity) >= :q`.
  The service checks the returned row count (`0` → a rejection, or `InsufficientStockException` for a manual adjust). The domain method
  (`Inventory.reserve()` etc.) validates first, but **the SQL guard is what prevents oversell** under concurrency.
  Keep this pattern for any new stock operation; do not load-modify-save quantities.
- `StockReservation` transitions: `RESERVED` → `CONFIRMED` | `RELEASED` | `EXPIRED`, and `CONFIRMED` → `RETURNED` (release with
  restock, BR-INV-03). `CANCELLED` is never used for a reservation. The hold TTL comes from `app.reservation.ttl` (15 min,
  BR-INV-02). The domain method states the rule; the **guarded UPDATE enforces it**: `markConfirmed` / `markExpired` /
  `markReleased` / `markReturned` run `UPDATE ... WHERE id = :id AND status = :from` per id (ascending) and return the ids
  that won. Change stock only for the winners. Never save a reservation status via load-modify-save.
- **Order header first** (`stock_reservation_orders`, ADR 0006, flow `order-stock-reservation.md`). Reserve, confirm and
  release each: `applyLocal` (`SET LOCAL lock_timeout` / `statement_timeout`), `insertIfAbsent` the header
  (`ON CONFLICT DO NOTHING`), then `lockForUpdate` it scoped by (order, merchant, store); empty → another merchant/store
  owns the order → `StockCommandOwnershipMismatchException` (contract error). Everything else is decided from the header
  and line statuses under that lock: a duplicate changes no stock and answers the current state (BR-INV-07); the
  duplicate check runs before the stale check; a confirm or release without header leaves a closed tombstone; the first
  release fixes `restock`. Business outcomes are **results** (`ReserveStockResult`, ...), contract errors throw
  (`InvalidStockCommandException`). The three transactions are bounded by `app.reservation.transaction-timeout-seconds`.
- **Lock order**: order header → reservation status updates by id ascending (release: the RESERVED ids, then the
  CONFIRMED ids — two disjoint sets) → inventory rows in Java `UUID.compareTo` order
  (`ReserveStockService` sorts lines; confirm and release use a `TreeMap` with the same comparator, one pass). The expiry
  takes only reservation → inventory and never the header. Java and Postgres order UUIDs differently, so any new
  multi-inventory locker must use the same Java comparator (not SQL `ORDER BY id ... FOR UPDATE`). Keep it, or deadlocks return.
- **Reserve is all or nothing** (≤ `max-lines` 100, BR-INV-11): missing row / other product → `UNKNOWN_SKU`, INACTIVE →
  `NOT_ORDERABLE`, short → `INSUFFICIENT_STOCK`; a guarded update lost at line *n* releases lines 1..n-1 in the same
  transaction and reads the reasons with `getStockStates`. The rejection is stored on the header (JSONB) for duplicates.
- **Movements for every hold change** (BR-INV-05): `RESERVE`, `SALE` (confirm), `RESERVE_RELEASE`, `RESERVE_EXPIRE`,
  `RETURN` (restock) are written by `StockMovementRepository.insertForReservations` (native `INSERT … SELECT` from the
  reservations in the required status, `reference_type = ORDER_ITEM`, `reference_id` = order item, `reservation_id` set,
  `ON CONFLICT (reservation_id, type) DO NOTHING`). Commands check the count and throw on a shortfall; the expiry only
  logs it. The hold types are refused by a manual adjust (`InvalidMovementTypeException`, 400); a manual `SALE` is allowed.
- **Replies go to the outbox in the same transaction, from one write point.** Reserve / confirm / release:
  `result = decide(cmd); outbox.saveAll(replyFactory.of(cmd, result)); return result` — every return publishes
  (duplicates and tombstones too, a new eventId each time, `causationId` = the command's eventId), every throw
  publishes nothing. The expiry publishes one `StockReservationExpired` per (order, merchant, store) after its
  movements; the expiry's reply mapping never throws (a database failure in `saveAll` still rolls that inventory back
  and the next tick retries it). `OutboxEventPersistenceAdapter` inserts the rows with `id = eventId` and **deletes
  them in the same transaction**: Debezium reads the INSERTs from the WAL, the table stays empty (open question 19).
  Payloads are plain JSON (`OutboxPayloadSerializer`: ISO instants, nulls written); never put typed ids in them.
  The six reply schemas and the three command schemas are copied in `src/contractTest/resources/contracts/events` —
  keep them in sync.
- **The command consumers only map and call** (`ReserveStockConsumer`, `ConfirmStockReservationConsumer`,
  `ReleaseStockReservationConsumer`; groups `inventory-reserve-stock`, `inventory-confirm-stock-reservation`,
  `inventory-release-stock-reservation`). They are not `@Transactional`, read nothing, keep no state and never catch a
  use-case exception: idempotency is the order header's job (keyed on `orderId`, not on the command's `eventId`), a
  business outcome is a normal return (offset committed, reply in the outbox), every exception goes to the error
  handler. Rules of the mapping (`StockCommandMapping`):
  - `eventType` must equal the topic's message type, else `InvalidStockCommandException`: the three payloads overlap, so
    a release put on the confirm topic would otherwise be read as a confirm and deduct stock;
  - a missing id becomes a **null wrapper**, never `OrderId.of(null)` (the typed ids accept null and
    `StockCommandValidation` checks the wrapper only);
  - `quantity` is read as a `BigDecimal` and must be a whole number (Jackson would truncate `2.7` to 2 for a `long`;
    the decoder reads the JSON as a tree first, so a float passes through a double: `2.7` is refused, a fraction below
    double precision such as `2.00000000000000001` is read as 2);
    `restock` is read as an `Object` and must be a JSON boolean (Jackson would turn `1` or `"true"` into `true`);
  - the record key is not compared with `orderId` (correctness does not depend on partition order; the header lock does it).
  Jackson's other lenient readings are accepted and pinned by the `*CommandJsonTest` classes: an `occurredAt` number is
  epoch **seconds**, a 24-character base64 string is read as a UUID, `quantity` `"5"` is 5.
- **Command listener sizing**: `max.poll.records=5` on the three `@KafkaListener`s and
  `app.kafka.stock-commands.concurrency` (default 1). Worst case per poll: 5 × (20 s Hikari connection wait + 10 s
  transaction timeout + 1 s) + one 60 s backoff = 215 s < `max.poll.interval.ms` (300 s, Kafka default), so a slow
  database does not cause a rebalance (an argument from the settings; a DLT publish to an unreachable broker is outside
  it: that record is retried, not lost). Raising the concurrency needs a larger pool: keep `3 × concurrency + 3` below
  the Hikari pool (10), which also serves REST. With concurrency 1 a command that is being retried delays the other
  partitions of its topic on that instance. Re-check the sum when a timeout, the pool wait or the backoff changes.
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

## Catalog sync invariants (`SyncProductInventoryService`)

- **Version gate first.** Every `ProductCreated` (version 0) / `ProductUpdated` (version ≥ 1) transaction starts with the
  upsert on `product_catalog_versions` (`advance`). It applies the event only if the version is newer and merchant/store
  match, and its row lock serialises all work on one product. 0 rows → stale/duplicate (skip) or ownership mismatch
  (`CatalogOwnershipMismatchException` → DLT). Never move it after other reads or writes. (A `ProductCreated` without
  SKUs returns in the consumer before the gate; harmless.)
- **Status is only written by the guarded native `updateStatus`** (`… AND status <> :status`), rows in Java `UUID` order.
  Never save a loaded `Inventory` for this: it would overwrite quantities. The sync never writes quantities.
- New rows go through `insertIfAbsent` (`ON CONFLICT (store_id, sku) DO NOTHING`), sorted by SKU code.
- **Rows are never deleted** (reservations and movements reference them without FKs). Removed SKUs become INACTIVE.
- Status = orderable (BR-INV-08): `InventoryStatus.orderable(productStatus, skuStatus, present)`.
- Error handling is the same for **every Kafka listener** (one `DefaultErrorHandler` bean, `KafkaErrorHandlingConfig`).
  Contract errors (`InvalidCatalogEventException`, `InvalidStockCommandException`, JSON errors,
  `DataIntegrityViolationException`) and ownership mismatches (`CatalogOwnershipMismatchException`,
  `StockCommandOwnershipMismatchException`) go to the DLT at once. Database connection failures are retried without
  limit (backoff up to 1 min), so an outage only delays events and commands. Other failures are retried 6 times (~35 s
  plus each attempt's own time) and then sent to the DLT — also the timeouts of a stock command (`lock_timeout` 55P03,
  `statement_timeout` 57014, the transaction deadline) and `InvalidReservationStateException` (a confirm or release
  that lost to the expiry: the retry answers from the new state; never make it not-retryable).
  **A cancelled statement (57014) is never a connection failure**: Spring JDBC translates SQLState class 57 to
  `DataAccessResourceFailureException` (seen on the outbox INSERT, which is plain JDBC, not Hibernate), so
  `isConnectionFailure` excludes 57014 by the cause chain (`StockCommandErrorClassificationIT`). When you add an
  exception to the not-retryable list or widen the connection-failure check, keep the timeout cases of
  `KafkaErrorHandlingConfigTest` and that IT green.
- Products whose rows span several merchants or stores fail the ownership check on every event (DLT): check with
  `SELECT store_product_id FROM inventories GROUP BY 1 HAVING count(DISTINCT merchant_id) > 1 OR count(DISTINCT store_id) > 1`.

## Logging (MDC)

- Every log line carries the MDC (`logging.pattern.correlation: "[%X] "`). REST requests get `requestId` (from `X-Request-Id` or
  generated, echoed in the response) and the request values listed in `app.logging.mdc.fields` (oms-core `MdcProperties`;
  defaults: `merchantId`, `storeId`, `productId`, `inventoryId`, `collectionId`, `storeAvailabilityId` as UUID path variables),
  set by oms-core (`RequestIdFilter`, `MdcRequestFieldsInterceptor`). Add a key in `application.yaml`, not in code.
- **Every Kafka listener** opens `KafkaMdc.open(record, mdcPropagation)` first and keeps its whole body inside the scope;
  after decoding it adds `eventId`, `merchantId`, `storeId` and `productId` (catalog events) or `orderId` (stock
  commands; the key is `StockCommandMapping.ORDER_ID`, oms-core `MdcKeys` has none) — always from the payload; the
  header carries correlation keys only. The command consumers call the use case **inside** the scope: the reply's
  `correlation_context` is captured from the MDC of the writing thread. `requestId` comes from the producer's
  `correlationContext` header (oms-core `MdcPropagation.restore`, validated; ADR 0004), so one id covers the whole flow
  from the browser's `X-Request-Id`; without a valid header it is `topic-partition-offset`. Either way it is the same on
  every retry; the DLT recoverer (`KafkaErrorHandlingConfig`) reopens `KafkaMdc` so the DLT line carries it too (not
  the payload ids: for a command the order id is on the consumer's own lines, and in `key=` if the producer keyed by it). The
  error handler's own retry lines have no MDC. Tests use a real `MdcPropagation` (`support/TestMdc`), not a mock.
- SQS listeners open an `MdcScope` with `requestId`/`sqsMessageId`; loops over independent units (e.g. expiry per inventory)
  open a nested scope per unit, with the `catch` **inside** the scope (a try-with-resources `catch` runs after `close()`).
- In application code only `MdcScope` writes the MDC (the oms-core filter/interceptor manage their own keys); the domain never does. A new executor or `@Async`
  needs a `TaskDecorator` that copies the MDC — there is none today.

## Adding a use case — checklist

1. **Domain**: rule in the aggregate method; throw a domain exception.
2. **Port + command**: `application/usecase/<area>/XxxUseCase`, `application/command/XxxCmd` (carry `MerchantId`, `StoreId`).
3. **Service**: `application/service/XxxService` with `@UseCase` + `@Transactional`. Stock changes through a guarded repository method (see invariants).
4. **Audit**: write a `StockMovement` with the right `StockMovementType`, `reference_type` and `reference_id` for every quantity change.
5. **Entry point**: REST controller or a consumer in `infrastructure/message`. Consumers map the external DTO to a command and must be **idempotent** (Kafka delivery is at-least-once).
6. **Errors**: map in `InventoryExceptionHandler` (extends `oms-core` `GlobalExceptionHandler`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (latest is `V5`); every table has `merchant_id`.
   Exception: `inventory.debezium_heartbeat` belongs to the connector setup (`development` repo SQL script) and has no
   `merchant_id`. It is not managed by Flyway today: do not add a migration for it while the `development` script creates
   it (moving it is open question 55 in oms-workspace). Events go through `outbox_events`; a new table that must reach
   Kafka needs a change to the connector setup in `development`.
8. **Tests** mirroring the package: domain, service, adapter, controller/consumer.

## Tests

- **Unit tests** (`src/test`): plain JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`), no Spring context, no DB.
  Run: `./gradlew test`.
- **Integration tests** (`src/integrationTest`, Gradle suite `integrationTest`, Docker needed): Testcontainers
  `postgres:16-alpine`, Flyway V1..Vn, the JPA slice with the persistence adapters and the stock command services
  (`support/PostgresIT`; no Kafka/SQS). They cover the native SQL, constraints, JSONB, `FOR UPDATE`, lock timeouts and
  races, transaction timeouts, and the outbox as Debezium would see it in the WAL (a `test_decoding` slot, `support/WalSlot`; the container runs with `wal_level=logical`) (`StockSqlIT`, `StockCommandsIT`, `StockConcurrencyIT`, `StockCommandTransactionIT`, `OutboxSqlIT`, `StockRepliesIT`, `OutboxFailureIT`), the command consumers on the real use cases (`StockCommandConsumersIT`: JSON record in, rows and the reply in the WAL out) and the real timeout exceptions against the Kafka error handler (`StockCommandErrorClassificationIT`). Not covered by any automated test (no broker): that the listener container applies the `@KafkaListener` settings and the error handler bean, the deserializers, DLT publishing and topic creation — check by hand with `kafka-console-producer` on the local stack after a change there. Run: `./gradlew integrationTest`; CI job `Integration tests`
  (not a required check yet). Any change to native SQL or locking needs an IT here.

## Git workflow

Branch **from `develop`** and open PRs **into `develop`** (`feature/...`, `fix/...`, `docs/...`).
`master` only receives `develop` through release PRs — never target `master` directly.

## Build & run

oms-core: locally `../oms-core` next to this repo is used as a composite build (`oms-workspace/scripts/sync-repos.sh`);
CI and releases use the pinned `coreVersion` (`gradle.properties`) from GitHub Packages (ADR 0005). To take an oms-core
change: merge it into oms-core `master` with its `version` bumped (published automatically), then bump `coreVersion` in a PR. Without `../oms-core` a local build needs
`gpr.user`/`gpr.key` (classic token, `read:packages`) in `~/.gradle/gradle.properties`. Never merge an `-rc` pin (`core-pin` check).
Infra from the `development` repo (Postgres, Kafka, LocalStack with SQS queue + EventBridge rule via Terraform).
`./gradlew bootRun --args='--spring.profiles.active=localdev'` → Swagger at `http://localhost:8058/swagger-ui.html`.

## Known gaps (do not assume these work)

- Kafka DLT topics (`ProductCreated.DLT`, `ProductUpdated.DLT`, `ReserveStock.DLT`, `ConfirmStockReservation.DLT`, `ReleaseStockReservation.DLT`) have no alerting and no replay tool; records there are only visible as an ERROR log (oms-workspace open question 17). **A stock command in a DLT gets no reply**: order-service's saga notices by its timeout (ADR 0006). SQS `reservation-expire` has no DLQ.
- The command topics have no producer yet (order-service is not built): the consumers are tested with unit and integration tests and with messages sent by hand. Locally Kafka has no ACLs; in production only order-service may write the command topics (ADR 0006).
- The internal `POST /internal/v1/inventory/inventories` (`CreateInventoryService`, kept for support, no caller today) bypasses the catalog version gate and creates **INACTIVE** rows (not orderable, BR-INV-08). Such a row becomes orderable only through a later `ProductUpdated` of that product, and only if the request's `productId` owns the SKU in the catalog (otherwise the sync skips it as claimed by another product and it stays INACTIVE); `ProductCreated` (also from a DLT replay) never changes an existing row, so support makes it sellable by changing the product in stores-service. A row in another merchant or store makes every later event of that product go to the DLT. It inserts in request order (not sorted by SKU), so it can deadlock with the sync; Postgres aborts one side and the sync is retried. Stock can still be adjusted on an INACTIVE row (BR-INV-10).
- Replies are published only while the connector's slot exists: rows written before the slot was created, or while it was lost, are never published (the outbox is always empty, nothing to re-read).
- Order headers (`stock_reservation_orders`) are never cleaned up (one row per order).
- REST endpoints take no `merchantId` (stores-service uses `/v1/store/merchants/{merchantId}/...`); `Inventory.validateMerchantAndStore` exists but merchant scoping of the API is undecided.
- `ProductCreatedEvent` and `ProductUpdatedEvent` DTOs duplicate the producer's classes in stores-service — keep both in sync with `oms-workspace/architecture/contracts/events/*.schema.json`.
