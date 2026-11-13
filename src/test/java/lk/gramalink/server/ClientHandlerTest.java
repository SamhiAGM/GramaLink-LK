package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ClientHandlerTest {
    @Test
    void consecutiveOperationsReturnErrorsWithoutEchoingToken() throws Exception {
        try (Connection connection = new Connection()) {
            for (MessageType type : MessageType.values()) {
                Message request = new Message(type, "request-" + type, "private-token",
                        JsonNodeFactory.instance.objectNode());
                JsonUtil.writeMessage(connection.output, request);

                Message response = JsonUtil.readMessage(connection.input);
                assertNotNull(response);
                assertEquals(MessageType.ERROR, response.type());
                assertEquals(request.correlationId(), response.correlationId());
                assertNull(response.token());
                assertEquals("NOT_IMPLEMENTED", response.payload().path("code").asText());
            }
        }
    }

    @Test
    void largeCorrelationIdStillReceivesAnErrorWithinFrameLimit() throws Exception {
        try (Connection connection = new Connection()) {
            var emptyPayload = JsonNodeFactory.instance.objectNode();
            int envelopeBytes = JsonUtil.toJson(new Message(MessageType.LOGIN, "", null, emptyPayload))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String correlationId = "x".repeat(JsonUtil.MAX_PAYLOAD_BYTES - envelopeBytes);
            JsonUtil.writeMessage(connection.output,
                    new Message(MessageType.LOGIN, correlationId, null, emptyPayload));

            Message response = JsonUtil.readMessage(connection.input);
            assertNotNull(response);
            assertEquals(MessageType.ERROR, response.type());
            assertEquals("NOT_IMPLEMENTED", response.payload().path("code").asText());
            assertNull(response.correlationId(), "Omit a correlation ID that cannot fit in the reply.");
        }
    }

    @Test
    void malformedClientDoesNotAffectAnotherOpenConnection() throws Exception {
        try (Connection bad = new Connection(); Connection good = new Connection()) {
            bad.output.writeInt(-1);
            bad.output.flush();
            assertEquals(-1, bad.input.read());
            bad.assertStopped();

            JsonUtil.writeMessage(good.output,
                    new Message(MessageType.LOGIN, JsonNodeFactory.instance.objectNode()));
            Message response = JsonUtil.readMessage(good.input);
            assertNotNull(response);
            assertEquals(MessageType.ERROR, response.type());
        }
    }

    @Test
    void cleanClientDisconnectFinishesHandlerAndRunsCleanup() throws Exception {
        try (Connection connection = new Connection()) {
            connection.client.shutdownOutput();
            assertNull(JsonUtil.readMessage(connection.input));
            connection.assertStopped();
        }
    }

    @Test
    void closingServerSideSocketUnblocksPendingFrameRead() throws Exception {
        try (Connection connection = new Connection()) {
            connection.output.writeInt(20);
            connection.output.writeByte('{');
            connection.output.flush();
            connection.serverSide.close();
            connection.assertStopped();
        }
    }

    private static final class Connection implements AutoCloseable {
        private final Socket client;
        private final Socket serverSide;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final AtomicBoolean cleanedUp = new AtomicBoolean();
        private final Thread handler;

        private Connection() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                serverSide = listener.accept();
            }
            client.setSoTimeout(5000);
            input = new DataInputStream(client.getInputStream());
            output = new DataOutputStream(client.getOutputStream());
            handler = new Thread(new ClientHandler(serverSide, () -> cleanedUp.set(true)));
            handler.start();
        }

        private void assertStopped() throws InterruptedException {
            handler.join(5000);
            assertFalse(handler.isAlive(), "Client handler must stop after socket closure.");
            assertTrue(cleanedUp.get(), "Connection must be removed from server tracking.");
        }

        @Override
        public void close() throws Exception {
            try {
                client.close();
            } finally {
                serverSide.close();
                assertStopped();
            }
        }
    }
}
