# FX project — Member 4 (local ZooKeeper / Kafka / Oracle)

For Spring Tools parent-POM download or XML schema errors, follow [STS-MAVEN-FIX.md](docs/STS-MAVEN-FIX.md). The supplied `scripts/build.ps1` selects project-local direct-network Maven settings for both global and user settings, without changing machine settings.

Maven project using **Spring Boot 4.1.2, Spring Cloud 2025.1.4, Java 25, WAR and YAML**, matching the supplied Initializr settings. The parent has POM packaging, event-contract is a library JAR, and both services are executable WARs. Group/package prefix: com.example.

This edition uses the existing local Oracle database, ZooKeeper and Kafka installations. No infrastructure installation or automatic broker shutdown is performed by the project.

## 1. Files and prerequisites

- eureka-server: discovery on 8761.
- event-service: leased Oracle outbox publisher, deduplicating audit consumer, audit APIs and UI on 8084.
- event-contract: common envelope and transactional outbox appender.
- database/001-schema.sql: run once as the Oracle application user.
- scripts/env.ps1.example: local application environment template.
- scripts/smoke.ps1: health, discovery, API and UI smoke checks.
- config and examples: client registration and producer integration for the other members.
- docs: recovery, acceptance checklist and delivery validation.
- .github/workflows/ci.yml: Windows Maven build and optional dedicated lab integration checks.

Use JDK 25 and Maven 3.9+ for the application. Keep the broker's own Java version configured as required by your installed Kafka version; do not force Java 25 onto an older Kafka installation. Your local C:\kafka_2.12-2.2.0 configuration was found with ZooKeeper localhost:2181 and Kafka localhost:9092. The commands below target that ZooKeeper-based installation. A separate Kafka 4.x installation uses KRaft instead of ZooKeeper; do not start both brokers on port 9092.

## 2. Start your existing ZooKeeper and Kafka

If already running, leave them running. Otherwise open two separate PowerShell terminals. Use the same Java environment that already works for your installed Kafka. For Kafka 2.2, that is normally Java 8; these terminals are separate from the application terminals.

Terminal 1:

```powershell
Set-Location C:\kafka_2.12-2.2.0
.\bin\windows\zookeeper-server-start.bat .\config\zookeeper.properties
```

Terminal 2:

```powershell
Set-Location C:\kafka_2.12-2.2.0
.\bin\windows\kafka-server-start.bat .\config\server.properties
```

Leave both processes running. The service connects to Kafka on 9092; it does not connect directly to ZooKeeper. Expected broker configuration (already found locally):

```properties
listeners=PLAINTEXT://localhost:9092
advertised.listeners=PLAINTEXT://localhost:9092
zookeeper.connect=localhost:2181
```

The Event Service attempts to create the three topics automatically. To create them manually with your Kafka 2.2 tools, use a third broker-tools terminal:

```powershell
Set-Location C:\kafka_2.12-2.2.0
.\bin\windows\kafka-topics.bat --zookeeper localhost:2181 --create --if-not-exists --topic fx-rate-updated --partitions 3 --replication-factor 1
.\bin\windows\kafka-topics.bat --zookeeper localhost:2181 --create --if-not-exists --topic fx-settlement-completed --partitions 3 --replication-factor 1
.\bin\windows\kafka-topics.bat --zookeeper localhost:2181 --create --if-not-exists --topic fx-system-alert --partitions 3 --replication-factor 1
.\bin\windows\kafka-topics.bat --zookeeper localhost:2181 --list
```

For a different Kafka version, use that installation's tools and supported topic flags (modern versions use --bootstrap-server localhost:9092). Keep Kafka client versions managed by Boot; integration with the installed broker still needs the supplied lab tests. References: [Kafka compatibility](https://kafka.apache.org/41/getting-started/compatibility/) and [ZooKeeper-mode quick start](https://kafka.apache.org/39/getting-started/quickstart/).

## 3. Oracle, environment, build and launch

Start the existing Oracle database/listener. A DBA creates an application user with CREATE SESSION, CREATE TABLE and sufficient tablespace quota in the intended PDB. Connect using SQL Developer or SQL*Plus as that user and execute database/001-schema.sql **once**. Do not use SYS/SYSTEM as the application user. Existing business and outbox tables must share the same transaction database; see section 4.

From the project root, copy scripts/env.ps1.example to scripts/env.local.ps1. Set your actual Oracle service name, username and password, plus OPS credentials. For example, use FREEPDB1 or XEPDB1 according to the installed database, not according to the example. This local file is Git-ignored.

Application terminal 1 (Eureka):

```powershell
. ./scripts/env.local.ps1
java -version
mvn -version
./scripts/build.ps1
java -jar eureka-server/target/eureka-server-1.0.0.war
```

Application terminal 2 (Event Service):

```powershell
. ./scripts/env.local.ps1
java -jar event-service/target/event-service-1.0.0.war
```

Open http://localhost:8761 for Eureka, and http://localhost:8084/ for the operations screen. Sign into the screen with OPS_USER/OPS_PASSWORD. Keep this plain HTTP configuration on the private lab machine; shared deployments require HTTPS and restricted infrastructure access.

Application terminal 3 (checks):

```powershell
. ./scripts/env.local.ps1
./scripts/smoke.ps1
```

The smoke script records evidence/smoke.json. All applications use YAML environment placeholders. No automatic startup DDL is enabled; keep schema changes versioned and preserve tables across restarts.

