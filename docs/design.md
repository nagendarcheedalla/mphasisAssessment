# Design and operating notes

## Scope

This service is a small, runnable demonstration of idempotent background transaction processing. The REST endpoint acts as a replaceable ingestion adapter; a production queue or file reader can call the same processing service. H2 is used so the example runs without an external service.

## Processing flow

1. Each request is validated independently. Invalid records receive `FAILED`, while valid siblings continue.
2. Transaction IDs and request IDs have database uniqueness constraints. Replays are reported as `DUPLICATE` and never enter the ledger again.
3. Each account has its own sequence beginning at 1. A future sequence is `PENDING_SEQUENCE`; when its gap is filled, a subsequent processing pass drains the now-ready sequence. A conflicting sequence is rejected.
4. A transaction row is locked while it is applied. The account balance update, ledger insert, attempt count, and final status share one database transaction.
5. A selected request can simulate one transient failure. The worker retries up to the configured maximum. The balance and ledger are changed only after the simulated failure point.

## Status lifecycle

`RECEIVED` → `PROCESSING` → `PROCESSED`, or `RETRY_PENDING` → `PROCESSING` → `PROCESSED` / `FAILED`. Records with sequence gaps remain `PENDING_SEQUENCE`; invalid input and exhausted/non-recoverable processing end in `FAILED`. A replay is returned as `DUPLICATE` without changing the original record.

## Consistency and limitations

The database transaction makes the local balance, ledger, and transaction status atomic. The ledger's primary key and account-sequence uniqueness constraint are a second guard against duplicate effects. This is a single-process teaching implementation; H2 in-memory data is lost on restart. The in-memory database and API batch are not a durable queue, and status claims are not a substitute for distributed locking when multiple workers run concurrently.

For production, use a durable relational database, a queue adapter, an inbox/idempotency table with retention policy, optimistic or pessimistic concurrency control, durable retry scheduling with backoff and jitter, dead-letter handling, reconciliation, health/metrics/tracing, authentication/authorization, and operational controls for replaying stuck or pending records. Define account bootstrap sequence and currency rules with the business owner. A real payment integration would also need an outbox or provider idempotency key for external side effects.

## Observability and AI-assisted development

The worker logs transaction ID, account reference, sequence, status, and attempts; it does not log amount or customer data. `/api/summary` reports persisted status counts, and each batch response includes outcomes and the current totals. AI assistance was used to accelerate project scaffolding, implementation, and test-driven correction. The candidate should be prepared to explain the sequence invariant, database transaction boundary, duplicate constraints, and test results, and personally verify every behavior before submission.
