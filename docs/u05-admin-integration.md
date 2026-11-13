# U05 Administrator dashboard

## UI-ready components

Six sections reuse the Citizen/GN/DS visual theme:

- Overview: explicit backend totals for connected clients, demo users, offices and
  requests, plus the backend snapshot timestamp. Unknown values remain unknown.
- Online Clients: connection, user, role, office, remote address, non-secret public
  session reference and optional connected/last-activity timestamps.
- User Management: listing, role/office catalogue choices, local draft validation,
  and create/update/disable controls restricted to server-identified demo accounts.
- Office Management: GN/DS type choices from the backend catalogue, local draft
  validation, and create/update/archive controls for server-identified demo offices.
- Request Statistics: exact backend-provided status labels and counts, supplied
  total, scope and as-of timestamp. Partial rows are never turned into global
  totals, percentages or invented business status categories.
- Audit Logs: read-only recorded timestamps, actors, activities, targets and
  outcomes. Newest known timestamps appear first; unknown timestamps remain unknown.

Connection loss locks management controls. All network/codec work runs on workers;
the controller dispatches incoming updates via Platform.runLater. Busy/error states
and sign-out are implemented. Updates preserve selection and unsubmitted drafts.

No table, overview count, statistic or audit entry is changed by a local draft,
successful socket write or ACK. Only authoritative typed updates replace them.
Server errors, including FORBIDDEN/CONFLICT, are displayed without claiming success.
Timeouts leave the outcome unconfirmed and do not trigger automatic retries.

## Authorization and networking boundaries

LoginService uses its existing NetworkClient/socket for the additional AdminGateway.
Only the correlation-matched LOGIN future can invoke administratorAuthorization.
The default AdminProtocol returns no identity and no capabilities. Claimed ADMIN,
ADMINISTRATOR, GN, DS or Citizen values, unverified tokens and unsolicited LOGIN_OK
messages cannot authorize the real workspace. Account-role draft choices cannot
change the logged-in identity. Ambiguous multi-workspace login routing is denied.

AdminDashboardView requires a concrete authorized AdminGateway before loading and
before navigation. The controller separately checks access. Disconnect, sign-out,
connection replacement and shutdown revoke access and clear gateway record caches.
There is no production preview flag or local Administrator selector.

Today's MessageType has **no administrative CRUD/reporting messages**. No new
message, payload schema or production encoder was invented. AdminProtocol defaults
to empty inbound/outbound type sets as well as unavailable operations. It must not
repurpose CREATE_REQUEST or STATUS_UPDATE as undocumented administrative commands.
A future adapter must implement an explicitly agreed capability, wire-type set,
encoder and decoder only after coordination with Samhi and Shadhir. The gateway
rejects an undeclared operation and rejects login/heartbeat/file/response envelopes
as administrative commands. Fixture encoders used by tests are transport stand-ins,
not a proposed administrative API.

Backend event decoders must validate authorization, scope, redaction and complete
payload shapes before producing AdminData models. Session references in models
must be public/non-secret display identifiers, never authentication bearer tokens.
No password provisioning or fake authentication is implemented. All final demo
flags, permissions, role assignments, state transitions and persistence remain the
server's responsibility; local demo/catalogue checks are only additional guards.

## Exact Windows CMD preview command

```cmd
cd /d "C:\Users\Admin\Downloads\GramaLink-LK-main\GramaLink-LK"
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" -Padmin-preview test-compile exec:java
```

The explicit profile runs AdminDashboardPreview from **test sources**, not
ClientMain. OfflineAdminWorkspace creates no NetworkClient/socket, sends no LOGIN
or administrative command and persists nothing. Its permanent amber banner and
window title say OFFLINE ADMIN PREVIEW / NOT SIGNED IN. Every identity, session,
account, office, audit record, state and total is fictional/PREVIEW. Fictional
totals deliberately differ from partial table sizes to exercise reporting safety.

