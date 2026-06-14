# FinSafe Idempotency Gateway

A production-grade **Idempotency Layer** for FinSafe Transactions Ltd., built with **Java Spring Boot** and **PostgreSQL**. It ensures payment requests are processed **exactly once**, no matter how many times a client retries.

---

## Architecture Diagram

### Sequence Diagram — Request Flow

```
Client                  API Gateway              PostgreSQL DB
  |                         |                         |
  |-- POST /process-payment |                         |
  |   Idempotency-Key: K1   |                         |
  |   { amount: 100 }       |                         |
  |                         |                         |
  |                         |-- SELECT FOR UPDATE  -->|
  |                         |   WHERE key = K1        |
  |                         |                         |
  |                         |<-- NOT FOUND -----------|
  |                         |                         |
  |                         |-- INSERT (PROCESSING) ->|
  |                         |                         |
  |                         |  [2s payment sim]       |
  |                         |                         |
  |                         |-- UPDATE (COMPLETED) -->|
  |                         |   + save response JSON  |
  |                         |                         |
  |<-- 201 Created ---------|                         |
  |    X-Cache-Hit: false   |                         |
  |    { "Charged 100 GHS" }|                         |
  |                         |                         |
  |                         |                         |
  |  [Client retries]       |                         |
  |                         |                         |
  |-- POST /process-payment |                         |
  |   Idempotency-Key: K1   |                         |
  |   { amount: 100 }       |                         |
  |                         |                         |
  |                         |-- SELECT FOR UPDATE  -->|
  |                         |   WHERE key = K1        |
  |                         |                         |
  |                         |<-- FOUND (COMPLETED) ---|
  |                         |    hash matches ✓       |
  |                         |                         |
  |<-- 201 Created ---------|                         |
  |    X-Cache-Hit: true    |                         |
  |    { "Charged 100 GHS" }| (same response, no DB   |
  |                         |  charge happened)       |
```

### Flowchart — Idempotency Decision Logic

```
                    ┌──────────────────────────┐
                    │  POST /process-payment   │
                    │  Idempotency-Key: K      │
                    └────────────┬─────────────┘
                                 │
                    ┌────────────▼─────────────┐
                    │  Key header present?     │
                    └────────────┬─────────────┘
                         No ─────┤──── Yes
                         │       │
                   400 Bad │      │
                   Request │      │
                            │
              ┌─────────────▼──────────────┐
              │  DB: SELECT FOR UPDATE     │
              │  WHERE key = K             │
              └─────────────┬──────────────┘
                            │
              ┌─────────────▼──────────────┐
              │    Record found?           │
              └────────┬────────┬──────────┘
                      YES       NO
                       │         │
         ┌─────────────▼──┐   ┌──▼─────────────────┐
         │  Check status  │   │  INSERT PROCESSING  │
         └──┬──────────┬──┘   │  Run payment (2s)   │
            │          │      │  UPDATE COMPLETED   │
         PROC.     COMPLETED  │  Return 201         │
            │          │      └─────────────────────┘
            │    ┌─────▼────────┐
            │    │ Hash match?  │
            │    └──┬───────┬───┘
            │      YES       NO
            │       │         │
            │  Return cached  409 Conflict
            │  X-Cache-Hit:   "Key used for
            │  true           different body"
            │
     Wait & poll until
     COMPLETED, then
     return cached result
     (race condition fix)
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.2 |
| Database | PostgreSQL |
| ORM | Spring Data JPA / Hibernate |
| Build Tool | Maven |

---

## Setup Instructions

### Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL 14+

### 1. Create the Database

```sql
CREATE DATABASE finsafe_db;
```

### 2. Configure credentials

Edit `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/finsafe_db
spring.datasource.username=postgres
spring.datasource.password=your_password
```

### 3. Run the application

```bash
mvn spring-boot:run
```

The server starts at `http://localhost:8080`. Hibernate auto-creates the `idempotency_records` table on first run.

