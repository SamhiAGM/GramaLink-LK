package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.TransportCodec;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferTransportTest {
    // Identities and accepting services below are unit-test stand-ins only.
    private static final SessionManager.VerifiedIdentity USER =
            new SessionManager.VerifiedIdentity("file-test-user", "CITIZEN");

    private static Message metadata() {
        return new Message(MessageType.FILE_META, "metadata-17", "untrusted-client-token",
                JsonNodeFactory.instance.objectNode().put("fileName", "test.bin")
                        .put("destination", "opaque-destination"));
    }

    private static FileTransferService.Grant grant(FileTransferService.Direction direction, long totalBytes) {
        return new FileTransferService.Grant(UUID.randomUUID(), direction, totalBytes);
    }

    private static void assertError(Message response, String code) {
        assertEquals(MessageType.ERROR, response.type());
        assertEquals(code, response.payload().path("code").asText());
        assertNull(response.token());
    }

    @Test
    void unauthenticatedOperationsNeverCallTheBackendOrCreateSessions() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            assertError(transport.negotiate(metadata()), "NOT_IMPLEMENTED");
            assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})),
                    "AUTHENTICATION_REQUIRED");
            assertEquals(0, service.authorizations);
            assertTrue(service.received.isEmpty());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void productionUnavailableServiceNeverPretendsThatAnUploadWasAccepted() throws Exception {
        var sessions = new SessionManager();
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, FileTransferService.unavailable())) {
            sessions.registerVerifiedSession(USER, pair.server);
            assertError(transport.negotiate(metadata()), "NOT_IMPLEMENTED");
            assertError(transport.receiveChunk(new FileChunk(UUID.randomUUID(), 0, true, new byte[]{1})),
                    "UNKNOWN_TRANSFER");
            assertFalse(pair.server.isClosed());
        }
    }

    @Test
    void metadataNegotiationUsesTheBackendGrantAndVerifiedSession() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 5));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            var session = sessions.registerVerifiedSession(USER, pair.server);
            Message request = metadata();
            Message response = transport.negotiate(request);

            assertEquals(MessageType.FILE_META, response.type());
            assertEquals(request.correlationId(), response.correlationId());
            assertNull(response.token());
            assertEquals("READY", response.payload().path("status").asText());
            assertEquals(service.grant.transferId().toString(), response.payload().path("transferId").asText());
            assertEquals("UPLOAD", response.payload().path("direction").asText());
            assertEquals(5, response.payload().path("totalBytes").asLong());
            assertEquals(65536, response.payload().path("maxChunkBytes").asInt());
            assertSame(session, service.authorizedSession);
            assertSame(request, service.metadata);
        }
    }

    @Test
    void orderedUploadChunksReachTheServiceAndAcknowledgeOnlyTransportReceipt() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 5));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            UUID transferId = service.grant.transferId();
            Message first = transport.receiveChunk(new FileChunk(transferId, 0, false, new byte[]{1, 2}));
            assertEquals(MessageType.ACK, first.type());
            assertEquals("TRANSPORT_RECEIVED", first.payload().path("stage").asText());
            assertEquals(transferId.toString(), first.payload().path("transferId").asText());
            assertEquals(0, first.payload().path("sequence").asLong());
            assertEquals(2, first.payload().path("receivedBytes").asLong());
            assertFalse(first.payload().path("complete").asBoolean());

            Message last = transport.receiveChunk(new FileChunk(transferId, 1, true, new byte[]{3, 4, 5}));
            assertEquals(MessageType.ACK, last.type());
            assertEquals(5, last.payload().path("receivedBytes").asLong());
            assertTrue(last.payload().path("complete").asBoolean());
            assertFalse(last.payload().has("stored"));
            assertEquals(2, service.received.size());
            assertArrayEquals(new byte[]{1, 2}, service.received.getFirst().data());
            assertArrayEquals(new byte[]{3, 4, 5}, service.received.get(1).data());
            assertError(transport.receiveChunk(new FileChunk(transferId, 1, true, new byte[]{3, 4, 5})),
                    "UNKNOWN_TRANSFER");
        }
        assertTrue(service.aborted.isEmpty(), "A completed transfer must not be aborted on disconnect.");
    }

    @Test
    void zeroByteTransferUsesOneFinalEmptyChunk() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 0));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            Message response = transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[0]));
            assertEquals(MessageType.ACK, response.type());
            assertTrue(response.payload().path("complete").asBoolean());
            assertEquals(0, response.payload().path("receivedBytes").asLong());
            assertEquals(1, service.received.size());
        }
    }

    @Test
    void outOfOrderChunkAbortsOnlyItsTransferWithoutCallingTheService() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 1, true, new byte[]{1})),
                    "INVALID_CHUNK");
            assertTrue(service.received.isEmpty());
            assertEquals(1, service.aborted.size());
            assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})),
                    "UNKNOWN_TRANSFER");
            assertFalse(pair.server.isClosed());
        }
    }

    @Test
    void unknownTransferIdDoesNotAffectAnAuthorizedTransfer() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            assertError(transport.receiveChunk(new FileChunk(UUID.randomUUID(), 0, true, new byte[]{1})),
                    "UNKNOWN_TRANSFER");
            assertEquals(MessageType.ACK,
                    transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})).type());
            assertEquals(1, service.received.size());
        }
    }

    @Test
    void byteLimitsAndFinalFlagsAreValidatedBeforeBackendConsumption() throws Exception {
        for (FileChunkSpec invalid : List.of(new FileChunkSpec(4, true), new FileChunkSpec(2, true),
                new FileChunkSpec(3, false))) {
            var sessions = new SessionManager();
            var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 3));
            try (SocketPair pair = new SocketPair();
                 var transport = new FileTransferTransport(pair.server, sessions, service)) {
                sessions.registerVerifiedSession(USER, pair.server);
                transport.negotiate(metadata());
                assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 0,
                        invalid.last(), new byte[invalid.length()])), "INVALID_CHUNK");
                assertTrue(service.received.isEmpty());
                assertEquals(1, service.aborted.size());
            }
        }
    }

    @Test
    void boundedActiveTransferLimitRejectsFurtherNegotiation() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            for (int count = 0; count < FileTransferTransport.MAX_ACTIVE_TRANSFERS; count++) {
                service.grant = grant(FileTransferService.Direction.UPLOAD, 1);
                assertEquals(MessageType.FILE_META, transport.negotiate(metadata()).type());
            }
            service.grant = grant(FileTransferService.Direction.UPLOAD, 1);
            assertError(transport.negotiate(metadata()), "TRANSFER_LIMIT");
            assertEquals(FileTransferTransport.MAX_ACTIVE_TRANSFERS, service.authorizations);
        }
        assertEquals(FileTransferTransport.MAX_ACTIVE_TRANSFERS, service.aborted.size());
    }

    @Test
    void duplicateGrantCannotReplaceAnActiveTransfer() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            assertError(transport.negotiate(metadata()), "TRANSFER_CONFLICT");
            assertEquals(MessageType.ACK,
                    transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})).type());
            assertEquals(1, service.received.size());
        }
    }

    @Test
    void backendAuthorizationFailureCreatesNoTransfer() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        service.authorizationFailure = new IOException("Private backend details must not be forwarded.");
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            Message response = transport.negotiate(metadata());
            assertError(response, "FILE_TRANSFER_REJECTED");
            assertFalse(response.payload().toString().contains("Private backend"));
            assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})),
                    "UNKNOWN_TRANSFER");
        }
    }

    @Test
    void backendConsumptionFailureReturnsErrorAndReleasesTheTransfer() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        service.receiveFailure = new IOException("Private storage path must not be forwarded.");
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            Message response = transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1}));
            assertError(response, "FILE_TRANSFER_FAILED");
            assertFalse(response.payload().toString().contains("Private storage"));
            assertEquals(1, service.aborted.size());
            assertError(transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})),
                    "UNKNOWN_TRANSFER");
        }
    }

    @Test
    void closingTransportAbortsOutstandingTransfersOnlyOnce() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 100));
        try (SocketPair pair = new SocketPair()) {
            sessions.registerVerifiedSession(USER, pair.server);
            var transport = new FileTransferTransport(pair.server, sessions, service);
            transport.negotiate(metadata());
            transport.close();
            transport.close();
            assertEquals(1, service.aborted.size());
        }
    }

    @Test
    void anOldGrantCannotFollowTheSocketToADifferentAuthenticatedIdentity() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.UPLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            sessions.removeSession(pair.server);
            sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity("another-user", "CITIZEN"), pair.server);

            Message response = transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1}));
            assertEquals(MessageType.ERROR, response.type());
            assertTrue(service.received.isEmpty());
            assertEquals(1, service.aborted.size());
        }
    }

    @Test
    void incomingChunkCannotBeConsumedAsPartOfAnAuthorizedDownload() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.DOWNLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            Message response = transport.receiveChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1}));
            assertEquals(MessageType.ERROR, response.type());
            assertTrue(service.received.isEmpty());
        }
    }

    @Test
    void authorizedDownloadSendsOrderedBinaryChunksThroughTheCanonicalWriter() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.DOWNLOAD, 3));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            assertEquals(MessageType.FILE_META, transport.negotiate(metadata()).type());
            var first = new FileChunk(service.grant.transferId(), 0, false, new byte[]{1});
            var last = new FileChunk(service.grant.transferId(), 1, true, new byte[]{2, 3});
            transport.sendChunk(first);
            transport.sendChunk(last);

            assertChunk(first, assertInstanceOf(FileChunk.class, TransportCodec.readFrame(pair.input)));
            assertChunk(last, assertInstanceOf(FileChunk.class, TransportCodec.readFrame(pair.input)));
            assertTrue(service.received.isEmpty(), "Downloads must not invoke the upload consumer.");
            assertFalse(pair.server.isClosed());
        }
        assertTrue(service.aborted.isEmpty());
    }

    @Test
    void invalidDownloadSequenceDoesNotCloseAnOtherwiseHealthySocket() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.DOWNLOAD, 1));
        try (SocketPair pair = new SocketPair();
             var transport = new FileTransferTransport(pair.server, sessions, service)) {
            sessions.registerVerifiedSession(USER, pair.server);
            transport.negotiate(metadata());
            assertThrows(IOException.class,
                    () -> transport.sendChunk(new FileChunk(service.grant.transferId(), 1, true, new byte[]{1})));
            assertFalse(pair.server.isClosed());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void interruptedDownloadClosesSocketAndRemovesItsSession() throws Exception {
        var sessions = new SessionManager();
        var service = new RecordingService(grant(FileTransferService.Direction.DOWNLOAD, 1));
        try (FailingSocket socket = new FailingSocket();
             var transport = new FileTransferTransport(socket, sessions, service)) {
            sessions.registerVerifiedSession(USER, socket);
            transport.negotiate(metadata());
            assertThrows(IOException.class,
                    () -> transport.sendChunk(new FileChunk(service.grant.transferId(), 0, true, new byte[]{1})));
            assertTrue(socket.isClosed());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        }
    }

    private static void assertChunk(FileChunk expected, FileChunk actual) {
        assertEquals(expected.transferId(), actual.transferId());
        assertEquals(expected.sequence(), actual.sequence());
        assertEquals(expected.last(), actual.last());
        assertArrayEquals(expected.data(), actual.data());
    }

    private record FileChunkSpec(int length, boolean last) { }

    private static final class RecordingService implements FileTransferService {
        private Grant grant;
        private int authorizations;
        private SessionManager.Session authorizedSession;
        private Message metadata;
        private IOException authorizationFailure;
        private IOException receiveFailure;
        private final List<FileChunk> received = new ArrayList<>();
        private final List<Grant> aborted = new ArrayList<>();

        private RecordingService(Grant grant) {
            this.grant = grant;
        }

        @Override
        public Grant authorize(SessionManager.Session session, Message metadata) throws IOException {
            authorizations++;
            authorizedSession = session;
            this.metadata = metadata;
            if (authorizationFailure != null) {
                throw authorizationFailure;
            }
            return grant;
        }

        @Override
        public void receiveChunk(SessionManager.Session session, Grant grant, FileChunk chunk) throws IOException {
            if (receiveFailure != null) {
                throw receiveFailure;
            }
            received.add(chunk);
        }

        @Override
        public void abort(SessionManager.Session session, Grant grant) {
            aborted.add(grant);
        }
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;
        private final DataInputStream input;

        private SocketPair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                server = listener.accept();
            }
            client.setSoTimeout(5000);
            input = new DataInputStream(client.getInputStream());
        }

        @Override
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                client.close();
            }
        }
    }

    private static final class FailingSocket extends Socket {
        private boolean closed;

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    closed = true;
                    throw new IOException("Test connection interruption.");
                }
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
