# Current integration and feature verification checklist

This is the current client integration report. The historical U07/U08 report below predates the merged PostgreSQL backend and is retained only as implementation history. The source of truth for wire fields is backend-contract.md and the actual backend services.

## Architecture and protocol mapping

ClientMain / FXML controllers → role workspace ports → role gateways / production protocol adapters → the existing NetworkClient → Samhi's mixed JSON/binary TCP codec → BackendDispatcher → Shadhir's transactional JDBC services → PostgreSQL and private PDF storage.

BackendQueries consumes operation/data ACK envelopes, explicit pagination and numeric/cursor fields. It rejects missing operation/data and mismatched correlation IDs. Gateways run reads on their background workers and publish through session-epoch guards. Controllers dispatch with Platform.runLater. Push NEW_REQUEST, REQUEST_UPDATED, CHAT_MESSAGE and NOTIFICATION are refresh hints; the UI applies only newly queried, authorized snapshots. This handles events before ACK and prevents untrusted event contents from becoming UI state. Refreshes coalesce; commands and credentials are never replayed.

| UI operation | Existing server command |
| --- | --- |
| Login / current identity | LOGIN / ME |
| Requests / details / history | LIST_REQUESTS / GET_REQUEST / GET_HISTORY |
| Citizen submit | CREATE_REQUEST: type, title, description, division |
| Citizen correction | STATUS_UPDATE: RESUBMIT, expectedVersion, title, description |
| GN receive / review / verify | STATUS_UPDATE: RECEIVE / REVIEW / VERIFY |
| GN information / forward / reject | STATUS_UPDATE: REQUEST_CORRECTION / FORWARD / REJECT |
| DS approve / reject / return | STATUS_UPDATE: APPROVE / REJECT / RETURN |
| Conversations | CHAT_MESSAGE body / GET_CHAT |
| Own notifications | GET_NOTIFICATIONS, afterId cursor |
| Accounts | LIST_USERS / CREATE_USER / SET_USER_ACTIVE |
| Offices | LIST_OFFICES / CREATE_OFFICE / RENAME_OFFICE |
| Admin totals / audit | GET_STATISTICS / GET_AUDIT |
| Files | LIST_DOCUMENTS / FILE_META / typed FileChunk |

## Feature acceptance matrix

Statuses refer to local automated integration against the real server and an isolated PostgreSQL test database; they do not certify a two-device LAN demonstration or a full security audit.

| Area | Status | Verified scope / limit |
| --- | --- | --- |
| Authentication | COMPLETE | Four backend-authenticated roles, invalid/malformed responses and correlation isolation |
| Citizen | COMPLETE | Actual title/description submission, own request/history/chat/notifications, versioned corrections and reconnect retrieval |
| GN | COMPLETE | Assigned inbox and versioned receive/review/request verification/forward; document-ID verification is unsupported |
| DS | COMPLETE | Real forwarded queue and approval; reject/return mappings and validation covered by unit/backend tests |
| Admin | PARTIAL | Real listing/create/rename/activation/audit; per-client session rows and unsupported editing/archive are blocked |
| Networking | COMPLETE | Existing framing/correlation/concurrency suite plus production TCP workflow |
| Events/chat/notifications | COMPLETE | Query reconciliation of committed state, persisted chat and own durable notifications |
| Files | COMPLETE | Real plain PDF STORED confirmation, authorized checksum-verified download; ACK-only negative coverage |
| Reliability | PARTIAL | Existing heartbeat/backoff/revocation tests; fresh login required and no replay; manual server restart/LAN loss not yet demonstrated |
| Persistence | COMPLETE | Request/chat/file records verified via JDBC in dedicated test database |
| Security | PARTIAL | Backend ownership/RBAC/version tests; TCP is unencrypted and this is not a security certification |
| Production desktop demonstration | COMPLETE | Normal login FXML/Connect button routes all four real test-database accounts; ClientMain also launched separately on the workstation |

## File guarantees and limits

ClientFiles streams at most 64 KiB per upload chunk, allows one transfer per client, validates with FileTransferProgress and waits for each transport ACK. It reports success only after matching STORED transfer/document/hash confirmation. Downloads first retrieve authorized LIST_DOCUMENTS metadata, negotiate READY, use bounded chunks, verify SHA-256, and move a local .part file to a new destination without overwriting existing files. The backend remains the final PDF/size/permission validator.

The client limit defaults to 10 MiB; a customized server deployment must match it with the JVM property gramalink.client.maxFileBytes. The server currently exposes no limits query. Unfinished download .part files may remain locally and are never presented as complete documents. No resume or business-operation retry occurs. There is no server cancel-transfer command; disconnecting aborts unfinished grants. If STORED is lost, sign in again and refresh documents before deciding whether to upload again.

