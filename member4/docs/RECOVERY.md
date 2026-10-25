# Recovery procedures

## Kafka unavailable

1. Check the protected outbox screen for PENDING records, increasing attempts, next_attempt_at and last_error. Check publisher logs using the event ID.
2. Restore the broker/network/credentials. Keep Oracle outbox rows intact.
3. Workers retry automatically after next_attempt_at; check that PUBLISHED grows and pending age declines. KafkaAdmin topic creation is attempted at startup; if initial creation failed, pre-create the three topics or restart Event Service after broker recovery.
4. Investigate records with a persistent error; do not mark them PUBLISHED manually. Snapshot evidence and correct the underlying cause before retrying.

## Publisher dies

Restart Event Service. An IN_FLIGHT row becomes eligible after its 120-second lease expires. If publication already happened, it may be sent again with the same eventId. Verify a single audit row for that ID. Never generate a replacement ID for a retry. A lease protects completion state but cannot cancel an old sender, so consumers must always deduplicate.

## Oracle unavailable / consumer fails

Restore the database/network and check the connection pool and logs. Pending rows persist; unsuccessful audit transactions roll back their inbox markers and effects. The consumer retries without committing the failed record. Monitor lag and logs: a malformed event or event ID collision can block its partition indefinitely. Other partitions may continue; capacity falls during retries.

For a poison event, capture topic/partition/offset, eventId, exception and original bytes in the team's secure incident store. Stop the affected consumer group and coordinate a reviewed repair/replay plan. Do not reset offsets or remove an inbox marker merely to clear an error. This implementation deliberately has no automatic dead-letter skip; a production dead-letter policy must preserve original messages and be agreed with the team.

## Native restart and retained storage

Stop each application using Ctrl+C in its own terminal, then relaunch the executable WAR with scripts/env.local.ps1 loaded. Do not delete Oracle rows or broker/ZooKeeper data directories. If the broker must be restarted in the dedicated lab, stop Kafka gracefully in its terminal, then ZooKeeper if needed; restart ZooKeeper first, then Kafka, then Eureka and Event Service. Use the same broker properties and persistent data directories. Verify retained history and progress of a previously pending event after restart. Coordinate database backups, broker retention and offsets before any restore.

## Audit retention / security

Keep inbox IDs for at least the full possible event replay horizon, including broker retention, backups and manual replays. Deleting dedup markers early permits duplicate effects. Retain PUBLISHED outbox/audit data according to the agreed policy, and export evidence before any archival. Restrict access to the UI because last_error and envelopes may contain internal diagnostics. Use HTTPS for shared environments and rotate OPS/database/Kafka credentials through the secret store.

## Final release coordination

Freeze service names, envelope v1 and payload agreements with M1–M3. Record commit SHA, artifact checksums, JDK/Maven/dependency versions, database schema version, endpoint topology and environment variable names (no secrets). Collect all member acceptance results in docs/ACCEPTANCE.md. Rehearse a restart and Kafka outage, confirm pending backlog drains and payment idempotency tests pass in M3. Deploy Eureka, database/schema and Kafka before producers/consumers; smoke each service's registration and business endpoints. Release sign-off remains a team activity.
