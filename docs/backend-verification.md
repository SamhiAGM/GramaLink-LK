# Backend verification report

Date: 13 November 2026 (Asia/Colombo). Branch: `feat/db-services-shadhir`, based on Samhi's `0d76ad8` networking implementation. Verification was performed before committing the backend implementation.

## Implemented

- B01: PostgreSQL schema, constraints, indexes, synthetic offices and seven BCrypt-hashed demo accounts.
- B02: Prepared-statement JDBC repository helpers, per-transaction connections, commit/rollback.
- B03: Credential validation, persistent login outcomes, canonical active-account identity and server-enforced role/office/assignment/ownership checks.
- B04: Citizen submission and correction, GN receive/review/verify/forward/reject, DS return/approve/reject; typed state/action enums and explicit transition rules.
- B05: Atomic request history, success/denial audits, public account/office queries, constrained admin management and statistics with actual session-manager online count.
- B06: Version-checked updates and CONFLICT handling with no partial history/notifications.
- B07: Durable request chat and user-scoped notifications; PDF metadata, private storage, incremental chunks, content parsing, checksums, authorized download and abort cleanup.
- B08: Safe exception responses, injected-failure rollback tests, packaged demo, backup/restore instructions and verified synthetic dump/restore.

Business services are connected to real TCP through BackendDispatcher. Existing JSON/binary framing, synchronized writers, heartbeat and session infrastructure are retained. No REST or JavaFX implementation was added.

## Executed verification

Environment: Windows, PostgreSQL **18.6**, Maven **3.9** bundled with IntelliJ, Temurin **25.0.4.1**, compilation target **Java 21**. PostgreSQL ran as an isolated repository-local cluster on `127.0.0.1:55439`; existing PostgreSQL installations/databases were not changed. The temporary database server and packaged demo server were stopped afterwards.

Final `mvn verify` with GRAMALINK_TEST_DB_URL configured:

**BUILD SUCCESS — 176 tests, 0 failures, 0 errors, 0 skipped.**

This consists of the existing **163 networking/protocol tests** and **13 new real PostgreSQL integration tests**. The integration suite uses a generated isolated schema and temporary storage, and covers:

1. Valid/invalid credentials, role handling and denied-operation audits.
2. Full request approval workflow with durable history and notifications.
3. Correction/resubmission and DS return requiring GN re-verification.
4. Cross-user, cross-office and invalid-role access rejection.
5. Two concurrent updates: exactly one success, one CONFLICT, one new history entry.
6. Forced history insertion failure: request, history and notifications roll back together.
7. Offline chat/notification retrieval and user-scoped read acknowledgement.
8. Admin account activation, office management, validation and statistics.
9. Valid PDF upload/download with integrity checks and unauthorized/tampered download rejection.
10. Forbidden/oversized files, traversal filenames, fake PDF content and incorrect checksums.
11. Aborted uploads and forced metadata failure leaving no published document or leftover file.
12. Four real TCP clients: login, workflow push, approval, heartbeat, reconnect retrieval and PDF transfer.
13. Real TCP rejection of unauthenticated operations and forged Admin role claims.

`git diff --check` passed. The compiler used `--release 21`; an actual Java 21 runtime was not installed, so execution on that exact runtime remains a deployment check.

## Packaged application demonstration

Built executable dependency-inclusive `target/gramalink-lk-1.0-SNAPSHOT.jar`. Started its actual ServerMain with the demo database on TCP port 55440 and ran BackendDemo from the packaged JAR, without the test fixture adapters.

Observed:

- Four successful role logins and live online count of 4.
- Synthetic request `99754703-52d8-40c8-b799-a7f230df7a6e` created.
- Generated PDF uploaded and downloaded byte-for-byte: 586 bytes.
- SHA-256: `c3f9dd25f1c14f34ac7f7bdaa537a437262f4742e519f5dcc659c08b941fadbf`.
- GN transitions: RECEIVED_BY_GN, UNDER_GN_REVIEW, VERIFIED_BY_GN, FORWARDED_TO_DS.
- DS decision: APPROVED; 6 history entries.
- Reconnected citizen retrieved 6 durable notifications.
- Windows storage ACL inspection showed only the current server user with FullControl.

The actual demo database was dumped with pg_dump and restored with pg_restore into a separate new database. Restored counts: **7 users, 1 request, 6 history rows, 1 document**. The corresponding storage backup was copied and its SHA-256 matched restored metadata. Generated cluster data, dumps, storage and raw execution logs remain ignored under `.local`; they contain synthetic data only.

## Remaining integration and limits

- Ahamad must connect JavaFX to the documented payloads/events, display conflict/reload behavior and handle READY/TRANSPORT_RECEIVED/STORED and reconnect recovery.
- Team review is needed for the proposed workflow assumptions, role spellings CITIZEN/GN/DS/ADMIN, new query/admin message names and deterministic officer assignment.
- Separate-device LAN/Wireshark and JavaFX end-to-end demonstrations remain team tasks. Tests here are local loopback, not LAN latency evidence.
- File policy intentionally supports plain synthetic PDFs only. No malware scanning, upload resume, automatic crash-orphan cleanup or exactly-once command retry is provided. Requests and file operations can commit before a client loses its reply; recover by querying durable state.
- Push delivery is best effort after commit; persisted notifications and state retrieval provide recovery. There is no background outbox retry worker.
- Connection pooling is optional and not used; JDBC obtains a fresh connection per transaction. No government identity integration or legally valid approvals are claimed.

See README for reproducible commands and backend-contract.md for teammate API details.