## Owner integration dependencies

- Samhi/Shadhir: no per-client online session query; only onlineConnections is provided.
- Shadhir: no general user edit, role reassignment, office archive/structural reassignment, document-ID verification, or profile-edit operation. These remain unavailable.
- Shadhir: SQL timestamps currently lack an offset (Timestamp.toString); the local demo assumes matching client/server timezones. An explicit offset/timezone contract is needed for deployments across zones.
- Samhi: transport confidentiality/TLS is outside this frontend change. Use a trusted isolated demonstration network; the current protocol sends credentials over plain TCP.
- There is no explicit logout command, revocation event, transferable token, resumable file transfer or command idempotency key. Logout closes the socket; reconnection always requires fresh authentication. UNAUTHENTICATED revokes local access when returned by the backend; FORBIDDEN on one request does not globally revoke the session.
- Offset pagination is not a stable concurrent snapshot. Refresh reconciles events, but the backend provides no cross-page snapshot token or outbox delivery guarantee.

## Repository audit

Branch: feat/javafx-ahamad. Existing commits include U02 (4a639bb), U03, U04, U05 and U07/U08, followed by the backend/frontend merge (7373f2f). The latest production authentication changes were already uncommitted and were preserved. No server/shared/repository/service/SQL files are changed by this integration. No Member Task/Full Project PDF or standalone NetworkListener.java was found; NetworkClient.Listener is the actual callback interface.

The untracked filename beginning hboard contains an ANSI-coloured Git diff-stat listing from the Citizen implementation. It is an unrelated terminal-output artifact, not Java source. It is preserved and must be excluded from staging. Ignored .local files contain workstation configuration; never stage or print their credentials.

## Commands (Windows CMD, project directory containing pom.xml)

Stop the running GramaLink server with Ctrl+C before clean/package/verify. Windows locks an executing JAR; never kill all Java processes or stop PostgreSQL/GRIDORA to fix that lock.

```cmd
cd /d "C:\Users\Admin\Downloads\GramaLink-LK-main\GramaLink-LK"
for /f "usebackq delims=" %L in (".local\gramalink-test.env") do @set "%L"
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" "-Dgramalink.uiTests=true" clean verify
```

The for command imports the ignored, generated test settings without echoing values. In a .cmd script use %%L. The dedicated database is gramalink_test with its own role. Both database fixtures create isolated random schemas there; the client fixture refuses the application database. Application data is never truncated or reseeded.

Backend, after a successful build:

```cmd
for /f "usebackq delims=" %L in (".local\gramalink-server.env") do @set "%L"
set "GRAMALINK_STORAGE=%CD%\storage"
java -jar target\gramalink-lk-1.0-SNAPSHOT.jar 5000
```

The local env file is workstation-specific. On a new machine configure GRAMALINK_DB_URL, GRAMALINK_DB_USER and GRAMALINK_DB_PASSWORD privately according to README. Do not copy a demo application password into database configuration.

JavaFX in another CMD terminal:

```cmd
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" javafx:run
```

Use the four README demo accounts and the documented application demo password. No offline preview flag is involved.

## Manual university demonstration

1. Confirm PostgreSQL readiness, start the backend and read its actual listening output. Launch four JavaFX clients.
2. Sign in with citizen.demo, gn.demo, ds.demo and admin.demo; verify the role-specific windows and absence of a role selector.
3. Citizen: create an OTHER request in DEMO-A with a title and description; optionally select a plain PDF. Note the backend request UUID.
4. GN: refresh/select the assignment, Receive, Start review, Verify request, Forward. Check the numeric revision and timeline after each committed response.
5. DS: select the forwarded request and approve, or reject/return with the required reason. Citizen: verify the committed status and notifications.
6. Send request-scoped chat from two roles. Download the supporting PDF from an assigned officer; compare its content/checksum.
7. Admin: refresh users/offices/statistics/audit. Demonstrate create/activate only with disposable demo records; do not disable existing assigned accounts.
8. Close/reconnect a client; fresh login must be required, and the request/chat must reload from PostgreSQL. Do not retry an uncertain approval or upload automatically.
9. Demonstrate a version conflict with two GN/DS clients and cross-division denial. Run these mutations in a dedicated demo/test environment, not against valued records.

For LAN use the actual backend IPv4 address in each client's Server IP field, expose only TCP 5000 on a trusted network, and keep PostgreSQL bound to localhost. Firewall changes and a two-device test have not been performed automatically.

## Final executed verification — 14 November 2026

