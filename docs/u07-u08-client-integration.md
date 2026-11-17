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
