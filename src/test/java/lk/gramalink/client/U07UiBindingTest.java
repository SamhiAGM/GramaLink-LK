package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static lk.gramalink.client.FxTestSupport.fx;

/** Isolated view fixtures only; production authentication is never bypassed. */
@EnabledIfSystemProperty(named = "gramalink.uiTests", matches = "true")
@Timeout(30)
class U07UiBindingTest {
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private record View(FXMLLoader loader, Parent root) {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
    }
    private static Object fixture(int role) {
        return switch (role) { case 0 -> new OfflineCitizenWorkspace(); case 1 -> new OfflineGnWorkspace(); case 2 -> new OfflineDsWorkspace(); default -> new OfflineAdminWorkspace(); };
    }
    private static void attach(int role, View view, Object fixture) {
        switch (role) {
            case 0 -> ((CitizenDashboardController)view.loader.getController()).attach((OfflineCitizenWorkspace)fixture, () -> { });
            case 1 -> ((GnDashboardController)view.loader.getController()).attach((OfflineGnWorkspace)fixture, () -> { });
            case 2 -> ((DsDashboardController)view.loader.getController()).attach((OfflineDsWorkspace)fixture, () -> { });
            default -> ((AdminDashboardController)view.loader.getController()).attach((OfflineAdminWorkspace)fixture, () -> { });
        }
    }
    private static View load(int role, Object fixture) throws Exception {
        return fx(() -> {
            String resource = List.of("citizen-dashboard.fxml", "gn-dashboard.fxml", "ds-dashboard.fxml", "admin-dashboard.fxml").get(role);
            FXMLLoader loader = new FXMLLoader(CitizenDashboardView.class.getResource(resource)); Parent root = loader.load();
            new Scene(root, 1340, 940); View view = new View(loader, root); attach(role, view, fixture); return view;
        });
    }
    private static Object listener(Object fixture) throws Exception {
        Field field = fixture.getClass().getDeclaredField("listener"); field.setAccessible(true); return field.get(fixture);
    }
    private static void error(Object listener, String message) {
        if (listener instanceof CitizenGateway.Listener value) value.onError(message);
        else if (listener instanceof GnGateway.Listener value) value.onError(message);
        else if (listener instanceof DsGateway.Listener value) value.onError(message);
        else ((AdminGateway.Listener)listener).onError(message);
    }
    private static void recovery(Object listener, ClientReliability.State state) {
        if (listener instanceof CitizenGateway.Listener value) value.onRecovery(state);
        else if (listener instanceof GnGateway.Listener value) value.onRecovery(state);
        else if (listener instanceof DsGateway.Listener value) value.onRecovery(state);
        else ((AdminGateway.Listener)listener).onRecovery(state);
    }
    private static void emptyUpdate(Object listener) {
        if (listener instanceof CitizenGateway.Listener value) value.onUpdate(new CitizenData.Requests(List.of()));
        else if (listener instanceof GnGateway.Listener value) value.onUpdate(new GnData.Requests(List.of()));
        else if (listener instanceof DsGateway.Listener value) value.onUpdate(new DsData.Requests(List.of()));
        else ((AdminGateway.Listener)listener).onUpdate(new AdminData.Overview(0L, 0L, 0L, 0L, null));
    }
    private static void available(Object fixture, boolean available) throws Exception {
        Field field = fixture.getClass().getDeclaredField("available"); field.setAccessible(true); field.setBoolean(fixture, available);
    }
    private static void close(Object fixture) throws Exception { ((AutoCloseable)fixture).close(); }

    @Test void callbacksFromDetachedBindingsCannotAlterANewRoleViewBinding() throws Exception {
        for (int role = 0; role < 4; role++) {
            final int selectedRole = role; Object old = fixture(role), replacement = fixture(role);
            try {
                View view = load(role, old); Object oldListener = listener(old);
                fx(() -> { attach(selectedRole, view, replacement); view.<Label>node("feedback").setText("New binding marker"); return null; });
                error(oldListener, "Stale error must be ignored"); recovery(oldListener, ClientReliability.State.RECONNECTING); emptyUpdate(oldListener);
                fx(() -> {
                    assertEquals("New binding marker", view.<Label>node("feedback").getText());
                    assertFalse(view.root.lookup("#workspace").isDisabled());
                    String metric = List.of("requestCount", "assignedCount", "forwardedCount", "usersCount").get(selectedRole);
                    assertEquals("—", view.<Label>node(metric).getText()); return null;
                });
            } finally { close(old); close(replacement); }
        }
    }
    @Test void reconnectAndAuthenticationRequiredStatesKeepEveryRoleLocked() throws Exception {
        for (int role = 0; role < 4; role++) {
            Object fixture = fixture(role);
            try {
                View view = load(role, fixture); Object listener = listener(fixture); recovery(listener, ClientReliability.State.RECONNECTING);
                fx(() -> { assertTrue(view.root.lookup("#workspace").isDisabled()); assertEquals("Reconnecting...", view.<Label>node("connectionLabel").getText()); return null; });
                available(fixture, false); recovery(listener, ClientReliability.State.AUTHENTICATION_REQUIRED);
                fx(() -> { assertTrue(view.root.lookup("#workspace").isDisabled()); assertTrue(view.<Label>node("connectionLabel").getText().contains("fresh sign-in required")); return null; });
                recovery(listener, ClientReliability.State.ONLINE);
                fx(() -> { assertTrue(view.root.lookup("#workspace").isDisabled(), "ONLINE transport state cannot grant role authorization"); return null; });
            } finally { close(fixture); }
        }
    }
    @Test void oldCitizenRequestCompletionCannotOverwriteFreshBindingOrBusyState() throws Exception {
        try (OfflineCitizenWorkspace first = new OfflineCitizenWorkspace(); OfflineCitizenWorkspace second = new OfflineCitizenWorkspace()) {
            first.exerciseAcknowledgement = true; first.nextReply = new CompletableFuture<>(); View view = load(0, first);
            fx(() -> {
                view.<TextField>node("requestType").setText("Fixture type"); view.<TextField>node("division").setText("Fixture division"); view.<TextArea>node("description").setText("Fixture draft");
                view.<Button>node("submitButton").fire(); assertTrue(view.<ProgressIndicator>node("busyIndicator").isVisible());
                attach(0, view, second); view.<Label>node("feedback").setText("New session marker"); assertFalse(view.<ProgressIndicator>node("busyIndicator").isVisible()); return null;
            });
            first.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> { assertEquals("New session marker", view.<Label>node("feedback").getText()); assertTrue(view.<TableView<?>>node("requestsTable").getItems().isEmpty()); return null; });
        }
    }
    @Test void disconnectKeepsFileProgressExplicitlyNonDurable() throws Exception {
        try (OfflineCitizenWorkspace fixture = new OfflineCitizenWorkspace()) {
            View view = load(0, fixture); CitizenGateway.Listener listener = (CitizenGateway.Listener)listener(fixture);
            listener.onUpdate(new CitizenData.TransferProgress(3, 9, "TRANSPORT_RECEIVED")); recovery(listener, ClientReliability.State.OFFLINE);
            fx(() -> {
                assertTrue(view.root.lookup("#workspace").isDisabled());
                assertTrue(view.<Label>node("transferLabel").getText().contains("not durable storage confirmation"));
                assertEquals(1.0 / 3, view.<ProgressBar>node("transferProgress").getProgress()); return null;
            });
        }
    }
}