Navigate the six sections, inspect the fictional tables, select records and use
Check user fields / Check office fields to exercise validation. Real submission
controls stay disabled. Close the window or use Sign out to end the preview. Test
fixtures and preview launchers are excluded from the production application JAR
and have no link from the real login workflow.

Add `-Dgramalink.previewSmoke=true` for a self-closing actual-window render check.
Snapshots are target/u05-preview-0.png through -5.png plus
u05-preview-user-form.png and u05-preview-office-form.png. The test-classpath JavaFX
launcher emits a nonfatal unnamed-module warning. Existing preview profiles and
the normal `mvn javafx:run` login application remain available.

## Verification

```cmd
mvn "-Dmaven.repo.local=%TEMP%\gramalink-u06-m2" -Dgramalink.uiTests=true verify
```

This compiles/packages production code and runs all Citizen/GN/DS/Admin desktop UI
tests alongside unit and socket tests. Without the UI flag, only desktop-dependent
tests are skipped. No TestFX/browser test dependency was added.

Admin tests cover FXML/navigation, tables and missing metadata, unknown versus
explicit counts, partial statistics, newest-first audit records, local validation,
demo-only management scope, catalogue validation, loading/error/sign-out behavior,
draft preservation, no optimistic state/audit updates after ACK, role/correlation
isolation, same-socket event delivery, malformed events and disconnect revocation.
None of these fixtures establishes completed backend administration or real auth.

## Remaining backend integration dependencies

1. Exact Administrator LOGIN_OK identity/role/session/token/permission contract,
   expiry/revocation and authenticated heartbeat/reconnect behavior.
2. Authorized online-client reporting API, semantics of TCP connections versus
   authenticated sessions, scope, sampling time and public/redacted identifiers.
3. User/office list, filter, paging and refresh APIs, explicit demo markers,
   canonical role and GN/DS office catalogues, and assignment permission rules.
4. Agreed administrative create/update/disable/archive message types and payloads,
   credential provisioning, null/partial-field semantics, validation/reason rules,
   concurrency/version/idempotency rules and durable result events.
5. Reporting schemas for scope, totals, exact request-status counts, unknown values
   and snapshot ordering/freshness. No local table can substitute for these totals.
6. Audit retrieval/event contracts, timestamp semantics, actor/target/outcome
   fields, immutability, pagination and server-side redaction of sensitive data.
7. Authoritative error schemas and live cross-role/permission tests, including
   non-admin denial, non-demo record rejection, concurrency conflicts, disconnects
   and committed changes recorded by the backend audit service.

## U05 file inventory

Modified existing files:

- pom.xml (explicit test-only admin-preview profile)
- src/main/java/lk/gramalink/client/LoginService.java
- src/main/java/lk/gramalink/client/LoginController.java
- src/test/java/lk/gramalink/client/LoginServiceTest.java

New files:

- src/main/java/lk/gramalink/client/AdminData.java
- src/main/java/lk/gramalink/client/AdminProtocol.java
- src/main/java/lk/gramalink/client/AdminWorkspace.java
- src/main/java/lk/gramalink/client/AdminGateway.java
- src/main/java/lk/gramalink/client/AdminDashboardView.java
- src/main/java/lk/gramalink/client/AdminDashboardController.java
- src/main/resources/lk/gramalink/client/admin-dashboard.fxml
- src/main/resources/lk/gramalink/client/admin-dashboard.css
- src/test/java/lk/gramalink/client/OfflineAdminWorkspace.java
- src/test/java/lk/gramalink/client/AdminDashboardPreview.java
- src/test/java/lk/gramalink/client/AdminGatewayTest.java
- src/test/java/lk/gramalink/client/AdminDashboardUiTest.java
- docs/u05-admin-integration.md

The pre-existing hboard… artifact and generated target/ files are not U05 source.
No server/shared networking/backend files or existing dashboard views/styles were
changed, and no commits, pushes, merges or deletions are part of this task.
