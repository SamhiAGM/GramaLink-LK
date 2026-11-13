package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.server.NetworkServer;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LoginServiceTest {
    @Test void validatesInputAndKeepsPasswordOutOfDescription() {
        var input = new LoginService.Input(" 127.0.0.1 ", 5000, " citizen ", " secret ");
        assertEquals("127.0.0.1", input.host());
        assertEquals("citizen", input.username());
        assertEquals(" secret ", input.password());
        assertFalse(input.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new LoginService.Input("", 5000, "u", "p"));
        assertThrows(IllegalArgumentException.class, () -> new LoginService.Input("bad host", 5000, "u", "p"));
        assertThrows(IllegalArgumentException.class, () -> new LoginService.Input("localhost", 65536, "u", "p"));
        assertThrows(IllegalArgumentException.class, () -> new LoginService.Input("localhost", 5000, " ", "p"));
        assertThrows(IllegalArgumentException.class, () -> new LoginService.Input("localhost", 5000, "u", ""));
    }

    @Test void sendsActualLoginPayloadAndDispatchesStatus() throws Exception {
        BlockingQueue<NetworkClient.Status> status = new LinkedBlockingQueue<>();
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status::add, Runnable::run)) {
            listener.setSoTimeout(3000);
            var pending = service.login(new LoginService.Input("127.0.0.1", listener.getLocalPort(), "citizen", "password"));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message sent = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                assertEquals(MessageType.LOGIN, sent.type());
                assertNotNull(sent.correlationId());
                assertEquals("citizen", sent.payload().path("username").asText());
                assertEquals("password", sent.payload().path("password").asText());
                assertNull(sent.token());
                var error = new Message(MessageType.ERROR, sent.correlationId(), null,
                        JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED").put("message", "Operation LOGIN is not implemented."));
                JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), error);
                assertEquals(error, pending.get(3, TimeUnit.SECONDS));
                assertEquals(NetworkClient.Status.CONNECTED, status.poll(3, TimeUnit.SECONDS));
                service.disconnect().get(3, TimeUnit.SECONDS);
                assertEquals(NetworkClient.Status.DISCONNECTED, status.poll(3, TimeUnit.SECONDS));
                assertEquals(-1, peer.getInputStream().read());
            }
        }
    }

    @Test void realServerRejectsLoginWithoutFakeSuccess() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        try (NetworkServer server = new NetworkServer(port);
             LoginService service = new LoginService(status -> { }, Runnable::run);
             ExecutorService listener = Executors.newSingleThreadExecutor()) {
            Future<?> running = listener.submit(() -> { server.start(); return null; });
            try {
                Message reply = service.login(new LoginService.Input("127.0.0.1", port, "u01-test", "test-only"))
                        .get(3, TimeUnit.SECONDS);
                assertEquals(MessageType.ERROR, reply.type());
                assertEquals("NOT_IMPLEMENTED", reply.payload().path("code").asText());
                assertTrue(service.gnGateway().isEmpty());
                assertTrue(service.dsGateway().isEmpty());
                assertEquals("NOT_IMPLEMENTED: Operation LOGIN is not implemented.", LoginService.describe(reply));
            } finally {
                service.disconnect().get(3, TimeUnit.SECONDS);
                server.close();
                running.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void reportsConnectionFailureAndRejectsUseAfterClose() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) { port = probe.getLocalPort(); }
        LoginService service = new LoginService(status -> { }, Runnable::run);
        var input = new LoginService.Input("127.0.0.1", port, "u", "p");
        try {
            assertInstanceOf(IOException.class,
                    assertThrows(ExecutionException.class, () -> service.login(input).get(7, TimeUnit.SECONDS)).getCause());
        } finally { service.close(); }
        assertThrows(ExecutionException.class, () -> service.login(input).get(3, TimeUnit.SECONDS));
    }

    @Test void neverTreatsLoginOkAsDashboardAuthorization() {
        String description = LoginService.describe(new Message(MessageType.LOGIN_OK, JsonNodeFactory.instance.objectNode()));
        assertTrue(description.contains("Dashboard access is not available"));
    }

    @Test void defaultLoginServiceRejectsUnsolicitedAndCorrelatedCitizenClaims() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            var pending = service.login(new LoginService.Input("127.0.0.1", listener.getLocalPort(), "user", "test-only"));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                var output = new DataOutputStream(peer.getOutputStream());
                var claims = JsonNodeFactory.instance.objectNode().put("role", "CITIZEN").put("userId", "claimed");
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, "unsolicited", "unverified", claims));
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, login.correlationId(), "unverified", claims));
                assertEquals(MessageType.LOGIN_OK, pending.get(3, TimeUnit.SECONDS).type());
                assertTrue(service.citizenGateway().isEmpty(), "An unknown auth payload must never unlock the dashboard");
                assertTrue(service.gnGateway().isEmpty(), "A Citizen claim must not unlock the GN workspace either");
            }
        }
    }

    @Test void defaultLoginServiceDoesNotTrustGnRoleClaims() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            var pending = service.login(new LoginService.Input("127.0.0.1", listener.getLocalPort(), "officer", "test-only"));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), new Message(MessageType.LOGIN_OK, login.correlationId(), "unverified",
                        JsonNodeFactory.instance.objectNode().put("role", "GN_OFFICER").put("userId", "claimed-officer")));
                pending.get(3, TimeUnit.SECONDS);
                assertTrue(service.gnGateway().isEmpty());
                assertTrue(service.citizenGateway().isEmpty());
            }
        }
    }

    @Test void defaultLoginServiceDoesNotTrustDsRoleClaims() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); LoginService service = new LoginService(status -> { }, Runnable::run)) {
            listener.setSoTimeout(3000);
            var pending = service.login(new LoginService.Input("127.0.0.1", listener.getLocalPort(), "ds-officer", "test-only"));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000); Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), new Message(MessageType.LOGIN_OK, login.correlationId(), "unverified",
                        JsonNodeFactory.instance.objectNode().put("role", "DS_OFFICER").put("userId", "claimed-ds")));
                pending.get(3, TimeUnit.SECONDS);
                assertTrue(service.dsGateway().isEmpty()); assertTrue(service.gnGateway().isEmpty()); assertTrue(service.citizenGateway().isEmpty());
            }
        }
    }
}
