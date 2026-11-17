# U02 Citizen workspace integration

The FXML workspace includes seven sections: Dashboard, New Request, My Requests,
Notifications, Documents, Messages and Profile. It includes local draft validation,
attachment selection, requests table, details, ordered status history, request
conversations, notifications, a read-only identity profile, busy indicators and
connection/error feedback. Production contains no demo rows or fabricated status
transitions. Unloaded counts remain unknown rather than zero. Counts, table
selection and request conversations update together from typed server events;
incoming updates preserve an unsent message draft on the same request.

## Authorization and navigation

`CitizenDashboardView.open` and controller attachment require a live authorized
`CitizenGateway`. `LoginService` passes only a correlation-matched LOGIN reply to
the gateway. Unsolicited LOGIN_OK messages never authorize access. The default
`CitizenProtocol.pendingBackend()` authorizes nobody, including payloads claiming
Citizen, user IDs or tokens. The existing server returns ERROR/NOT_IMPLEMENTED;
the application therefore remains on the login screen.

The real application has no preview switch, local role selector or public identity
setter. A future production adapter must decode the backend's agreed LOGIN_OK
contract and confirm Citizen authorization for the current connection. Connection
loss, login replacement and sign-out revoke local access. The backend must still
authorize every individual operation and recipient.

## Protocol boundary

`CitizenData` records are local view models, not JSON schemas. `CitizenProtocol`
owns future encoders/decoders. No application payload field names or new message
types have been introduced for U02. CREATE_REQUEST and CHAT_MESSAGE are the only
outbound business operations in the gateway; their encoders are unavailable by
default. NEW_REQUEST, REQUEST_UPDATED, CHAT_MESSAGE and NOTIFICATION may be passed
to a future adapter on the receiving login socket. An ACK never creates a local
request or message, changes a status, or implies durable storage.

The following contracts are still needed from Shadhir, coordinated with Samhi:

- LOGIN_OK: canonical identity, Citizen role, session/token requirements and expiry.
- Request types and GN divisions: canonical catalogues and validation rules.
- CREATE_REQUEST: fields, validation errors and authoritative result/events.
- Request list/history/details loading: there is no agreed list/detail request
  message in MessageType. No invented GET_REQUESTS or equivalent is sent.
- NEW_REQUEST/REQUEST_UPDATED: IDs, dates, status values, history and ownership.
- NOTIFICATION: identity, timestamp, text, request association, read-state rules.
- CHAT_MESSAGE: request binding, sender, timestamp and persistence confirmation.
- Attachment FILE_META request fields, ownership, destination, permitted file
  limits/types, authorized READY and durable completion rules.
- Authenticated heartbeat integration for a future live authorized workspace.
- Document history/downloads and additional profile data/editing: no contracts
  exist, so these are explicitly unavailable and no guessed fields are shown.

## Attachments and threads

File selection is local only; file checks and metadata reads run on the citizen
worker. Files are not read into memory or uploaded. Submit is blocked while an
attachment is selected, to prevent silently dropping it. Upload remains disabled
until authorization/metadata integration exists. The progress interface accepts
bounded byte counts from a future adapter; TRANSPORT_RECEIVED must never be
described as durable storage. Binary transport remains Samhi's existing codec.

Socket connect/send/disconnect, encoding/decoding and filesystem work stay off the
FX thread. Gateway updates enter controller controls through Platform.runLater.
Late updates after sign-out are ignored. No server, shared or backend files change.

`CitizenWorkspace` is a package-local UI port. The only production implementation
is CitizenGateway; production navigation still accepts that concrete gateway and
requires server-confirmed Citizen access. The separate offline fixture lives only
in test sources and cannot be supplied by the normal login screen.

## Safe offline preview

From the directory containing pom.xml, run:

```powershell
mvn "-Dmaven.repo.local=$env:TEMP\gramalink-u06-m2" -Pcitizen-preview test-compile exec:java
```

This explicit profile runs `src/test/.../CitizenDashboardPreview.java`. It does
not run ClientMain, construct NetworkClient, send LOGIN, authenticate anyone or
persist anything. Its permanent amber banner and window title identify the
offline preview; fictional rows are marked PREVIEW and contain sample statuses.
Submission, messaging and upload remain disabled. You can navigate all sections,
check required-field validation, select a local attachment, inspect sample details
and switch request conversations. Close the window to end the preview.

The preview launcher and OfflineCitizenWorkspace are excluded from the application
JAR. No production authentication override was added. Normal startup remains
`mvn javafx:run` and keeps the dashboard closed while authentication is unavailable.

For a self-closing render smoke check (snapshots in target/u02-preview-0.png through
target/u02-preview-6.png), add `-Dgramalink.previewSmoke=true` to the preview command.
The classpath-only test launcher may emit JavaFX's unnamed-module warning; it does
not affect the normal JavaFX Maven launcher.

## Verification

Run Maven verification using a writable dependency cache if this workstation's
default C:\.m2 cache is unavailable:

```powershell
mvn "-Dmaven.repo.local=$env:TEMP\gramalink-u06-m2" verify
```

Normal verification runs the unit/socket suite and skips the six desktop-only
UI tests. To run those actual FXML/controller tests on a desktop workstation:

```powershell
mvn "-Dmaven.repo.local=$env:TEMP\gramalink-u06-m2" -Dgramalink.uiTests=true verify
```

These tests use only JUnit and JavaFX (no TestFX or browser dependencies). They
exercise FXML loading, all seven navigation actions, required-field validation,
unknown versus loaded-empty table state, ordered history, request-scoped
conversations, selection/draft preservation, loading/progress controls, no
optimistic persistence on ACK, timeout feedback and connection-loss locking.
The fixtures do not claim complete authentication, persistence or file transfer.