### 4. Run tests

```bash
mvn test
```

---

## API Documentation

### Base URL
```
http://localhost:8080/api/v1
```

---

### `POST /process-payment`

Processes a payment. Idempotent — safe to retry with the same key.

**Headers**

| Header | Required | Description |
|--------|----------|-------------|
| `Idempotency-Key` | ✅ Yes | Unique string per transaction (UUID recommended) |
| `Content-Type` | ✅ Yes | `application/json` |

**Request Body**

```json
{
  "amount": 100,
  "currency": "GHS"
}
```

---

#### Scenario 1 — New Request (Happy Path)

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: a1b2c3d4-e5f6-7890-abcd-ef1234567890" \
  -d '{"amount": 100, "currency": "GHS"}'
```

**Response `201 Created`**
```json
{
  "status": "SUCCESS",
  "message": "Charged 100 GHS",
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "amount": 100.0,
  "currency": "GHS"
}
```
Headers: `X-Cache-Hit: false`

---

#### Scenario 2 — Duplicate Request (Same Key + Same Body)

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: a1b2c3d4-e5f6-7890-abcd-ef1234567890" \
  -d '{"amount": 100, "currency": "GHS"}'
```

**Response `201 Created`** *(same as above, no re-processing)*
```json
{
  "status": "SUCCESS",
  "message": "Charged 100 GHS",
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```
Headers: `X-Cache-Hit: true`

---

#### Scenario 3 — Fraud / Error Check (Same Key + Different Body)

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: a1b2c3d4-e5f6-7890-abcd-ef1234567890" \
  -d '{"amount": 500, "currency": "GHS"}'
```

**Response `409 Conflict`**
```json
{
  "status": 409,
  "error": "409 CONFLICT",
  "message": "Idempotency key already used for a different request body.",
  "timestamp": "2025-06-01T10:30:00"
}
```

---

#### Scenario 4 — Missing Header

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -d '{"amount": 100, "currency": "GHS"}'
```

**Response `400 Bad Request`**
```json
{
  "status": 400,
  "error": "400 BAD_REQUEST",
  "message": "Missing required header: Idempotency-Key",
  "timestamp": "2025-06-01T10:30:00"
}
```

---

### `GET /health`

```bash
curl http://localhost:8080/api/v1/health
```
```
FinSafe Idempotency Gateway is running ✓
```

---

## Design Decisions

### 1. PostgreSQL Pessimistic Locking (`SELECT FOR UPDATE`)
The repository uses `@Lock(LockModeType.PESSIMISTIC_WRITE)` when querying by idempotency key. This acquires a **row-level DB lock**, meaning if two requests arrive simultaneously with the same key, the database itself serializes them — only one proceeds while the other waits. This is the cleanest solution to the race condition bonus task.

### 2. Two-Phase Record Writing (PROCESSING → COMPLETED)
When a new key is first seen, the record is saved immediately as `PROCESSING` before the 2-second payment simulation begins. This acts as a **distributed lock flag**. Any concurrent duplicate request that sees `PROCESSING` knows to wait and poll rather than starting a new transaction.

### 3. SHA-256 Request Body Hashing
Instead of storing the full raw request body, we store a **SHA-256 hash**. This is more storage-efficient and collision-resistant for detecting tampered payloads in the fraud/error check.

---

## Developer's Choice Feature: Automatic TTL-based Cleanup

### What it is
Every idempotency record is assigned an `expires_at` timestamp (default: 24 hours after creation). A Spring `@Scheduled` task runs every hour and purges expired records from the database.

### Why it matters for Fintech
In a real payment system, idempotency keys are only meaningful within a short retry window (usually minutes to hours). Without cleanup:
- The `idempotency_records` table grows indefinitely
- Query performance degrades over time
- Storage costs increase

The TTL is configurable via `app.idempotency.ttl-hours` in `application.properties`, allowing ops teams to tune it per environment without code changes.
