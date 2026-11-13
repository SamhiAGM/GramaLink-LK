# Backend service and TCP integration contract

## Ownership and trust

Samhi owns socket framing, session mapping, heartbeat monitoring, canonical connection writers and event routing. Shadhir owns credential verification, authorization, transactions, storage and persistence. Ahamad consumes these contracts in JavaFX and must run network reads away from the UI thread.

The server constructs an `Actor` exclusively from `SessionManager.findSession(socket)`. Public Java service methods are trusted **server-side** APIs; callers must not construct actors from unverified client IDs. Each service reloads the actor's active account and canonical role/office/division from PostgreSQL. Client `role`, `userId` and `token` claims never confer authority. No bearer-token authentication is implemented; reconnect requires a new successful LOGIN. User role/office reassignment is deliberately not offered in this version.

## Framing and replies

Keep the existing four-byte big-endian length + UTF-8 JSON envelope. JSON maximum is 1 MiB. Binary chunks retain Samhi's documented typed extension and maximum 64 KiB data size. Never create a second output wrapper on the server socket. `correlationId` is a message ID; `requestId` is the persistent public UUID. They are unrelated.

```json
{"type":"LOGIN","correlationId":"login-1","payload":{"username":"gn.demo","password":"DemoPass123!"}}
```

Successful login returns:

```json
{"type":"LOGIN_OK","correlationId":"login-1","payload":{"operation":"LOGIN","data":{"userId":"20000000-0000-0000-0000-000000000002","role":"GN"}}}
```

Other successful service commands return `ACK` with the original correlation ID and payload `{"operation":"<COMMAND>","data":<result>}`. Login identity, UUIDs, typed requests, arrays, and admin result objects are under `data`. Request records use camelCase (`id`, `citizenId`, `gnId`, `dsId`, `gnOfficeId`, `dsOfficeId`, `division`, `type`, `title`, `description`, `status`, `version`). Raw database query rows use the schema's snake_case names. UUIDs serialize as strings; timestamp columns in query rows serialize as timestamp strings.

Errors have payload `{"code":"CONFLICT","message":"Request changed. Reload it before retrying."}` and the original correlation ID. Codes: `VALIDATION_ERROR`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `INVALID_TRANSITION`, `CONFLICT`, `INTERNAL_ERROR`. Existing transport-specific file errors remain unchanged. File transport deliberately returns generic `FILE_TRANSFER_REJECTED` / `FILE_TRANSFER_FAILED` for backend validation failures; validation details are not exposed through that transport boundary.

Push types are `NEW_REQUEST`, `REQUEST_UPDATED`, `CHAT_MESSAGE`, and `NOTIFICATION`. Their payload is `{"data":<committed record or refresh hint>}` with no client-command correlation ID. Events may arrive **before the command ACK**, including on the submitting socket. Correlate replies and process events independently. Notifications are durable; a NOTIFICATION push signals the client to retrieve its own rows. Re-fetch authoritative state after reconnect or conflict. Do not blindly apply an older pushed version over a newer request version.

All three assigned request participants are authorized event recipients, including the assigned DS; its action queue remains limited to forwarded/returned/completed decisions. Admin receives no global broadcast; it uses authorized queries/statistics. Inactive accounts are filtered before event delivery. Offline or failed event delivery does not undo a committed request. Delivery is best effort, with durable query recovery, not exactly-once or an outbox dispatcher.

## Service operations

| Command | Payload fields | Result / permissions |
|---|---|---|
| `LOGIN` | username, password | LOGIN_OK; reconnect before changing identity |
| `ME` | none | Current active user; no password hash |
| `CREATE_REQUEST` | type, title, description, division | Request; citizen's own division, configured active GN/DS |
| `STATUS_UPDATE` | requestId, expectedVersion, action; reason/title/description as below | Updated request; assigned officer or owning citizen |
| `GET_REQUEST` | requestId | Assigned participant or Admin |
| `LIST_REQUESTS` | offset (optional) | 50 records; own/assigned scope; DS decision queue |
| `GET_HISTORY` | requestId, offset (optional) | 100 history rows; request access required |
| `CHAT_MESSAGE` | requestId, body | Durable message; assigned participant; Admin cannot send |
| `GET_CHAT` | requestId, offset (optional) | 50 messages, chronological; request access required |
| `GET_NOTIFICATIONS` | afterId (optional, default 0) | 100 own notifications, ascending ID |
| `READ_NOTIFICATION` | notificationId | Marks only own notification read |
| `LIST_DOCUMENTS` | requestId, offset (optional) | 100 metadata rows, excluding private storage paths |
| `LIST_OFFICES` | offset (optional) | 100 office records; any active user |
| `LIST_USERS` | offset (optional) | 100 public account records; Admin |
| `CREATE_USER` | username, password, role, division, officeId (officers only) | New UUID; Admin; office kind/division must match role |
| `SET_USER_ACTIVE` | userId, active | Admin; cannot disable self/Admin or accounts with active assignments |
| `CREATE_OFFICE` | name, kind, division, parentId (GN only) | New UUID; Admin; GN requires a DS parent |
| `RENAME_OFFICE` | officeId, name | Admin; structural reassignment is not supported |
| `GET_STATISTICS` | none | Requests by status, users by role, live session-manager connection count; Admin |
| `GET_AUDIT` | afterId (optional) | 100 audit rows; Admin |
| `HEARTBEAT` | none | Existing network ACK; send every 10 seconds after login |

