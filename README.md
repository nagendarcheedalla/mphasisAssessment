# Idempotent Background Transaction Processing Service

A compact Java/Spring Boot implementation for the Mphasis FDE L2 practical assessment. It demonstrates safe transaction processing when batch input contains duplicates, sequence gaps, invalid records, and retryable failures.

## Requirements

- Java 17 or newer
- Maven 3.6.3 or newer

The project uses Spring Boot 3.5.16 and an in-memory H2 database. Spring Boot's current system requirements list Java 17 as the minimum and Maven 3.6.3 as the minimum supported Maven version. See the [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

## Run

```bash
mvn spring-boot:run
```

Submit the included sample batch from another terminal:

```bash
curl -X POST http://localhost:8080/api/batches \
  -H "Content-Type: application/json" \
  --data-binary @samples/transactions.json
```

For a compact example, `samples/single-transaction.json` shows one request and `samples/expected-response.json` shows its response when submitted to a fresh process.

Useful endpoints:

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/batches` | Validate and process a batch |
| `GET` | `/api/transactions` | List transaction states |
| `GET` | `/api/accounts/{accountId}/balance` | Read an account balance |
| `GET` | `/api/summary` | Counts by persisted status |

Set `processor.max-attempts` in `application.yml` to change the retry limit. `simulateTransientFailure: true` causes a request's first processing attempt to fail transiently and a subsequent attempt to succeed.

## Ordering rule

Sequence numbers start at 1 independently for each account. A request whose sequence is ahead of the next expected number remains `PENDING_SEQUENCE`. When a missing sequence arrives, the processor applies it and drains ready successors. A second transaction claiming the same account sequence is rejected. The service does not infer missing sequence numbers or skip a gap automatically.

## Idempotency

`transactionId` and `requestId` are each unique in the database. A replay matching either identifier returns `DUPLICATE` and does not create a second ledger entry or balance update. A retry of the same accepted transaction reuses the same transaction row. The balance update, ledger entry, attempts, and terminal status commit atomically.

## Verify

```bash
mvn test
```

The integration tests cover sequence buffering and draining, duplicate requests, retry safety, and invalid-record isolation.

## Assessment notes

See [docs/design.md](docs/design.md) for the architecture, status lifecycle, consistency boundary, observability, assumptions, and production hardening steps. The solution deliberately keeps the input adapter small and makes the core transaction processing service independent of the HTTP controller.
