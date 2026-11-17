package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** Contract/identity fixtures only; no production authorization override or event schema. */
@Timeout(30)
class U07EventDispatchTest {
    private static Message message(MessageType type) { return new Message(type, JsonNodeFactory.instance.objectNode()); }
    private static final class Spy {
        int updates;
        final List<String> errors = new ArrayList<>();
        final List<ClientReliability.State> recovery = new ArrayList<>();
    }
    private static final class Fixture {
        final AtomicBoolean accepted = new AtomicBoolean(true);
        final AtomicInteger decoded = new AtomicInteger();
        final CountDownLatch evaluated = new CountDownLatch(1), authStarted = new CountDownLatch(1), authRelease = new CountDownLatch(1);
        volatile boolean blockAuthorization;
        void authorization() {
            if (!blockAuthorization) return;
            authStarted.countDown();
            try { assertTrue(authRelease.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
        }
        boolean accepts() { evaluated.countDown(); return accepted.get(); }
    }
    private static final class Harness implements AutoCloseable {
        final NetworkClient client = new NetworkClient();
        final BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        final Fixture fixture = new Fixture();
        Runnable authorize, disconnect, connect, close;
        Consumer<Message> incoming;
        Consumer<ClientReliability.State> recovery;
        Consumer<Spy> install;
        BooleanSupplier authorized;
        Harness(int role) {
            if (role == 0) {
                CitizenProtocol protocol = new CitizenProtocol() {
                    public Optional<CitizenData.Identity> citizenAuthorization(Message reply) { fixture.authorization(); return Optional.of(new CitizenData.Identity("fixture", "Fixture Citizen")); }
                    public boolean acceptsEvent(Message message) { return fixture.accepts(); }
                    public Optional<CitizenData.Update> decodeEvent(Message message) { fixture.decoded.incrementAndGet(); return Optional.of(new CitizenData.Notifications(List.of())); }
                };
                CitizenGateway gateway = new CitizenGateway(client, protocol, callbacks::add);
                authorize = () -> gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); disconnect = () -> gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                connect = () -> gateway.connectionStatus(NetworkClient.Status.CONNECTED); incoming = gateway::incoming; recovery = gateway::recoveryState;
                authorized = gateway::isCitizenAuthorized; close = gateway::close;
                install = spy -> gateway.setListener(new CitizenGateway.Listener() {
                    public void onUpdate(CitizenData.Update update) { spy.updates++; }
                    public void onError(String error) { spy.errors.add(error); }
                    public void onRecovery(ClientReliability.State state) { spy.recovery.add(state); }
                });
            } else if (role == 1) {
                GnProtocol protocol = new GnProtocol() {
                    public Optional<GnData.Identity> gnAuthorization(Message reply) { fixture.authorization(); return Optional.of(new GnData.Identity("fixture", "Fixture GN")); }
                    public boolean acceptsEvent(Message message) { return fixture.accepts(); }
                    public Optional<GnData.Update> decodeEvent(Message message) { fixture.decoded.incrementAndGet(); return Optional.of(new GnData.Notifications(List.of())); }
                };
                GnGateway gateway = new GnGateway(client, protocol, callbacks::add);
                authorize = () -> gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); disconnect = () -> gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                connect = () -> gateway.connectionStatus(NetworkClient.Status.CONNECTED); incoming = gateway::incoming; recovery = gateway::recoveryState;
                authorized = gateway::isGnAuthorized; close = gateway::close;
                install = spy -> gateway.setListener(new GnGateway.Listener() {
                    public void onUpdate(GnData.Update update) { spy.updates++; }
                    public void onError(String error) { spy.errors.add(error); }
                    public void onRecovery(ClientReliability.State state) { spy.recovery.add(state); }
                });
            } else if (role == 2) {
                DsProtocol protocol = new DsProtocol() {
                    public Optional<DsData.Identity> dsAuthorization(Message reply) { fixture.authorization(); return Optional.of(new DsData.Identity("fixture", "Fixture DS")); }
                    public boolean acceptsEvent(Message message) { return fixture.accepts(); }
                    public Optional<DsData.Update> decodeEvent(Message message) { fixture.decoded.incrementAndGet(); return Optional.of(new DsData.Notifications(List.of())); }
                };
                DsGateway gateway = new DsGateway(client, protocol, callbacks::add);
                authorize = () -> gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); disconnect = () -> gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                connect = () -> gateway.connectionStatus(NetworkClient.Status.CONNECTED); incoming = gateway::incoming; recovery = gateway::recoveryState;
                authorized = gateway::isDsAuthorized; close = gateway::close;
                install = spy -> gateway.setListener(new DsGateway.Listener() {
                    public void onUpdate(DsData.Update update) { spy.updates++; }
                    public void onError(String error) { spy.errors.add(error); }
                    public void onRecovery(ClientReliability.State state) { spy.recovery.add(state); }
                });
            } else {
                AdminProtocol protocol = new AdminProtocol() {
                    public Optional<AdminData.Identity> administratorAuthorization(Message reply) { fixture.authorization(); return Optional.of(new AdminData.Identity("fixture", "Fixture Admin")); }
                    public Set<MessageType> eventTypes() { return Set.of(MessageType.NOTIFICATION); }
                    public boolean acceptsEvent(Message message) { return fixture.accepts(); }
                    public Optional<AdminData.Update> decodeEvent(Message message) { fixture.decoded.incrementAndGet(); return Optional.of(new AdminData.Clients(List.of())); }
                };
                AdminGateway gateway = new AdminGateway(client, protocol, callbacks::add);
                authorize = () -> gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); disconnect = () -> gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                connect = () -> gateway.connectionStatus(NetworkClient.Status.CONNECTED); incoming = gateway::incoming; recovery = gateway::recoveryState;
                authorized = gateway::isAdministratorAuthorized; close = gateway::close;
                install = spy -> gateway.setListener(new AdminGateway.Listener() {
                    public void onUpdate(AdminData.Update update) { spy.updates++; }
                    public void onError(String error) { spy.errors.add(error); }
                    public void onRecovery(ClientReliability.State state) { spy.recovery.add(state); }
                });
            }
            connect.run();
        }
        void drain() { Runnable task; while ((task = callbacks.poll()) != null) task.run(); }
        Runnable queued() throws InterruptedException { Runnable task = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(task); return task; }
        public void close() { fixture.authRelease.countDown(); close.run(); client.close(); }
    }

    @Test void controlRepliesNeverBecomeBusinessStateAndForbiddenDoesNotGuessGlobalRevocation() {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role)) {
            Spy spy = new Spy(); h.install.accept(spy); h.authorize.run(); h.drain();
            h.incoming.accept(message(MessageType.ACK));
            h.incoming.accept(new Message(MessageType.ERROR, JsonNodeFactory.instance.objectNode().put("code", "FORBIDDEN").put("message", "Fixture request permission denied")));
            h.drain(); assertEquals(0, spy.updates); assertEquals(0, h.fixture.decoded.get());
            assertEquals(1, spy.errors.size()); assertTrue(spy.errors.getFirst().contains("FORBIDDEN"));
            assertTrue(h.authorized.getAsBoolean(), "A request-level FORBIDDEN does not define a session-revocation contract");
        }
    }
    @Test void explicitOwnershipContractMustAcceptBeforeDecoding() throws Exception {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role)) {
            Spy spy = new Spy(); h.install.accept(spy); h.authorize.run(); h.drain(); h.fixture.accepted.set(false);
            h.incoming.accept(message(MessageType.NOTIFICATION)); assertTrue(h.fixture.evaluated.await(5, TimeUnit.SECONDS));
            assertEquals(0, h.fixture.decoded.get()); assertEquals(0, spy.updates);
            h.fixture.accepted.set(true); h.incoming.accept(message(MessageType.NOTIFICATION)); h.queued().run();
            assertEquals(1, spy.updates);
        }
    }
    @Test void queuedEventsCannotCrossDisconnectAndFreshAuthorizationEpochs() throws Exception {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role)) {
            Spy spy = new Spy(); h.install.accept(spy); h.authorize.run(); h.drain();
            h.incoming.accept(message(MessageType.NOTIFICATION)); Runnable oldEvent = h.queued();
            h.disconnect.run(); h.connect.run(); h.authorize.run(); oldEvent.run(); h.drain(); assertEquals(0, spy.updates);
            h.incoming.accept(message(MessageType.NOTIFICATION)); h.queued().run(); assertEquals(1, spy.updates);
        }
    }
    @Test void replacingAListenerDropsItsAlreadyQueuedCallbacks() throws Exception {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role)) {
            Spy old = new Spy(), replacement = new Spy(); h.install.accept(old); h.authorize.run(); h.drain();
            h.incoming.accept(message(MessageType.NOTIFICATION)); Runnable oldEvent = h.queued();
            h.install.accept(replacement); oldEvent.run(); assertEquals(0, old.updates); assertEquals(0, replacement.updates);
            h.incoming.accept(message(MessageType.NOTIFICATION)); h.queued().run(); assertEquals(1, replacement.updates);
        }
    }
    @Test void anAuthorizationDecoderFinishingAfterDisconnectCannotReviveTheSession() throws Exception {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role); ExecutorService worker = Executors.newSingleThreadExecutor()) {
            h.fixture.blockAuthorization = true; Future<?> authorization = worker.submit(h.authorize);
            assertTrue(h.fixture.authStarted.await(5, TimeUnit.SECONDS)); h.disconnect.run(); h.fixture.authRelease.countDown();
            authorization.get(5, TimeUnit.SECONDS); assertFalse(h.authorized.getAsBoolean());
        }
    }
    @Test void recoveryReportsStatesWithoutReusingPreviousAuthorization() {
        for (int role = 0; role < 4; role++) try (Harness h = new Harness(role)) {
            Spy spy = new Spy(); h.install.accept(spy); h.authorize.run(); h.drain(); assertTrue(h.authorized.getAsBoolean());
            h.recovery.accept(ClientReliability.State.RECONNECTING); h.drain(); assertFalse(h.authorized.getAsBoolean());
            h.recovery.accept(ClientReliability.State.AUTHENTICATION_REQUIRED); h.drain(); assertFalse(h.authorized.getAsBoolean());
            h.recovery.accept(ClientReliability.State.ONLINE); h.drain(); assertFalse(h.authorized.getAsBoolean());
            assertEquals(List.of(ClientReliability.State.RECONNECTING, ClientReliability.State.AUTHENTICATION_REQUIRED, ClientReliability.State.ONLINE), spy.recovery);
        }
    }
    @Test void pendingBackendContractsDenyEventOwnershipByDefault() {
        Message event = message(MessageType.NOTIFICATION);
        assertFalse(CitizenProtocol.pendingBackend().acceptsEvent(event)); assertFalse(GnProtocol.pendingBackend().acceptsEvent(event));
        assertFalse(DsProtocol.pendingBackend().acceptsEvent(event)); assertFalse(AdminProtocol.pendingBackend().acceptsEvent(event));
    }
}
