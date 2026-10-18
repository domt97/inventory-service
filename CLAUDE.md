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
| Infrastructure | `infrastructure/rest/{api,dto,mapper,exception}` | Controllers under `/v1/inventories`, `InventoryExceptionHandler` |
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
- Expiry runs per inventory in its own transaction (`ExpireByInventoryTransaction`, `REQUIRES_NEW`) so one failure does not roll back others.
- `inventories` is unique on (store_id, sku).

## Adding a use case — checklist

1. **Domain**: rule in the aggregate method; throw a domain exception.
2. **Port + command**: `application/usecase/<area>/XxxUseCase`, `application/command/XxxCmd` (carry `TenantId`, `StoreId`).
3. **Service**: `application/service/XxxService` with `@UseCase` + `@Transactional`. Stock changes through a guarded repository method (see invariants).
4. **Audit**: write a `StockMovement` with the right `StockMovementType`, `reference_type` and `reference_id` for every quantity change.
5. **Entry point**: REST controller or a consumer in `infrastructure/message`. Consumers map the external DTO to a command and must be **idempotent** (Kafka delivery is at-least-once).
6. **Errors**: map in `InventoryExceptionHandler` (extends `oms-core` `GlobalExceptionHandler`).
7. **Migration**: `src/main/resources/db/migration/V<next>__<what>.sql` (latest is `V1`); every table has `tenant_id`.
8. **Tests** mirroring the package: domain, service, adapter, controller/consumer.

## Tests

Plain JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`), no Spring context, no DB.
The conditional SQL is therefore **not** covered by tests — reason about it explicitly when you change it.
Run: `./gradlew test`.

## Build & run

Needs `../oms-core` next to this repo (composite build) — use `oms-workspace/scripts/sync-repos.sh`.
Infra from the `development` repo (Postgres, Kafka, LocalStack with SQS queue + EventBridge rule via Terraform).
`./gradlew bootRun --args='--spring.profiles.active=localdev'` → Swagger at `http://localhost:8058/swagger-ui.html`.

## Known gaps (do not assume these work)

- **`ProductCreatedConsumer` is not idempotent**: a redelivered event hits the unique (store_id, sku) constraint in `saveAllAndFlush` and the consumer throws.
- **`ReserveStockService` is not idempotent per order item**: replaying the same order reserves twice.
- **Reserve / confirm / expire do not write `StockMovement`** — only manual adjustment does, so the audit trail is incomplete.
- `ReserveStock` and `ConfirmStockReservation` have no entry point; `OrderCreatedEventConsumer` is commented out; `ReleaseStockReservationUseCase` has no implementation.
- REST endpoints take no `tenantId` (stores-service uses `/v1/tenants/{tenantId}/...`); `Inventory.validateTenantAndStore` exists but tenant scoping of the API is undecided.
- `ProductCreatedEvent` DTO duplicates the producer's class in stores-service — keep them in sync.
