# U03 GN Officer workspace

## UI-ready components

- Dashboard: current assignment count, counts grouped by the exact supplied status
  text and officer-relevant notifications. Unknown counts remain unknown until a
  complete assignment snapshot arrives; no business status codes are invented.
- Assigned Requests: ID, citizen, type, Sri Lanka submission date/time, status and
  optional priority. Missing priority is displayed as "Not supplied".
- Details: description, supporting-document metadata/verification state and
  chronologically sorted history. Document contents/downloads are unavailable.
- Officer controls: verify a selected document, request more information, forward
  to DS and reject with a reason. "Check action fields" validates locally without
  sending or changing any state. Rejection and more-information actions require a
  note; document verification requires a selected document; forwarding notes are
  optional client-side drafts pending final backend rules.
- Request-specific chat: selection stays synchronized with the assignments table.
  Updates preserve unsent notes/chat for the same request; changing request clears
  them to prevent accidentally messaging a different citizen.
- Notifications, connection/error feedback, busy indicators and disconnect locking.

The view reuses citizen-dashboard.css with a small gn-dashboard.css extension.
Citizen production views and styles have not changed. Login gained only additional
guarded GN routing; its current unauthenticated and Citizen behavior is preserved.
Citizen/GN UI tests share one test toolkit so neither shuts down JavaFX before the
other suite runs. No new UI-test library is required.

## Authorization and TCP integration

`GnDashboardView.open` accepts only GnGateway and checks GN authorization before
loading and before navigation. The controller also checks its workspace permission.
There is no role selector, username heuristic or production preview override.

LoginService binds GnGateway to the same NetworkClient used for login. Only the
correlation-matched LOGIN future passes its response to GnProtocol.gnAuthorization;
unsolicited LOGIN_OK messages cannot authorize it. The default pending adapter
returns no identity, including for guessed GN/Citizen/DS claims or unverified
tokens. Ambiguous Citizen/GN routing is denied rather than silently selecting a
workspace. Disconnect, connection replacement, sign-out and shutdown revoke access.

The real server still rejects LOGIN with ERROR/NOT_IMPLEMENTED. Consequently the
real GN workspace remains inaccessible until Shadhir supplies the agreed identity
and role contract and a production decoder is implemented.

GnData records and ActionKind values are local view models/intents, not new TCP
message types, JSON schemas or server statuses. All protocol capabilities default
to unavailable. GnProtocol.encode must only be implemented after backend agreement.
The gateway permits CHAT_MESSAGE for chat and generic STATUS_UPDATE as a future
officer-action integration boundary; this does **not** claim that document
verification, rejection, forwarding or more-information semantics are implemented
by STATUS_UPDATE today. If the agreed operation cannot use an existing type, it
must remain disabled until a coordinated protocol change is implemented.

NEW_REQUEST, REQUEST_UPDATED, CHAT_MESSAGE and NOTIFICATION can reach a future GN
decoder on the authenticated login connection. Encoders/decoders and socket work
run off the FX thread. Controllers dispatch incoming updates via Platform.runLater.
Backend adapters must validate assignment ownership, event scope and payload shape.

No request status, document verification state, history or chat entry changes from
a successful TCP write, ACK or local action. Only authoritative decoded updates
replace view data. A timeout leaves the outcome unconfirmed, not failed/successful;
the officer must check current server data before retrying a decision.

## Missing backend contracts

The following must be agreed with Shadhir and Samhi:

1. LOGIN_OK canonical identity, exact GN role representation, session/token binding,
   permissions, expiry and authenticated heartbeat behavior.
2. Assigned-request list/details/history loading and full versus paged counts.
   No unsupported GET_ASSIGNED_REQUESTS or similar message has been invented.
3. Citizen display values, timestamps, request types, canonical statuses, optional
   priorities and GN division/assignment filtering.
4. Document IDs, metadata, download authorization, content transfer and verification
   permission, outcomes, evidence/notes and durable audit history.
5. More-information, DS-forward and rejection payloads, permitted state transitions,
   reason rules, assignment scope, audit records and authoritative result events.
6. Duplicate action/conflict handling, concurrency/version rules and retry safety.
7. Request-scoped chat and notification schemas, recipient authorization, timestamps
   and durable delivery/read-state semantics.

## Safe offline preview

Run from the project directory containing pom.xml (PowerShell):

```powershell
mvn "-Dmaven.repo.local=$env:TEMP\gramalink-u06-m2" -Pgn-preview test-compile exec:java
```

The GN preview and OfflineGnWorkspace exist only in src/test. They open no socket,
send no LOGIN, authenticate nobody, access no real documents and persist nothing.
A permanent amber banner/title says OFFLINE GN UI PREVIEW / NOT SIGNED IN. All data
is marked fictional/PREVIEW, including sample priority/status values. Actual
submission controls remain disabled. You can inspect assigned requests/history,
select a fictional document, check action reasons and switch chat conversations.
Close the window to end the preview. It is not reachable through ClientMain.

For a self-closing render check add `-Dgramalink.previewSmoke=true`. Snapshots are
written to target/u03-preview-0.png through -3.png and u03-preview-actions.png.
The test-classpath launcher emits JavaFX's nonfatal unnamed-module warning.

The existing citizen-preview profile remains available and unchanged. Both preview
launchers, offline fixtures and UI tests are excluded from the application JAR.

## Verification

```powershell
mvn "-Dmaven.repo.local=$env:TEMP\gramalink-u06-m2" -Dgramalink.uiTests=true verify
```

This compiles/packages production code and runs all unit/socket plus Citizen/GN
desktop UI tests. Omit gramalink.uiTests on a headless workstation to skip only UI
tests. Tests cover role/route denial, correlation, socket framing, unsupported
actions, malformed/late events, action validation, document/assignment/chat state,
no optimistic updates after ACK, busy/error states and disconnect revocation.
Adapter fixtures are test-only integration stand-ins, not a real auth/backend
implementation or proposed JSON schemas. Tests do not establish completed officer
workflows, document storage or database-backed authentication.
