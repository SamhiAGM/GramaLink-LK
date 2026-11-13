# GramaLink LK file transport v1 (N07 Phase 1)

This document records the original transport-only boundary. The integrated backend now
implements authorization/storage and adds durable STORED completion and download startup;
see [the backend contract](backend-contract.md). Binary framing below is unchanged.

This specification is the NetworkClient integration contract for Ahamad and the
FileTransferService integration boundary for Shadhir. All multibyte integers use
**big-endian/network byte order**. Read exact byte counts; a TCP read is not a frame.

## Frame discrimination and compatibility

Read exactly four bytes as a signed 32-bit integer at every frame boundary:

- `1..1,048,576`: the unchanged JSON payload length. Read that many UTF-8 bytes
  and decode the existing `Message` envelope (`type`, optional `correlationId`,
  optional `token`, object `payload`). Existing JSON wire bytes are unchanged.
- `0x80000000` (signed `-2147483648`): binary extension marker. Parse the v1
  header below. This value cannot be a valid JSON frame length.
- Zero, any other negative value, or a positive value above the JSON limit is a
  protocol error. Close the connection; do not attempt stream resynchronization.

An old JSON-only client safely rejects the binary marker. The server never sends
binary chunks to such a client spontaneously: a backend-authorized download must
first be negotiated. `JsonUtil.readMessage` remains a JSON-only API; a client using
file transport must use `TransportCodec.readFrame` for the entire mixed stream.

## Binary FILE_CHUNK frame

Offsets are from the first marker byte. Length fields are signed 32-bit integers
but must satisfy the positive bounds below. The sequence is a nonnegative signed
64-bit integer. Version/type/flags are unsigned bytes.

| Offset | Bytes | Field | Required value |
| --- | ---: | --- | --- |
| 0 | 4 | Marker | `80 00 00 00` |
| 4 | 4 | Body length | `31 + dataLength`, range `31..65,567` |
| 8 | 1 | Version | `01` |
| 9 | 1 | Binary type | `01` = `FILE_CHUNK` (a fixed wire code, not an enum ordinal) |
| 10 | 16 | Transfer ID | Non-nil UUID: most-significant 64 bits, then least-significant 64 bits |
| 26 | 8 | Sequence | Starts at zero; increases by exactly one per transfer |
| 34 | 1 | Flags | `00` normal chunk; `01` final chunk; all other bits must be zero |
| 35 | 4 | Data length | `1..65,536`, with the empty-file exception below |
| 39 | N | Data | Exactly `dataLength` raw bytes; no Base64 or JSON encoding |

The body length excludes the first eight bytes and includes the 31-byte binary
header. Maximum complete binary frame size is **65,575 bytes** (`39 + 65,536`).
Validate body length before allocating, then validate data length and its exact
agreement with body length. Reject unsupported versions/types/flags, nil IDs,
negative sequences, and truncated headers/data. Never skip an unknown binary type.

The only zero-length chunk is sequence zero with the final flag set. It is valid
only for an authorized transfer whose total size is zero. A nonempty transfer's
final chunk must reach exactly the authorized total size; neither an early final
flag nor reaching the total without a final flag is accepted.

Example: ID `00112233-4455-6677-8899-aabbccddeeff`, sequence zero, final chunk,
three data bytes `01 ff 02` (42 bytes total; body length 34):

```text
80 00 00 00  00 00 00 22  01 01
00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff
00 00 00 00 00 00 00 00  01  00 00 00 03  01 ff 02
```

## Negotiation, authorization and responses

1. Authenticate through the future backend before requesting a transfer.
   Client-provided tokens, roles, user IDs and transfer IDs are not authorization.
2. Send a normal JSON `FILE_META` control message. Its object payload is untrusted
   metadata for the backend to validate. For example it may contain `direction`,
   `fileName`, `totalBytes` and an opaque destination reference. The transport does
   not interpret a filename as a path or create a destination. Exact request-field
   validation and permitted file limits belong to Shadhir's service.
3. Only the backend can issue a `FileTransferService.Grant` after permission,
   metadata, total-size and destination checks. It supplies a fresh non-nil UUID,
   `UPLOAD` or `DOWNLOAD`, and the exact nonnegative total byte count. The grant is
   bound to the exact authenticated session and TCP connection, not just a user ID.
4. A successful JSON response has type `FILE_META`, echoes `correlationId`, omits
   the token, and contains:

   ```json
   {"status":"READY","transferId":"00112233-4455-6677-8899-aabbccddeeff","direction":"UPLOAD","totalBytes":3,"maxChunkBytes":65536}
   ```

   This object is the response's **payload**, not the entire Message envelope.
   A trusted download service must send READY before using `sendChunk`.
5. After READY, use only binary FILE_CHUNK frames for bytes. JSON `FILE_CHUNK`
   messages do not carry data and remain rejected. Sequence tracking starts at
   zero independently for each transfer. Chunks may be interleaved with other
   transfers and JSON control messages only at complete frame boundaries.
6. For uploads, the transport validates order/size, calls the authorized backend
   with one bounded chunk, then returns a JSON `ACK`. Its payload includes
   `transferId`, `sequence`, `receivedBytes`, `complete`, and
   `stage: "TRANSPORT_RECEIVED"`. This ACK is not a claim of durable storage or
   application-level success. Completed transfer IDs cannot receive more chunks.

**Production Phase 1 behavior:** the default service issues no grants. FILE_META
returns `ERROR/NOT_IMPLEMENTED` for both unauthenticated clients and verified
sessions without backend integration. Binary chunks from unauthenticated clients
return `ERROR/AUTHENTICATION_REQUIRED`; authenticated chunks without a grant return
`ERROR/UNKNOWN_TRANSFER`. No file is opened or stored, and no fake login occurs.

Invalid sequence/size/final flags abort just the affected negotiated transfer and
return `ERROR/INVALID_CHUNK`. Backend failures abort it with a generic error;
internal exception text is not sent. Unknown IDs do not affect other transfers.
Malformed wire framing or a broken connection closes the socket and aborts all
its unfinished grants. The service's abort callback releases backend resources.
There is no file-content retry, cross-connection resume, checksum, or durable
completion protocol in Phase 1. TCP preserves byte ordering; reconnecting requires
a new authenticated session, fresh authorization and a new transfer ID.

## Concurrency, bounds and integration

- One SessionManager-owned `ConnectionWriter` handles JSON replies, routed events,
  heartbeat ACKs and binary chunks. A complete frame is written under the same
  connection lock and output wrapper. Do not construct another socket writer.
- The shared codec serializes complete frames under its output-stream monitor;
  server integrations must still use the canonical writer/session APIs.
- The receiver buffers at most one bounded chunk at a time. At most four active
  grants are tracked per connection, each storing counters rather than file data.
  The backend must consume chunks incrementally and must not accumulate a file.
- File traffic does not refresh heartbeat timestamps. Keep sending authenticated
  heartbeats between frames; a stalled partial frame may time out and be closed.
- `FileTransferService` is injected through NetworkServer/ClientHandler. Default
  authorization and consumption are unavailable. Test implementations exist only
  in tests. The future backend owns credentials, roles, permissions, metadata and
  total-file limits, destinations, storage and final application success.
- `FileTransferTransport.sendChunk` provides ordered outbound transport after a
  DOWNLOAD grant. `SessionManager.sendChunkToSession` is the underlying trusted
  write API; it alone is not a transfer-authorization or sequencing check.
- A successful outbound write means bytes were handed to TCP, not that the peer
  durably consumed them. On disconnect or timeout, stale session snapshots cannot
  deliver further frames to a different identity or replacement connection.