## 4. Integration agreement with M1/M2/M3

Proposed names (team confirmation remains required): `fx-rate-service:8081`, `fx-quote-service:8082`, `fx-payment-service:8083`, `event-service:8084`, `eureka-server:8761`. Merge config/client-application.yml and config/client-dependencies.xml into each producer. Eureka registration is enabled by the client dependency; identity follows `spring.application.name`. To make calls through discovery, use the agreed application name with a Spring Cloud LoadBalancer client; the registry does not itself proxy HTTP traffic. No other members' source code was supplied, so their registrations cannot be changed or verified here.

**This lab topology assumes the producer business tables and fx_outbox share one Oracle database/schema.** Each producer uses OutboxAppender inside the same local transaction as its business update; the Event Service scans that shared outbox. Do not POST business events to the Event Service after committing the business transaction: that loses atomicity. If members have separate databases, deploy an outbox worker beside each producer with its local schema; centralize only the audit consumer. The current worker is in Event Service and must be extracted/reused for that topology before release.

Run `mvn install` to make event-contract available locally, or publish the library to the team's Maven repository. Example transaction code is in examples/ProducerIntegration.java.example. Services that do not publish events need only the Eureka client dependency and configuration.

## 5. Common contract

The event ID is a UUID created **once** in the original business transaction. Republish the stored envelope unchanged. Fields: eventId, schemaVersion=1, eventType, aggregateId, source, correlationId, occurredAt (UTC ISO-8601), payload (JSON object). See examples/event.json. Never put account secrets or full payment credentials in payloads. Event size is capped by the shared appender at 200,000 characters.

| Topic and eventType | Producer | Aggregate key | Suggested payload |
| --- | --- | --- | --- |
| fx-rate-updated | Rate service | Currency pair | baseCurrency, quoteCurrency, rate as decimal string, providerTimestamp |
| fx-settlement-completed | Payment service | Payment ID | paymentId, status, sourceAmount/targetAmount as decimal strings, currencies, quoteId |
| fx-system-alert | Any service | Incident/system ID | code, severity, message |

These are proposed v1 payload agreements to finalize with teammates. The code validates the common envelope and topic; domain payload rules remain with each producer. Kafka record key = aggregateId. Retries may reorder distinct events for an aggregate: no strict business event ordering is promised by this outbox worker. Use an aggregate sequence/version and consumer ordering rules if downstream projections require them.

The worker claims one row in a short transaction using Oracle SKIP LOCKED, saves a random claim token and a 120-second lease, then sends outside the DB transaction. It marks PUBLISHED only after Kafka acknowledgement (`acks=all`). Failures return to PENDING with bounded exponential backoff (up to 256 seconds), attempts and last_error. Expired claims are recovered automatically. Stale owners cannot change a newer claim because all completion updates require the token. A publisher crash after Kafka publication can deliver duplicates; this is **at-least-once delivery**.

The audit consumer inserts a unique `(consumer_name,event_id)` marker and the history effect in one Oracle transaction. Duplicate delivery is a no-op; ID reuse with different content fails visibly. Database commit completes before Kafka RECORD acknowledgement; a crash between them causes a safe replay. Other consumers must implement the same pattern in their own effect database. This does not implement M3's payment-request idempotency or bank settlement guarantees.

## 6. Read APIs and screen

All operations APIs and the UI require OPS credentials. Health is public with no details. API responses have uppercase Oracle column labels.

```text
GET /api/audit?aggregateId=PAY-1001&offset=0&size=50
GET /api/audit/{eventId}
GET /api/outbox?status=PENDING&offset=0&size=50
GET /actuator/health
```

Size is bounded to 200. Outbox responses expose attempts, next retry, lease, publication time and the last error. Audit responses expose source, correlation ID, occurrence/receipt timestamps and Kafka location. The screen uses textContent for stored data, supports paging/filtering, and displays the selected event envelope. It has no settlement or destructive operations.

## 7. Shared deployment

Set ORACLE_URL/USER/PASSWORD, KAFKA_BOOTSTRAP_SERVERS, EUREKA_URL, OPS_USER/PASSWORD, SERVICE_HOSTNAME, INSTANCE_ID and optionally SERVER_PORT/ADVERTISED_PORT per service. SERVICE_HOSTNAME must resolve from **other Eureka clients**: use `localhost` for native local clients and reachable hostnames in the lab. Do not advertise localhost when clients run on different machines.

Set KAFKA_REPLICAS=3 with a three-broker shared cluster, pre-create topics with min.insync.replicas=2, and configure Kafka TLS/SASL via Spring's Kafka environment properties per lab policy. Single-broker defaults offer no broker failure durability. Keep database backups, durable broker storage and credentials in the lab secret manager.

WARs run directly with `java -jar`. For external servlet deployment, use a Boot 4-compatible Servlet 6.1 container (for example Tomcat 11), configure environment variables on that container, and account for the WAR context path in URLs and Eureka metadata. The instructions and smoke script assume root-context executable WARs, not an external Tomcat installation.

## References

- https://spring.io/projects/spring-cloud/ — Boot 4.1 support starts with Cloud 2025.1.2.
- https://docs.spring.io/spring-boot/system-requirements.html — Java and servlet requirements.
- https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html — Eureka registration/configuration.
- https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html — RECORD offset commit behavior.