For paged arrays, request the next offset until a shorter page is returned. Request lists sort newest first, history by version, chat chronologically, documents chronologically and offices by division/name/id. Offset pagination is a demo mechanism, not a stable snapshot during concurrent insertions. Notifications/audit use ID cursors; persist the highest ID successfully processed.

Request type: `RESIDENCE`, `INCOME`, `CHARACTER`, `OTHER`. Title maximum 160 characters, description 5000, chat body 4000, reason 1000. IDs must be UUIDs; numeric fields must be nonnegative integers. New passwords require 10+ characters and at most 72 UTF-8 bytes (BCrypt's bound).

## Workflow and transaction semantics

See [the explicit transition table](backend-implementation-plan.md). Action names: `RECEIVE`, `REVIEW`, `VERIFY`, `REQUEST_CORRECTION`, `RESUBMIT`, `FORWARD`, `RETURN`, `APPROVE`, `REJECT`. Correction/return/reject require `reason`. RESUBMIT requires replacement `title` and `description`. Admin cannot mutate request status. All officer actions require both assigned user ID and matching office; GN also requires the request division. Citizen access requires ownership and division.

`RequestService.create/change` returns `Models.Result<Request>`; `NotificationService.sendChat` returns `Models.Result<Map<String,Object>>`. The result includes typed post-commit events with authorized recipient IDs. These methods return only **after commit**. Each operation opens its own JDBC connection; all write repositories, request history, audit records and notifications share that transaction connection. Request updates use `WHERE id=? AND version=?`; zero updated rows means CONFLICT. A losing race emits no success event and writes no partial history/notification. Denied actions are audited in a separate transaction after rollback; if PostgreSQL itself is down, the rejection audit can only be logged as unavailable.

`AuthService.login` verifies BCrypt and commits a login outcome, then returns `VerifiedIdentity` for the handler's session registration. `AdminService` receives an `IntSupplier` from the live session manager; database rows are never used as network-presence evidence.

## File integration

The backend implementation is `server.service.FileTransferService`, implementing Samhi's existing `server.FileTransferService` interface. They are intentionally separate types. Constructor settings configure private storage, maximum byte count and the allowed MIME set (currently only `application/pdf`).

Upload FILE_META payload:

```json
{"direction":"UPLOAD","requestId":"<request-uuid>","fileName":"demo.pdf","mediaType":"application/pdf","totalBytes":1234,"sha256":"<64 lowercase hex characters>"}
```

`authorize(session, metadata)` validates active authenticated session, request access, nonterminal workflow, simple filename, extension, MIME, byte limit and checksum syntax. It returns a fresh connection-bound grant. Admin cannot upload. The transport sends READY and accepts raw binary chunks only. The backend incrementally writes a private `.part` file, verifies byte count/sequence and SHA-256, parses the PDF with PDFBox, rejects encryption, invalid page counts, annotations and catalog active/embedded content, atomically moves the file and commits metadata/audit. This is a constrained **plain demo PDF** validator, not a malware scanner or legally valid document validator.

The final transport ACK still says `stage: TRANSPORT_RECEIVED`. The handler then calls `completion` and sends a separate `FILE_META` payload `{"status":"STORED","transferId":"...","documentId":"...","sha256":"..."}`. Only STORED or a subsequent LIST_DOCUMENTS result establishes application-level completion. The upload document ID equals its transfer UUID; downloads use a fresh grant UUID distinct from the document ID. Never interpret transport ACK alone as durable completion.

Download FILE_META uses `{"direction":"DOWNLOAD","documentId":"<document-uuid>"}`. After READY, the handler opens the authorized backend stream, verifies stored size/hash and sends typed chunks using `FileTransferTransport.sendChunk`. A virtual download worker allows the read loop to keep processing heartbeats. The backend stream closes on completion/failure. No raw storage path is sent to clients.

`abort(session, grant)` is idempotent and deletes incomplete temporary files. The transport invokes it on protocol failure, disconnect or failed download. Database/storage failures during completion remove the temporary/final file where possible and do not publish incomplete metadata. Successful documents are retained on disconnect. Network interruption after commit may lose STORED; recover through LIST_DOCUMENTS rather than blindly repeating uploads. No resumable uploads, automatic retry/idempotency key, or process-crash cleanup daemon is implemented. See README backup/reconciliation procedures.

## Compatibility impact

- Existing message names/framing/binary layout are unchanged. New query/admin message names are additive; older clients should only send names they support.
- Existing network-only constructors retain NOT_IMPLEMENTED fixtures. The application entry point now requires a configured database and uses BackendDispatcher.
- File service adds default optional completion/download callbacks, preserving old test adapters.
- The handler adds dispatcher injection and authorized asynchronous download startup; it continues using the existing manager-owned writer.
- JavaFX must handle the new service payloads, pushed events before ACK, file STORED, conflict/reload, pagination, and re-login/refresh after reconnect. No JavaFX screens were added in this module.