- `mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" "-Dgramalink.uiTests=true" clean verify`: **BUILD SUCCESS; 323 tests, 0 failures, 0 errors, 0 skipped**. Surefire XML totals were checked independently against the Maven summary. The actual run imported the ignored dedicated-test database configuration; neither database suite was skipped.
- `ProductionWorkflowIntegrationTest`: six real PostgreSQL/TCP tests cover Citizen creation/chat/reconnect, GN-to-DS approval and cross-citizen denial, Admin create/rename/activation/audit and live account revocation, multi-chunk plain-PDF storage/checksum-verified officer download, the normal JavaFX login form for all four roles, and correction/resubmission.
- `ClientFilesTest` proves a final TRANSPORT_RECEIVED ACK without STORED does not complete an upload and disconnect does not replay it. Existing framing, concurrency, heartbeat, recovery, gateway and desktop tests all passed in the full run.
- Started the packaged backend with the private existing application settings: actual output was `GramaLink Server started on port 5000`. Production LoginService authenticated citizen.demo, gn.demo, ds.demo and admin.demo against the application database and authorized exactly one correct role each. Wrong credentials returned UNAUTHENTICATED. This check created only the backend's normal login audit records, not new application requests/accounts.
- Launched `mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" javafx:run`. Windows reported the real window title `GramaLink LK | Sign in`. The verification client and server were then closed so the packaged JAR remains unlocked for subsequent builds. PostgreSQL and all GRIDORA containers remain running.
- Nonfatal JavaFX model/shading resource warnings remain. An intermediate incremental run encountered stale generated test bytecode; a clean rebuild from source resolved discovery. No tests were removed, disabled or relaxed to obtain the passing result. UI fixtures were updated to supply the new mandatory title and the Admin fixture now includes the supported activation action.
- No two-device LAN test or manual interactive evaluation of every decision path was performed. Backend tests exercise reject/return and conflicts; client contract tests verify their mappings. This local evidence does not certify TLS, process-crash recovery or unsupported teammate operations.

## Historical U07/U08 report

# U07/U08 client networking and reliability

## Gap analysis

The client-side foundations for U07 and U08 are already implemented:

| Requirement area | Existing behavior |
| --- | --- |
| Role event dispatch | Citizen, GN, DS and Admin gateways receive messages from the login `NetworkClient`. Each routes ACK/ERROR separately and delegates business-event ownership and shape validation to its role protocol adapter before decoding. |
| UI threading and blocking work | Dashboard listeners use `Platform.runLater`; gateway work, socket I/O, heartbeat scheduling and reconnect attempts run off the JavaFX application thread. |
| UI state | Dashboards expose connection/error/loading states. Same-request drafts and selection are preserved on authoritative snapshots; switching or losing a request clears request-specific drafts. |
| Authorization and stale callbacks | Role access requires a correlated LOGIN response accepted by exactly one role adapter. Session epochs, listener identity and dashboard binding epochs reject obsolete callbacks. Request-level `FORBIDDEN` is reported without assuming global session revocation. |
| Heartbeat | `ClientReliability` sends correlated heartbeats only after a validated role adapter authorizes the session. It checks the server's `ACK` contract, deadlines missed replies, and uses bounded exponential reconnect backoff. Reconnection clears role access and requires a fresh sign-in; credentials and business commands are never replayed. |
| Transfer progress | `FileTransferProgress` requires a current authorized session and correlated `FILE_META/READY`; it validates transfer identity, direction, size, sequence and `TRANSPORT_RECEIVED` ACKs. Disconnects mark unfinished work incomplete. Progress is explicitly transport receipt, not durable storage. |

The implementation gaps are integration dependencies, not safe-to-guess client
behavior. All four production protocol adapters currently fail closed. As a result,
real dashboards cannot authorize or receive business updates, no request-loading
operation can be sent, and no production file transfer can be negotiated.

## Exact backend/server blockers

### Shadhir backend contracts not supplied

1. **Authentication and revocation:** correlated `LOGIN_OK` identity/role schema,
   session/token rules, expiry, logout and any explicit authorization-revocation
   event. The client must not treat a request-level `FORBIDDEN` as global revocation.
2. **Authoritative request loading:** agreed request/list/detail/history messages,
   fields, paging/snapshot semantics, ownership scope and refresh behavior after
   `CONFLICT` or `FORBIDDEN`. No GET/list/reload message is invented.
3. **Role event contracts:** complete `NEW_REQUEST`, `REQUEST_UPDATED`,
   `NOTIFICATION` and `CHAT_MESSAGE` schemas, event ownership/authorization,
   ordering, recipient scope and authoritative persistence/result semantics.
4. **Role operations:** agreed fields, validation errors, conflict/version rules
   and authoritative results for Citizen, GN, DS and Administrator actions.
5. **Files:** metadata fields, permitted destinations and limits, grant issuance,
   upload/download authorization, and application/durable-storage completion.

