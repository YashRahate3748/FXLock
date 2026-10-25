# Native lab acceptance evidence

See DELIVERY-VALIDATION.txt for checks actually run during delivery. The following lab acceptance results are still pending.

## Automated checks

1. Load scripts/env.local.ps1 and run `mvn clean verify` with Java 25. This builds both executable WARs and runs 3 unit tests.
2. For integration tests, use a dedicated empty Oracle test schema initialized with database/001-schema.sql. Keep application processes stopped, while the installed Oracle database, ZooKeeper and Kafka are running. Set the environment to that dedicated schema.
3. Set `$env:RUN_INFRA_TESTS='true'` and run `mvn test`. Five tests exercise Oracle deduplication, rollback, concurrent claims, crash-window replay through real Kafka, and failure/recovery. The connection-failure test targets a deliberately unreachable endpoint before retrying the same row through the configured local Kafka broker. It does not stop or restart your broker.
4. Remove RUN_INFRA_TESTS from the shell before starting applications. Set the application Oracle environment again, start Eureka and Event Service, and run `./scripts/smoke.ps1`. This saves health, discovery, audit/outbox API and UI checks in evidence/smoke.json.
5. CI runs the build/unit checks on Windows. Optional workflow_dispatch lab checks require a self-hosted Windows runner labelled fx-lab, a dedicated test schema, existing running Kafka, and the fx-lab environment secrets ORACLE_URL/ORACLE_USER/ORACLE_PASSWORD. KAFKA_BOOTSTRAP_SERVERS is an environment variable setting in GitHub. No infrastructure is installed by CI.

The crash test deterministically publishes without marking the row and expires its lease. Its Kafka reads invoke the transactional consumer bean. Validate the deployed Kafka listener and actual process crash using the manual checks below.

## Manual lab acceptance

| Check | Procedure and expected evidence | Actual result |
| --- | --- | --- |
| Clean setup | Initialize a dedicated schema, start local ZooKeeper/Kafka, build and launch WARs; capture versions/logs/smoke JSON | Pending |
| Discovery | All agreed service names are UP and advertised endpoints are reachable; restart each client | Pending |
| Actual Kafka outage/recovery | In a dedicated lab, stop Kafka gracefully in its terminal while leaving Oracle and Event Service running. Trigger a producer transaction; verify PENDING with attempts/error. Restart Kafka with the same properties/data directory; verify PUBLISHED and one audit effect | Pending |
| Publisher process crash | Break after send.get and before store.published; terminate publisher; restart and wait >120s; verify replay of same ID and one audit effect | Pending |
| Consumer deduplication | Deliver identical stored envelope twice; verify one inbox marker and one audit effect | Pending |
| Effect failure | Force an audit insert failure in the dedicated test schema; verify marker rollback and retry | Pending |
| Native restart | Record history/pending event; gracefully restart services using the same Oracle and broker data; verify history survives and pending work drains | Pending |
| UI/API | Authenticate, filter aggregate/status, page through records and inspect envelope/errors; unauthorized requests rejected | Pending |
| M3 payment idempotency | Double click/retry/concurrent confirm with the same payment key; one settlement and atomic outbox event | Pending — M3 |

Keep commit SHA, timestamps, logs, screenshots and SQL counts for each check. Exclude secrets/customer data. Final service-name, payload and release agreement remains a team task.
