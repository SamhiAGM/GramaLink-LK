# N08 Network Testing Report

**Project:** GramaLink LK
**Prepared by:** Samhi - Networking Module
**Branch:** `feat/tcp-server-samhi`
**Test scope:** Localhost / loopback networking

## 1. Introduction and objective

This report documents the recorded N08 networking test results for GramaLink LK. The objective was to evaluate TCP connection handling, four-byte length-prefixed JSON communication, concurrent client handling, and the rejection of heartbeat requests from unauthenticated connections.

The evaluation combines recorded Maven verification results, the existing automated networking tests, and Wireshark observations from a local client-server exchange. All communication results reported here relate to localhost / loopback testing on one computer. A real two-device LAN test has not been completed.

## 2. Networking modules tested (N01-N08)

The following table describes the N01-N08 testing scope using descriptive responsibility labels for this report. It distinguishes networking component coverage from integrations that remain pending.

| Module | Networking responsibility | Components and testing scope |
| --- | --- | --- |
| N01 | TCP server and listener | `ServerMain` and `NetworkServer`: server startup, listening, connection acceptance, and shutdown. |
| N02 | Concurrent client handling | `ClientHandler` and the server worker pool: independent connection processing, additional connection acceptance, and cleanup. |
| N03 | JSON message protocol | `Message`, `MessageType`, and `JsonUtil`: message encoding, decoding, correlation IDs, and four-byte length-prefixed framing. |
| N04 | Session management | `SessionManager`: connection-associated session state, registration boundaries, and removal after disconnect or failure. Verified identities in automated tests are test fixtures. |
| N05 | Event routing | `EventRouter` and `ConnectionWriter`: session-targeted delivery and shared per-connection writes, exercised through automated component tests. |
| N06 | Heartbeat and session lifecycle | `HeartbeatMonitor` and heartbeat handling: unauthenticated rejection, plus refresh and expiry checks using test-created verified sessions. |
| N07 | File transport | `TransportCodec`, `FileTransferTransport`, and related tests: JSON/binary framing and transfer authorization boundaries. Live backend authorization and storage integration remain pending. |
| N08 | Network verification and concurrency | `NetworkConcurrencyTest`, recorded Maven results, and the Wireshark demonstration: simultaneous clients, listener responsiveness, TCP exchange, and protocol observations. |

The manual Wireshark demonstration covered a JSON heartbeat exchange. It did not demonstrate authenticated event routing or an integrated file-transfer workflow.

## 3. Testing environment

| Item | Recorded environment or configuration |
| --- | --- |
| Java | Java 21 |
| Build and verification tool | Maven |
| Automated test framework | JUnit 5 |
| Transport | TCP sockets |
| JSON framing | Four-byte, big-endian length prefix followed by UTF-8 JSON bytes |
| Network scope | Localhost / loopback on a single computer |
| Demonstration client address | `127.0.0.1` |
| Demonstration server address | `127.0.0.1` |
| Demonstration server listening port | TCP `11849` |
| Packet inspection tool | Wireshark |
| Capture evidence filename | `GramaLink_N08_TCP_JSON_Test.pcapng` |
| Screenshot evidence filename | `N08_JSON_Communication.png` |

Port `11849` identifies the recorded manual demonstration. The automated concurrency fixture binds a loopback listener to an operating-system-assigned port, so the manual demonstration port should not be assumed for every automated test.

## 4. Automated concurrency testing

The recorded Maven verification completed with **BUILD SUCCESS**. The overall test suite recorded **163 passed, 0 failures, and 0 errors**. This is the project suite total; it is not a count of 163 concurrency tests.

The N08 concurrency test verified **20 simultaneous clients** and a **responsive 21st connection** while the original clients remained active. The clients exchanged framed heartbeat messages and checked the expected replies. This demonstrates that the listener continued serving an additional connection while existing handlers were occupied.

The existing automated concurrency coverage also exercises disconnect and malformed-JSON isolation, heartbeat expiry with test-created verified sessions, concurrent JSON/binary writes, worker reuse, and shutdown cleanup. These checks use bounded loopback fixtures. Session-dependent scenarios use a test-only stand-in for future backend identity verification, rather than a completed login integration.

Loopback connection/message checks recorded **approximately 40 ms**. This is an observed elapsed time for the local checks, not LAN latency or a measured network round-trip time. It does not establish a performance guarantee, maximum connection capacity, or sustained throughput.

## 5. TCP connection testing

For the recorded manual demonstration, the server listened on TCP port `11849`, and both endpoints used `127.0.0.1`. Wireshark captured the TCP **SYN**, **SYN-ACK**, and **ACK** packets associated with connection establishment. Application data was then exchanged in both directions, and connection closing packets were also captured.

These observations confirm a TCP client-server connection and bidirectional communication through the local loopback interface. They do not verify communication between separate devices, LAN routing, or firewall behavior between hosts.

## 6. Four-byte length-prefixed JSON protocol testing

The JSON protocol places a four-byte length header before each UTF-8 JSON payload. The header stores the JSON byte count in big-endian order; it excludes the four header bytes. A receiver reads the header and then the specified number of payload bytes before decoding the JSON message.

The recorded exchange contained:

| Field | Recorded value |
| --- | --- |
| Request message type | `HEARTBEAT` |
| Request correlation ID | `n08-demo-001` |
| Response message type | `ERROR` |
| Response error code | `NOT_IMPLEMENTED` |

