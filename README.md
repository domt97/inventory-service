# Inventory Service

A production-grade inventory microservice for an Order Management System (OMS), built with **Java 17** and **Spring Boot 3.2.3**, applying **Domain-Driven Design (DDD)** and **Hexagonal Architecture**.

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)

## Table of Contents

- [Overview](#overview)
- [Order Lifecycle & Inventory Flow](#order-lifecycle--inventory-flow)
- [Architecture](#architecture)
- [Domain Model](#domain-model)
- [Event-Driven Integration](#event-driven-integration)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Database Schema](#database-schema)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Configuration](#configuration)

---

## Overview

The Inventory Service is a downstream microservice in the OMS ecosystem responsible for tracking stock levels, managing stock reservations, and recording all stock movements.

**Bounded Context:** Inventory  
**Role in OMS:** Downstream consumer of store and order events; upstream source of stock availability truth.

**Core Responsibilities:**
- Track available and reserved stock per SKU per store
- Reserve stock when an order is created
- Confirm stock when an order is paid/confirmed
- Release reserved stock when an order times out or is cancelled
- Provide a manual stock adjustment API with full audit trail
- Record all stock movements for audit and reporting

---

## Order Lifecycle & Inventory Flow

```
OrderCreated
    │
    ▼
ReserveStock          (stock held, unavailable to other orders)
    │
    ├──── Order expires / cancelled
    │         │
    │         ▼
    │     ExpireReservation   (stock released back to available)
    │         EventBridge → SQS → SqsReservationExpireConsumer
    │
    ▼
OrderConfirmed (payment success)
    │
    ▼
ConfirmReservation    (stock permanently consumed)
    ▼
Shipment              (logistics concern — does not affect inventory)
```

---

## Architecture

### Hexagonal Architecture (Ports & Adapters)

```
┌─────────────────────────────────────────────────────────────────┐
│                       Infrastructure Layer                      │
│                                                                 │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐   │
│  │  REST Controllers│  │ Persistence      │  │ Message      │   │
│  │  @WebAdapter     │  │ Adapters         │  │ Consumers    │   │
│  │                  │  │ @PersistenceAdptr│  │ (Kafka, SQS) │   │
│  └────────┬─────────┘  └────────┬─────────┘  └──────┬───────┘   │
└───────────┼─────────────────────┼────────────────────┼──────────┘
            │    (inbound ports)  │   (outbound ports) │
┌───────────┼─────────────────────┼────────────────────┼──────────┐
│           │   Application Layer │                    │          │
│           │                     │                    │          │
│  ┌────────▼──────────────────────────────────────────▼──────┐   │
│  │  Use Cases (@UseCase)                                     │  │
│  │  ReserveStockService  │  ConfirmStockReservationService   │  │
│  │  AdjustStockService   │  ExpireStockReservationService    │  │
│  │  CreateInventoryService  │  LoadInventoryService          │  │
│  └──────────────────────────────┬────────────────────────────┘  │
│                                 │                               │
│  ┌──────────────────────────────▼────────────────────────────┐  │
│  │  Port Interfaces (Repository contracts)                   │  │
│  │  InventoryRepository  │  StockReservationRepository       │  │
│  │  StockMovementRepository                                  │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│                         Domain Layer                            │
│                                                                 │
│  Aggregates: Inventory, StockReservation, StockMovement         │
│  Enums: InventoryStatus, ReservationStatus, StockMovementType   │
│  Exceptions: InsufficientStockException, InvalidReservation...  │
└─────────────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Annotation | Responsibility |
|-------|-----------|----------------|
| Domain | — | Pure business logic, invariants, no framework deps |
| Application | `@UseCase` | Orchestrates domain + ports, transaction boundary |
| Infrastructure/REST | `@WebAdapter` | HTTP in/out, DTO mapping |
| Infrastructure/DB | `@PersistenceAdapter` | JPA + repository implementations |
| Infrastructure/Msg | `@Component` | Kafka / SQS consumers (ACL layer) |

---

## Domain Model

### Aggregates

**`Inventory`** — Aggregate Root
- Tracks `quantity` (total stock) and `reservedQuantity` (held for active orders)
- `availableQuantity()` is always computed: `quantity - reservedQuantity`
- Business methods: `reserve()`, `release()`, `confirmReservation()`, `adjust()`, `receive()`
- Business methods enforce invariants before persistence

**`StockReservation`** — Aggregate Root
- Represents a stock hold for one order line item
- Lifecycle: `RESERVED` → `CONFIRMED` | `RELEASED` | `EXPIRED` (`CANCELLED` exists in the enum, not used yet); every transition requires current status `RESERVED`
- Default TTL: 30 minutes from creation
- Linked to `Inventory` by `inventoryId`
- Expires via `expires_at` timestamp, triggered by AWS EventBridge → SQS

**`StockMovement`** — Entity (Audit Record)
- Immutable record of every stock change
- Polymorphic reference: `referenceType` + `referenceId` (e.g., `ORDER`, `ADJUSTMENT`)
- Source of truth for stock history and reconciliation

### Value Objects

All identifiers are strongly typed via the `oms-core` shared module:  
`InventoryId`, `StoreId`, `MerchantId`, `ProductId`, `SKU`, `OrderId`, `ReferenceId`

---

## Event-Driven Integration

### Consumed Events

| Event | Source | Transport | Handler | Action |
|-------|--------|-----------|---------|--------|
| `ProductCreated` | store-service | Kafka | `ProductCreatedConsumer` | Create inventory record for new SKU |
| `OrderCreated` | order-service | Kafka | `OrderCreatedEventConsumer` | *(in progress)* Reserve stock |
| Reservation Expire Trigger | AWS EventBridge | SQS | `SqsReservationExpireConsumer` | Expire overdue reservations |

### Anti-Corruption Layer (ACL)

External integration events (e.g., `ProductCreatedEvent` published by store-service) are **not domain events**. They live in `infrastructure/message/dto/` and are mapped to internal commands before crossing the domain boundary. The domain has no awareness of external event schemas.

### Reservation Expiry Flow

```
AWS EventBridge (scheduled rule)
    └─► SQS Queue (reservation-expire)
            └─► SqsReservationExpireConsumer
                    └─► ExpireStockReservationService
                            └─► ExpireByInventoryTransaction (REQUIRES_NEW per inventory)
```

Each inventory's expiry runs in an isolated `REQUIRES_NEW` transaction — a failure on one inventory does not roll back others.

---

## Technology Stack

| Category | Technology |
|----------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2.3 |
| ORM | Spring Data JPA / Hibernate 6.4 |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Messaging | Apache Kafka, AWS SQS (Spring Cloud AWS) |
| Mapping | MapStruct 1.6.3 |
| Boilerplate | Lombok |
| API Docs | SpringDoc OpenAPI 3.0 (Swagger UI) |
| Build | Gradle |
| Local AWS | LocalStack |

---

## Project Structure

```
src/main/java/com/dotran/oms/inventory/
│
├── domain/                          # Pure business logic — no framework deps
│   ├── model/
│   │   ├── Inventory.java           # Aggregate Root
│   │   ├── StockReservation.java    # Aggregate Root
│   │   └── StockMovement.java       # Audit entity
│   ├── enums/
│   │   ├── InventoryStatus.java
│   │   ├── ReservationStatus.java
│   │   └── StockMovementType.java
│   └── exception/
│       ├── InsufficientStockException.java
│       ├── InvalidReservationException.java
│       └── InvalidQuantityException.java
│
├── application/                     # Use cases & port definitions
│   ├── usecase/                     # Inbound port interfaces
│   │   ├── inventory/
│   │   └── reservation/
│   ├── repository/                  # Outbound port interfaces
│   │   ├── InventoryRepository.java
│   │   ├── StockReservationRepository.java
│   │   └── StockMovementRepository.java
│   ├── service/                     # Use case implementations (@UseCase)
│   │   ├── ReserveStockService.java
│   │   ├── ConfirmStockReservationService.java
│   │   ├── ExpireStockReservationService.java
│   │   ├── ExpireByInventoryTransaction.java  # REQUIRES_NEW isolation
│   │   ├── AdjustStockService.java
│   │   └── CreateInventoryService.java
│   ├── command/                     # Input DTOs for use cases
│   └── dto/                         # Output DTOs from use cases
│
└── infrastructure/                  # Technical implementations
    ├── rest/                        # REST API (@WebAdapter)
    │   ├── api/
    │   │   ├── InventoryController.java
    │   │   └── StockMovementController.java
    │   ├── internal/
    │   │   └── InventoryInternalController.java  # hidden from Swagger, internal only
    │   ├── dto/request|response/
    │   └── mapper/
    ├── persistence/                 # JPA (@PersistenceAdapter)
    │   ├── InventoryPersistenceAdapter.java
    │   ├── entity/
    │   ├── jpa/
    │   │   └── SpringDataInventoryRepository.java
    │   └── mapper/
    └── message/                     # Event consumers (ACL)
        ├── ProductCreatedConsumer.java
        ├── OrderCreatedEventConsumer.java
        ├── SqsReservationExpireConsumer.java
        └── dto/                     # External event DTOs (not domain events)
```

---

## Database Schema

### Tables

**`inventories`**
```
id                UUID PK
merchant_id       UUID NOT NULL
store_id          UUID NOT NULL
store_product_id  UUID NOT NULL
sku               VARCHAR(50) NOT NULL
quantity          BIGINT NOT NULL DEFAULT 0     -- total physical stock
reserved_quantity BIGINT NOT NULL DEFAULT 0     -- held for active orders
status            VARCHAR(30) NOT NULL
created_at / updated_at
UNIQUE (store_id, sku)
```

**`stock_reservations`**
```
id              BIGINT PK (sequence, cache=20 for performance)
merchant_id / store_id
inventory_id    UUID FK → inventories
order_id / order_item_id
quantity        BIGINT NOT NULL
status          VARCHAR(30)   -- RESERVED | CONFIRMED | RELEASED | EXPIRED | CANCELLED
expires_at      TIMESTAMPTZ
```

**`stock_movements`** (append-only audit log)
```
id              BIGINT PK (sequence, cache=20)
merchant_id / store_id / inventory_id
type            VARCHAR(30)   -- StockMovementType: RECEIVE | SALE | RETURN | ADJUSTMENT_IN | ADJUSTMENT_OUT | DAMAGE | TRANSFER_IN | TRANSFER_OUT
quantity        BIGINT
reference_type  VARCHAR(50)   -- e.g. ORDER, ADJUSTMENT
reference_id    UUID          -- polymorphic FK
reason          VARCHAR(255)
created_at
```

### Key Indexes

- `inventories`: `(store_id)`, `(sku)`, `(store_id, store_product_id)`
- `stock_reservations`: `(order_id)`, `(inventory_id)`, `(status)`
- `stock_movements`: `(inventory_id)`, `(reference_type, reference_id)`, `(created_at)`

---

## Getting Started

> **No DynamoDB any more (2026-10).** inventory-service no longer looks merchants up: the merchant id comes with
> `ProductCreated` and is stored on every inventory row; merchants themselves live in stores-service (Postgres).
> Nothing to migrate here. If you recreate the `store` database for the stores-service change, recreate `inventory`
> too so no inventory rows point to stores that no longer exist (see the stores-service README).

### Prerequisites

- Java 17+
- PostgreSQL 12+
- LocalStack (for local AWS SQS/S3)
- Kafka

### Database Setup

```sql
CREATE DATABASE inventory;
CREATE SCHEMA inventory;
```

Flyway runs migrations automatically on startup from `src/main/resources/db/migration/`.

### LocalStack Setup

```bash
# Start LocalStack
cd ../development/docker && docker compose up -d localstack   # LocalStack runs from the development repo

# Create SQS queue
awslocal sqs create-queue --queue-name localdev-reservation-expire-queue
```

### Run

```bash
./gradlew bootRun
```

Application starts on **http://localhost:8058**

### Swagger UI

```
http://localhost:8058/swagger-ui.html
```

---

## API Reference

### Inventory

| Method | Path | Visibility | Description |
|--------|------|------------|-------------|
| `POST` | `/internal/v1/inventory/inventories` | Internal only | Create inventory records for a product's SKUs (triggered by `ProductCreated` event; hidden from Swagger, blocked at gateway) |
| `GET` | `/v1/inventory/inventories/{inventoryId}` | Public | Get inventory by ID |
| `GET` | `/v1/inventory/inventories/products/{productId}` | Public | Get all inventories for a product |
| `POST` | `/v1/inventory/inventories/{inventoryId}` | Public | Adjust stock (`movementType`: any `StockMovementType`) |

### Stock Movements

| Method | Path | Visibility | Description |
|--------|------|------------|-------------|
| `GET` | `/v1/inventory/inventories/stock-movements/{inventoryId}` | Public | Get stock movement history of one inventory |

> Reservation operations (reserve, confirm, expire) are triggered internally via messaging, not direct API calls.

---

## Configuration

Key properties in `application.yaml`:

```yaml
server:
  port: 8058

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/inventory?currentSchema=inventory

aws:
  region: eu-west-1
  sqs:
    queues:
      reservation-expire: localdev-reservation-expire-queue

spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: product-consumer
```

All AWS credentials and endpoints support environment variable overrides:

```
AWS_REGION, AWS_ENDPOINT, AWS_ACCESS_KEY, AWS_SECRET_KEY
AWS_SQS_RESERVATION_EXPIRE_QUEUE
```
