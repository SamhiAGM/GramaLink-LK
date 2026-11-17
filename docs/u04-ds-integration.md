# U04 DS Officer dashboard

## UI-ready components

The DS workspace uses the existing Citizen theme plus ds-dashboard.css. It has
Dashboard, Forwarded Requests, Notifications and Officer Chat navigation.

- Overview: forwarded-request total and counts grouped by the exact supplied
  status text, notification count and recent activity derived only from loaded
  current-version request histories. Unknown totals are not displayed as zero.
- Inbox: request ID, citizen, GN office, request type, status, optional submission
  date (Sri Lanka time) and optional server revision. No date/version is invented.
- Details: description, originating GN officer, document metadata and chronological
  status history. Stale detail revisions are withheld until they match the inbox.
- Decisions: approve, reject with a mandatory reason, return to GN with a mandatory
  explanation. Check decision fields validates locally without sending anything.
- Request-specific officer chat, notifications, busy/error indicators and
  connection-loss locking. Switching requests clears drafts; updates to the same
  request preserve drafts and selection.

Document upload/download and live submissions are unavailable until the backend
contract exists. An ACK never changes status, revision, document state, history or
chat. Only decoded authoritative events replace those values.

## Authorization and existing frontend behavior

LoginService binds DsGateway to the same NetworkClient used for LOGIN, alongside
the existing Citizen and GN gateways. Only the correlation-matched LOGIN future
can call the DS authorization adapter. Unsolicited LOGIN_OK, guessed DS/GN/Citizen
roles, usernames or unverified tokens cannot unlock the real DS dashboard.

DsProtocol.pendingBackend authorizes nobody and supports no actions. The existing
server's ERROR/NOT_IMPLEMENTED login response therefore leaves all dashboards
closed. No production auth decoder has been fabricated. DsDashboardView requires a
concrete DsGateway and checks authorization before loading and before navigation;
the controller also checks workspace access. Ambiguous multi-workspace permissions
are denied by login routing. Disconnect, sign-out, replacement and close revoke DS
access. Citizen/GN production controllers, views, styles and NetworkClient are
unchanged, and their regression tests remain part of verification.

DsWorkspace is a package-local view port; the only production implementation is
DsGateway. The offline implementation lives only in test sources. Neither it nor
the preview launcher is referenced by ClientMain or packaged into the application.

## Protocol and request versions

DsData records and ActionKind are local view models/intents, not server payload
schemas, new message types or business state codes. The default encoder throws
unavailable; no approval/rejection/return payload has been guessed. A future
adapter may encode an agreed operation using STATUS_UPDATE or chat using
CHAT_MESSAGE. This boundary does not assert that today's server implements those
business operations. If an operation needs a coordinated protocol extension, it
must remain disabled until that extension is implemented by its owners.

Version is an opaque value supplied by the backend adapter. The client preserves
it exactly; it never invents, increments or numerically compares revisions. The
adapter's per-action requiresVersion rule must match the eventual backend contract.
The conservative default requires a revision for decisions; capabilities remain
disabled regardless until an actual adapter exists. Versions can be absent when
the backend supplies none; optional dates likewise display Not supplied.

For supported versioned actions the gateway checks:

1. Current DS authorization and a request present in the latest DS-scoped inbox.
2. A supplied version exactly matching that inbox revision before encoding and
   again before sending.
3. The encoder receives the selected expectedVersion and must serialize it using
   the **agreed** backend field name and JSON type. Neither is invented here.
4. The server must still atomically enforce authorization, permitted transition,
   current version/concurrency and durable persistence. Local checks are not proof
   that server data has not changed in transit.

REQUEST_UPDATED, NOTIFICATION, NEW_REQUEST and CHAT_MESSAGE are routed to the
future DS decoder on a worker. The decoder must validate event scope/ownership and
normalize data into a complete inbox snapshot or revision-bound details. The UI
uses Platform.runLater. No socket work blocks the FX thread.

CONFLICT and FORBIDDEN codes/messages are displayed verbatim as server errors;
statuses and revisions remain unchanged. Both gateway and UI block another action
for that request until a fresh authoritative inbox snapshot arrives. A timeout
reports an unconfirmed outcome and the UI requires refreshed data before retrying.
There is no automatic retry. No unsupported refresh/list message has been invented.
Version equality does not define event ordering: the backend must specify ordering,
snapshot scope and stale-event reconciliation before enabling live operations.

## Safe offline preview (Windows CMD)

```cmd
cd /d "C:\Users\Admin\Downloads\GramaLink-LK-main\GramaLink-LK"
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" -Pds-preview test-compile exec:java
```

The preview has a permanent OFFLINE DS PREVIEW / NOT SIGNED IN banner and a distinct
window title. All identities, requests, documents, states, messages and revisions
are explicitly fictional/PREVIEW. No NetworkClient or socket is created; no LOGIN,
decision, message or upload is sent and nothing is persisted. Actual submission
controls remain disabled. Navigate, inspect samples and use Check decision fields
to exercise validation. Select the second fictional request to inspect the missing
date/version behavior. Close the window to end the preview.

Add `-Dgramalink.previewSmoke=true` for a self-closing actual-window render check.
It writes target/u04-preview-0.png through -3.png and u04-preview-decisions.png.
This test-classpath launcher emits JavaFX's nonfatal unnamed-module warning.
The real application command remains `mvn javafx:run`; it has no preview override.

## Verification

```cmd
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" -Dgramalink.uiTests=true verify
```

The desktop flag runs all Citizen, GN and DS JavaFX control tests in addition to
the unit/socket suites. Tests cover FXML/navigation, mandatory reasons, missing and
opaque revisions, stale detail/inbox mismatch, encoder version propagation,
CONFLICT/FORBIDDEN presentation and retry blocking, ACK without persistence,
authoritative updates, request-scoped chat, loading, disconnect and role/correlation
isolation. Fixture encoders are not proposed real JSON payloads or real auth.
Omit the UI flag on a headless workstation; desktop UI tests will then be skipped.

## Remaining backend dependencies / before U05 integration

- Exact LOGIN_OK DS role/identity/session/token contract and production decoder.
- DS-scoped forwarded inbox/list/detail loading, paging/statistics and fresh-data
  retrieval after conflicts (there is no agreed list/refresh message).
- Decision payload fields, exact version representation/requirements, reason rules,
  allowed states, atomic concurrency checks, idempotency and durable audit/result
  events. ACK is transport-level evidence only.
- Revision-bound documents/history, originating GN identity, content download and
  upload authorization/storage.
- Real-time event schemas/order, assignment/recipient scoping, chat persistence and
  notification delivery semantics.
- Session expiry, authenticated heartbeats and reconnect/resynchronization behavior.
- End-to-end Citizen → GN → DS tests against the integrated backend, including
  competing officers, CONFLICT/FORBIDDEN, disconnect and committed outcomes.

U05's task definition is not present in the inspected repository. Its exact scope
must be confirmed before implementing it. UI-only work can continue with isolated
fixtures, but none of the above live workflows may be claimed complete until the
contracts, adapters and backend integration tests exist.