The response was expected because authentication is not yet integrated and the connection had no authenticated session. Receiving an application-level error confirms that the server received and processed the request sufficiently to return a framed JSON response. The result verifies the unauthenticated rejection path rather than a successful authenticated heartbeat.

The existing `JsonUtilTest` coverage includes four-byte big-endian framing, UTF-8 byte lengths, delivery one byte at a time, consecutive frames, invalid lengths, malformed JSON, and truncated input. These automated checks complement the captured exchange by exercising framing conditions that one manual request cannot cover.

## 7. Wireshark packet capture observations

The recorded capture contained **four application-data TCP segments** for the demonstrated exchange:

| Direction | Observed application data | TCP payload length |
| --- | --- | ---: |
| Client to server | Client length header | 4 bytes |
| Client to server | Client JSON request | 64 bytes |
| Server to client | Server length header | 4 bytes |
| Server to client | Server JSON response | 178 bytes |

These byte counts describe application data carried in the TCP segments, not complete packet sizes including protocol headers. The four data-bearing segments are separate from the captured connection establishment and closing packets.

The Wireshark TCP stream view showed the actual JSON request and response, supporting inspection of the `HEARTBEAT` request and the `ERROR / NOT_IMPLEMENTED` response alongside the framing observations.

The separation of headers and JSON bodies into four segments was an observation of this capture. TCP is a byte stream and does not guarantee that application headers and bodies will always occupy separate segments. Correct framing must therefore tolerate both split and combined deliveries.

The evidence is identified by the filenames `GramaLink_N08_TCP_JSON_Test.pcapng` and `N08_JSON_Communication.png`. No storage paths are asserted, and the raw capture is referenced as evaluation evidence rather than added to Git as part of this report.

## 8. Session security observations

The demonstrated unauthenticated `HEARTBEAT` request received `ERROR / NOT_IMPLEMENTED`, as expected while authentication integration remains pending. Establishing a TCP connection and sending a heartbeat did not constitute successful authentication.

The automated session tests exercise rejection of client-supplied login claims and confirm that unauthenticated heartbeat messages do not create authenticated sessions. Other tests directly register verified identities as controlled fixtures to exercise session lifecycle, routing, and heartbeat behavior. Those fixtures do not demonstrate real credential validation or database-backed login.

The TCP stream displayed readable JSON. This demonstration therefore does not establish transport confidentiality. The observed rejection and automated session-boundary checks support the tested behavior only; they do not establish completed authentication, database integration, or comprehensive system security.

## 9. Testing results table

| Test or observation | Recorded result | Evaluation |
| --- | --- | --- |
| Maven verification | `BUILD SUCCESS` | Passed for the recorded verification run. |
| Overall automated test suite | 163 passed, 0 failures, 0 errors | Passed; project suite total. |
| Simultaneous clients | 20 clients verified | Passed within the loopback test scope. |
| Additional connection | Responsive 21st connection verified | Passed; listener remained responsive in the tested scenario. |
| Local connection/message timing | Approximately 40 ms | Recorded loopback observation; not a LAN latency benchmark. |
| Manual TCP endpoint | `127.0.0.1:11849` | Localhost server connection verified. |
| TCP connection lifecycle | SYN, SYN-ACK, ACK, and connection closing packets captured | Establishment and closing traffic observed. |
| JSON request identification | `HEARTBEAT`, correlation ID `n08-demo-001` | Request observed in the TCP stream. |
| Four-byte JSON framing | Client: 4-byte header + 64-byte JSON; server: 4-byte header + 178-byte JSON | Framing supported by the recorded exchange. |
| Server response | `ERROR / NOT_IMPLEMENTED` | Expected application response while authentication is unavailable. |
| Unauthenticated heartbeat handling | Request rejected | Passed for the expected rejection path. |
| Evidence references | `GramaLink_N08_TCP_JSON_Test.pcapng`; `N08_JSON_Communication.png` | Capture and screenshot identified by filename. |
| Two-device LAN communication | Not completed | Pending validation. |
| Authentication and database integration | Not complete | Pending integration and subsequent testing. |

## 10. Limitations and pending integration

- **Loopback scope:** All reported communication results concern one computer. A real two-device LAN test remains pending, including connectivity across host firewalls and actual network interfaces.
- **Performance scope:** The approximately 40 ms observation is specific to the recorded local checks. Repeated timing measurements, sustained load, larger client populations, throughput, and network-delay testing are needed for broader performance conclusions.
- **Authentication:** Real credential verification and the authenticated login/session workflow remain pending. Test-created verified sessions must not be presented as completed authentication integration.
- **Database and backend services:** Database integration is not complete. Database-backed workflows and backend-authorized file storage/transfer require integration testing after the relevant services are connected.
- **Client integration:** The demonstrated heartbeat exchange does not verify the complete application client or user-interface workflows. Those paths require end-to-end testing after integration.
- **Capture scope:** The manual capture demonstrates one JSON request/response exchange. It does not by itself validate every message type, file transfer, adverse network condition, or security property.

## 11. Conclusion

The recorded N08 evaluation demonstrates TCP communication, four-byte length-prefixed JSON framing, concurrent handling of 20 simultaneous clients with a responsive 21st connection, and the expected rejection of an unauthenticated heartbeat. Maven verification reported BUILD SUCCESS, with 163 tests passed and no failures or errors.

These results provide evidence for the networking behavior exercised in the localhost / loopback environment. Completion of authentication and database integration, full application integration, and real two-device LAN validation remains necessary before claiming complete integrated network operation.