Until these contracts and production adapters exist, role operations and event
application remain disabled. Offline UI fixtures are test-only and are not an
authentication bypass.

### Samhi server behavior currently available

- The server accepts an authenticated heartbeat only for a session registered by
  its trusted `SessionManager` integration point. It returns a correlated `ACK`
  with `acknowledgedType: HEARTBEAT`; the client schedules this within the
  documented 35-second default server expiry.
- `LOGIN`, unauthenticated `HEARTBEAT`, and business messages receive
  `ERROR/NOT_IMPLEMENTED` where applicable. There is no production login route
  that creates a verified session.
- The default file service issues no grants. `FILE_META` cannot return
  `READY`; unauthenticated chunks are rejected, and there is no production
  application storage/durable-completion path.

Consequently, a real user cannot yet exercise an authenticated client/server
dashboard session or end-to-end file transfer. The authenticated-heartbeat socket
test installs a verified session using a test fixture only; it does not emulate
production credential verification.

## Verification

Run the complete Maven suite, including desktop JavaFX controller tests, from the
directory containing `pom.xml`:

```powershell
mvn "-Dgramalink.uiTests=true" test
```

The verified run completed **284 tests, 0 failures, 0 errors, 0 skipped**. Coverage
includes all four UI suites, role event dispatch and stale-session handling,
heartbeat success/timeout, reconnect without replay or inherited authorization,
transfer progress/incomplete-transfer behavior, and loopback tests against
Samhi's current server for authenticated heartbeat via a test-only verified
session and the server's currently unavailable unauthenticated contracts.

This verifies the supported client/server boundary, not real authentication,
business event/list loading, database persistence or durable file storage. No
server, shared protocol or backend files were modified.

## Current frontend change manifest

Includes preserved pre-existing authentication changes. No files have been staged or committed. Excludes the unrelated hboard artifact and ignored local configuration/build outputs.

- README.md
- docs/u02-citizen-integration.md
- docs/u03-gn-integration.md
- docs/u04-ds-integration.md
- docs/u05-admin-integration.md
- docs/u07-u08-client-integration.md
- src/main/java/lk/gramalink/client/AdminDashboardController.java
- src/main/java/lk/gramalink/client/AdminData.java
- src/main/java/lk/gramalink/client/AdminGateway.java
- src/main/java/lk/gramalink/client/AdminProtocol.java
- src/main/java/lk/gramalink/client/AdminWorkspace.java
- src/main/java/lk/gramalink/client/CitizenDashboardController.java
- src/main/java/lk/gramalink/client/CitizenData.java
- src/main/java/lk/gramalink/client/CitizenGateway.java
- src/main/java/lk/gramalink/client/CitizenProtocol.java
- src/main/java/lk/gramalink/client/CitizenWorkspace.java
- src/main/java/lk/gramalink/client/DsDashboardController.java
- src/main/java/lk/gramalink/client/DsGateway.java
- src/main/java/lk/gramalink/client/DsProtocol.java
- src/main/java/lk/gramalink/client/DsWorkspace.java
- src/main/java/lk/gramalink/client/GnDashboardController.java
- src/main/java/lk/gramalink/client/GnData.java
- src/main/java/lk/gramalink/client/GnGateway.java
- src/main/java/lk/gramalink/client/GnProtocol.java
- src/main/java/lk/gramalink/client/GnWorkspace.java
- src/main/java/lk/gramalink/client/LoginController.java
- src/main/java/lk/gramalink/client/LoginService.java
- src/main/resources/lk/gramalink/client/admin-dashboard.fxml
- src/main/resources/lk/gramalink/client/citizen-dashboard.fxml
- src/main/resources/lk/gramalink/client/ds-dashboard.fxml
- src/main/resources/lk/gramalink/client/gn-dashboard.fxml
- src/test/java/lk/gramalink/client/AdminGatewayTest.java
- src/test/java/lk/gramalink/client/CitizenDashboardUiTest.java
- src/test/java/lk/gramalink/client/LoginServiceTest.java
- src/test/java/lk/gramalink/client/U07UiBindingTest.java
- src/main/java/lk/gramalink/client/BackendCommands.java
- src/main/java/lk/gramalink/client/BackendLoginContract.java
- src/main/java/lk/gramalink/client/BackendQueries.java
- src/main/java/lk/gramalink/client/ClientFiles.java
- src/main/java/lk/gramalink/client/FileWorkspace.java
- src/test/java/lk/gramalink/client/BackendQueriesTest.java
- src/test/java/lk/gramalink/client/ClientFilesTest.java
- src/test/java/lk/gramalink/client/ProductionLoginAuthenticationTest.java
- src/test/java/lk/gramalink/client/ProductionWorkflowIntegrationTest.java
