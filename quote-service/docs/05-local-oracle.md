# Common Oracle database on this laptop

Connection: jdbc:oracle:thin:@//localhost:1521/freepdb1
User/schema: FXLOCK. Database service: FREE; pluggable database: FREEPDB1.
The exact supplied team SQL has been applied in this new local schema, including
all 13 tables, constraints, indexes, config, 3 customers, 6 wallets and DEV_SEED.
The earlier FXLOCK_M2 schema is unused and preserved; its private proposal is
removed from this source. M2 now stores quotes in the same logical schema as M3.
Each teammate's local laptop may have separate data despite using the same DDL.

## Start M2

Build: mvn package. Then run .\run-oracle.ps1 from the project folder.
The launcher activates oracle-local alone, uses port 8082 by default and reads
%LOCALAPPDATA%\FXLock\common-oracle.credentials.xml. Windows encrypts this file
for the current user. It is outside Git. Use -Port 8084 if 8082 is occupied.

After startup log in at http://localhost:8082 (or the chosen port). Requests to
/wallets/quote-options return actual numeric wallet IDs. Health UP confirms
connectivity, but a quote also requires a fresh trusted observation.

DEV_SEED expires normally. While M1 is not running, you may manually execute
sql/local-development-rate.sql **only in this local development schema** to add
an explicitly synthetic observation. It inserts a new row rather than modifying
an observation referenced by existing quotes. It does not simulate provider
availability. Do not increase freshness limits just to make a test pass.

## SQL Developer

Create a Basic connection: host localhost, port 1521, service name freepdb1,
username FXLOCK. You can view tables directly. If a password is needed, open a
private PowerShell window and read the encrypted credential locally; do not
paste it into chat, screenshots or code:

```powershell
$c = Import-Clixml "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml"
$c.GetNetworkCredential().Password
```

That command displays a secret on your own screen, so use it only when necessary.
The launcher does not print the password. Prefer your team's credential-sharing
method when connecting other local services to FXLOCK.

## Fresh setup on another laptop

An Oracle administrator creates a NEW user with CREATE SESSION, CREATE TABLE
and a bounded USERS quota. Connect as that user to FREEPDB1. Enable SQL*Plus
blank-line support before running the exact SQL:

```sql
SET SQLBLANKLINES ON
SET DEFINE OFF
WHENEVER SQLERROR EXIT FAILURE ROLLBACK
@sql/01_fxlock_schema.sql
```

The common file includes DROP TABLE cleanup. Never run it on an existing schema
with data you need. Do not apply M3's older V001 migration to this schema.
Save credentials locally in Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\FXLock" | Out-Null
Get-Credential -UserName FXLOCK | Export-Clixml "$env:LOCALAPPDATA\FXLock\common-oracle.credentials.xml"
```

M2 Oracle startup uses spring.sql.init.mode=never: it never recreates tables.
Run test-oracle.ps1 -Browser -Package to verify all optional suites. No live
teammate settlement or Kafka delivery is claimed by those tests.
