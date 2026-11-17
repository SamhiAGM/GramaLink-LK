package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ProductionLoginAuthenticationTest {
    private static final String USER_ID = "20000000-0000-0000-0000-000000000002";

    @Test void authorizesCitizenFromTheBackendIdentity() throws Exception { authenticatesOnly("CITIZEN"); }
    @Test void authorizesGnFromTheBackendIdentity() throws Exception { authenticatesOnly("GN"); }
    @Test void authorizesDsFromTheBackendIdentity() throws Exception { authenticatesOnly("DS"); }
    @Test void authorizesAdministratorFromTheBackendIdentity() throws Exception { authenticatesOnly("ADMIN"); }

    @Test void rejectsLoginOkWithoutTheContractEnvelope() throws Exception {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("data", identityData("CITIZEN"));
        rejects(payload);
    }

    @Test void rejectsUnknownRole() throws Exception {
        ObjectNode payload = loginPayload("GN_OFFICER");
        rejects(payload);
    }

    @Test void rejectsMissingUserId() throws Exception {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("role", "CITIZEN");
        rejects(loginPayload(data));
    }

    @Test void rejectsMalformedUserId() throws Exception {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("userId", "not-a-server-uuid").put("role", "CITIZEN");
        rejects(loginPayload(data));
    }

    @Test void unsolicitedLoginOkDoesNotAuthorizeBeforeTheMatchedReply() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = read(peer);
                write(peer, loginReply("unsolicited-id", "ADMIN"));
                assertRemainsPending(pending);
                assertNoWorkspace(service);
                write(peer, loginReply(login.correlationId(), "CITIZEN"));
                assertEquals(MessageType.LOGIN_OK, pending.get(3, TimeUnit.SECONDS).type());
                assertTrue(service.citizenGateway().isPresent());
                assertTrue(service.adminGateway().isEmpty());
            }
        }
    }

    @Test void incorrectCorrelationDoesNotCompleteOrAuthorizeLogin() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = read(peer);
                write(peer, loginReply("different-request-id", "GN"));
                assertRemainsPending(pending);
                assertNoWorkspace(service);
                write(peer, loginReply(login.correlationId(), "GN"));
                assertEquals(MessageType.LOGIN_OK, pending.get(3, TimeUnit.SECONDS).type());
                assertTrue(service.gnGateway().isPresent());
            }
        }
    }

    @Test void replacementRevokesOldRoleAndIgnoresOldCorrelation() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> first = service.login(input(listener.getLocalPort()));
            try (Socket firstPeer = listener.accept()) {
                Message firstLogin = read(firstPeer);
                write(firstPeer, loginReply(firstLogin.correlationId(), "CITIZEN"));
                first.get(3, TimeUnit.SECONDS);
                assertTrue(service.citizenGateway().isPresent());

                CompletableFuture<Message> replacement = service.login(input(listener.getLocalPort()));
                assertNoWorkspace(service);
                try (Socket secondPeer = listener.accept()) {
                    Message secondLogin = read(secondPeer);
                    assertNotEquals(firstLogin.correlationId(), secondLogin.correlationId());
                    write(secondPeer, loginReply(firstLogin.correlationId(), "ADMIN"));
                    assertRemainsPending(replacement);
                    assertNoWorkspace(service);
                    write(secondPeer, loginReply(secondLogin.correlationId(), "DS"));
                    replacement.get(3, TimeUnit.SECONDS);
                    assertTrue(service.dsGateway().isPresent());
                    assertTrue(service.citizenGateway().isEmpty());
                    assertTrue(service.adminGateway().isEmpty());
                }
            }
        }
    }

    @Test void connectionLossRevokesTheAuthenticatedWorkspace() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            Socket peer = listener.accept();
            Message login = read(peer);
            write(peer, loginReply(login.correlationId(), "CITIZEN"));
            pending.get(3, TimeUnit.SECONDS);
            assertTrue(service.citizenGateway().isPresent());
            peer.close();
            waitUntil(() -> service.citizenGateway().isEmpty(), 3000);
            assertTrue(service.fileProgress().isEmpty());
        }
    }

    @Test void signOutRevokesEveryWorkspaceAndStopsAuthenticatedState() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                Message login = read(peer);
                write(peer, loginReply(login.correlationId(), "ADMIN"));
                pending.get(3, TimeUnit.SECONDS);
                assertTrue(service.adminGateway().isPresent());
                service.disconnect().get(3, TimeUnit.SECONDS);
                assertNoWorkspace(service);
                assertEquals(ClientReliability.State.OFFLINE, service.recoveryState());
                assertEquals(-1, peer.getInputStream().read());
            }
        }
    }

    private static void authenticatesOnly(String role) throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = read(peer);
                assertEquals(MessageType.LOGIN, login.type());
                assertEquals("citizen.demo", login.payload().path("username").asText());
                assertEquals("DemoPass123!", login.payload().path("password").asText());
                assertNull(login.token());
                write(peer, loginReply(login.correlationId(), role));
                assertEquals(MessageType.LOGIN_OK, pending.get(3, TimeUnit.SECONDS).type());
                assertEquals(role.equals("CITIZEN"), service.citizenGateway().isPresent());
                assertEquals(role.equals("GN"), service.gnGateway().isPresent());
                assertEquals(role.equals("DS"), service.dsGateway().isPresent());
                assertEquals(role.equals("ADMIN"), service.adminGateway().isPresent());
                assertEquals(ClientReliability.State.ONLINE, service.recoveryState());
                switch (role) {
                    case "CITIZEN" -> {
                        assertTrue(service.citizenGateway().orElseThrow().supports(CitizenProtocol.Operation.CREATE_REQUEST));
                        assertTrue(service.citizenGateway().orElseThrow().supports(CitizenProtocol.Operation.CHAT_MESSAGE));
                    }
                    case "GN" -> assertTrue(service.gnGateway().orElseThrow().supports(GnData.ActionKind.FORWARD_TO_DS));
                    case "DS" -> assertTrue(service.dsGateway().orElseThrow().supports(DsData.ActionKind.APPROVE));
                    case "ADMIN" -> assertTrue(service.adminGateway().orElseThrow().supports(AdminData.Operation.CREATE_DEMO_USER));
                    default -> throw new AssertionError(role);
                }
                String displayId = switch (role) {
                    case "CITIZEN" -> service.citizenGateway().orElseThrow().identity().orElseThrow().displayName();
                    case "GN" -> service.gnGateway().orElseThrow().identity().orElseThrow().displayName();
                    case "DS" -> service.dsGateway().orElseThrow().identity().orElseThrow().displayName();
                    case "ADMIN" -> service.adminGateway().orElseThrow().identity().orElseThrow().displayName();
                    default -> throw new AssertionError(role);
                };
                assertEquals(USER_ID, displayId);
            }
        }
    }

    private static void rejects(ObjectNode payload) throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            CompletableFuture<Message> pending = service.login(input(listener.getLocalPort()));
            try (Socket peer = listener.accept()) {
                Message login = read(peer);
                write(peer, new Message(MessageType.LOGIN_OK, login.correlationId(), null, payload));
                ExecutionException error = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, error.getCause());
                assertNoWorkspace(service);
                assertEquals(ClientReliability.State.OFFLINE, service.recoveryState());
            }
        }
    }

    private static void assertNoWorkspace(LoginService service) {
        assertTrue(service.citizenGateway().isEmpty());
        assertTrue(service.gnGateway().isEmpty());
        assertTrue(service.dsGateway().isEmpty());
        assertTrue(service.adminGateway().isEmpty());
    }

    private static LoginService.Input input(int port) {
        return new LoginService.Input("127.0.0.1", port, "citizen.demo", "DemoPass123!");
    }

    private static ObjectNode identityData(String role) {
        return JsonNodeFactory.instance.objectNode().put("userId", USER_ID).put("role", role);
    }

    private static ObjectNode loginPayload(String role) {
        return loginPayload(identityData(role));
    }

    private static ObjectNode loginPayload(ObjectNode data) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("operation", "LOGIN");
        payload.set("data", data);
        return payload;
    }

    private static Message loginReply(String correlationId, String role) {
        return new Message(MessageType.LOGIN_OK, correlationId, null, loginPayload(role));
    }

    private static Message read(Socket socket) throws Exception {
        return JsonUtil.readMessage(new DataInputStream(socket.getInputStream()));
    }

    private static void write(Socket socket, Message message) throws Exception {
        JsonUtil.writeMessage(new DataOutputStream(socket.getOutputStream()), message);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition was not reached before timeout.");
    }

    private static void assertRemainsPending(CompletableFuture<?> pending) throws InterruptedException {
        Thread.sleep(100);
        assertFalse(pending.isDone());
    }
}
