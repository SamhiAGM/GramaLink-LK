# GramaLink-LK
Real-time client-server coordination system connecting Citizens, Grama Niladhari Officers and Divisional Secretariat Officers using Java, JavaFX, TCP Socket Programming and PostgreSQL, with request tracking, live notifications, file transfer and role-based access.

## Backend module (Shadhir)

Java 21+, Maven 3.9+, PostgreSQL, JDBC, BCrypt and PDFBox. This branch builds on Samhi's TCP implementation at `0d76ad8`; JavaFX remains Ahamad's module. All supplied accounts, offices and documents are synthetic. Communications remain direct TCP sockets, not REST.

### Database setup

Create a dedicated local demo role/database using PostgreSQL's `psql` (run as your PostgreSQL administrator):

```sql
CREATE ROLE gramalink LOGIN;
\password gramalink
CREATE DATABASE gramalink OWNER gramalink;
```

Then, from this repository, apply the scripts as the new role. `psql -W` prompts for the database password; the scripts contain no database credentials.

```powershell
psql -h localhost -U gramalink -d gramalink -W -v ON_ERROR_STOP=1 -f sql/schema.sql
psql -h localhost -U gramalink -d gramalink -W -v ON_ERROR_STOP=1 -f sql/seed.sql
```

`schema.sql` is the initial schema: apply it once to an empty database. `seed.sql` is safe to repeat for the supplied UUIDs and does not reset existing accounts. Tables include the required eight plus `audit_logs`. Indexes support assignment queues, history, messages, notifications and audit queries. Apply future migrations explicitly; the server never drops or recreates a database.

### Build and run

Set `JAVA_HOME` to a Java 21+ JDK and ensure Maven is on PATH. The build targets Java 21 bytecode.

```powershell
mvn verify
$env:GRAMALINK_DB_URL = 'jdbc:postgresql://localhost:5432/gramalink'
$env:GRAMALINK_DB_USER = 'gramalink'
$env:GRAMALINK_DB_PASSWORD = Read-Host 'Demo database password'
$env:GRAMALINK_STORAGE = Join-Path $PWD 'storage'
java -jar target/gramalink-lk-1.0-SNAPSHOT.jar 5000
```

The packaged JAR includes runtime dependencies. The server verifies database availability before listening. An unavailable schema/database/storage fails startup with a safe error. The password must be set; it is not printed or committed. Environment variables are local to this shell and inherited by the server process.

Optional settings:

| Setting | Default | Meaning |
|---|---|---|
| `GRAMALINK_MAX_FILE_BYTES` | `10485760` | Maximum upload bytes; supported range 1 byte to 100 MiB |
| `GRAMALINK_ALLOWED_MEDIA_TYPES` | `application/pdf` | Only the PDF validator is implemented; other types fail startup |
| `GRAMALINK_STORAGE` | `storage` | Dedicated private server directory outside any web/public root |
| `-Dgramalink.heartbeat.timeoutSeconds=35` | 35 seconds | Server heartbeat expiry |
| `-Dgramalink.heartbeat.checkIntervalSeconds=5` | 5 seconds | Expiry check interval |

The storage directory and newly created files use server-user-only POSIX permissions or Windows ACLs. Use a directory dedicated to this server; initialization applies its private directory permissions. Do not point storage at a shared folder. Keep the same storage directory across restarts.

### Synthetic demo accounts

All seeded demo accounts use **`DemoPass123!`**. Only BCrypt cost-12 hashes are stored in SQL/database. These are public synthetic demonstration accounts, not government credentials.

| Username | Role | Division |
|---|---|---|
| `citizen.demo` | CITIZEN | DEMO-A |
| `gn.demo` | GN | DEMO-A |
| `ds.demo` | DS | DEMO-A |
| `admin.demo` | ADMIN | DEMO-A |
| `citizen.other` | CITIZEN | DEMO-B |
| `gn.other` | GN | DEMO-B |
| `ds.other` | DS | DEMO-B |

### TCP demonstration without JavaFX

With the server running, open another shell and execute:

```powershell
java -cp target/gramalink-lk-1.0-SNAPSHOT.jar lk.gramalink.server.backend.BackendDemo 127.0.0.1 5000
```

The harness logs in four roles, creates a request, generates and uploads a plain PDF, verifies a downloaded copy, receives/ reviews/verifies/forwards through GN, approves through DS, reads history and statistics, and reconnects to fetch durable notifications. It creates a new synthetic request on each run. It is a short demo client, not the persistent/background JavaFX client.

### Tests

`mvn verify` always runs the existing networking suite. Database integration tests require an explicitly configured dedicated PostgreSQL test database and are **skipped** when `GRAMALINK_TEST_DB_URL` is unset. To run everything:

```powershell
$env:GRAMALINK_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/gramalink_test'
$env:GRAMALINK_TEST_DB_USER = 'gramalink_test'
$env:GRAMALINK_TEST_DB_PASSWORD = Read-Host 'Test database password'
mvn verify
```

Create this test role/database separately and give the role permission to create schemas in that database. Each integration run creates a random `test_<uuid>` schema, applies the real PostgreSQL schema/seeds, uses temporary private storage and drops only that generated schema afterwards. No H2 substitute is used. Individual fixtures truncate only tables inside that generated schema. A crash may leave a `test_<uuid>` schema for manual inspection/cleanup.

Coverage includes credential validation, role/office/ownership checks, correction and DS-return paths, approval/rejection, atomic history, simultaneous stale updates, injected database failures, offline recovery, file validation/hash failures, upload cleanup and four-role communication through real TCP handlers.

### Integration and contribution boundaries

- [Workflow assumptions and implementation plan](docs/backend-implementation-plan.md)
- [Backend service and TCP contract](docs/backend-contract.md)
- [Backend verification report](docs/backend-verification.md)
- [Samhi's binary transport contract](docs/file-transfer-protocol.md)
- [Samhi's recorded networking evaluation](docs/n08-network-testing-report.md)

Business logic is in `server/service`, SQL operations in `server/repository`, and typed records/dispatch/demo code in `server/backend`. `BackendDispatcher` maps TCP messages to services and sends post-commit events through Samhi's existing `EventRouter`. Networking changes are limited to dependency injection, additive message names, upload completion and starting authorized downloads. Existing network-only constructors remain available for their test fixtures; `ServerMain` uses the integrated backend.

### Demo backup and restore

Stop the server before copying storage or taking a matched database/file backup. The database dump contains metadata, not file bytes.

```powershell
pg_dump -h localhost -U gramalink -W -Fc -f gramalink-demo.dump gramalink
Copy-Item -LiteralPath ./storage -Destination ./storage-backup -Recurse
```

For restore, create a **new empty** database owned by the demo role, then restore the dump and copy the matching storage backup to a new dedicated directory. Set `GRAMALINK_DB_URL` and `GRAMALINK_STORAGE` to these restored locations.

```powershell
pg_restore -h localhost -U gramalink -W --no-owner --exit-on-error -d gramalink_restored gramalink-demo.dump
Copy-Item -LiteralPath ./storage-backup -Destination ./storage-restored -Recurse
```

Do not apply `schema.sql` before restoring a complete dump. After an unclean shutdown, `.part` files and finalized files without a matching `documents.storage_name` row are invisible to clients. With the server stopped, reconcile/quarantine these orphans before deleting anything; do not remove files referenced by metadata. This prototype does not implement a crash-recovery daemon or cross-connection upload resume.
