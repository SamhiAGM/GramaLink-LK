package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static lk.gramalink.client.FxTestSupport.fx;

@EnabledIfSystemProperty(named = "gramalink.uiTests", matches = "true")
@Timeout(20)
class GnDashboardUiTest {
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private record Ui(FXMLLoader loader, Parent root, OfflineGnWorkspace fixture) implements AutoCloseable {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
        GnDashboardController controller() { return loader.getController(); }
        public void close() { fixture.close(); }
    }
    private static Ui load(boolean attach) throws Exception {
        return fx(() -> {
            FXMLLoader loader = new FXMLLoader(GnDashboardView.class.getResource("gn-dashboard.fxml")); Parent root = loader.load();
            Scene scene = new Scene(root, 1280, 900);
            scene.getStylesheets().add(GnDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
            scene.getStylesheets().add(GnDashboardView.class.getResource("gn-dashboard.css").toExternalForm());
            OfflineGnWorkspace fixture = new OfflineGnWorkspace();
            if (attach) ((GnDashboardController)loader.getController()).attach(fixture, () -> { });
            root.applyCss(); root.layout(); return new Ui(loader, root, fixture);
        });
    }
    private static void samples(Ui ui) throws Exception {
        ui.fixture.sampleData();
        fx(() -> { ui.<TableView<GnData.RequestRow>>node("assignedRequests").getSelectionModel().selectFirst(); return null; });
    }

    @Test void fxmlStartsLockedAndAllNavigationUsesSameTheme() throws Exception {
        try (Ui ui = load(false)) {
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled());
                ui.controller().attach(ui.fixture, () -> { });
                String[] navigation = {"navDashboard", "navRequests", "navNotifications", "navMessages"};
                TabPane tabs = ui.node("sections"); assertEquals(4, tabs.getTabs().size());
                for (int i = 0; i < navigation.length; i++) {
                    ui.<Button>node(navigation[i]).fire(); assertEquals(i, tabs.getSelectionModel().getSelectedIndex());
                    assertTrue(ui.<Button>node(navigation[i]).getStyleClass().contains("nav-current"));
                }
                assertTrue(ui.<Label>node("officerName").getText().contains("Fictional"));
                return null;
            });
        }
    }

    @Test void assignmentsDetailsPriorityHistoryAndNotificationsReflectTypedDataOnly() throws Exception {
        try (Ui ui = load(true)) {
            fx(() -> { assertEquals("—", ui.<Label>node("assignedCount").getText()); return null; });
            ui.fixture.emit(new GnData.Requests(List.of()));
            fx(() -> { assertEquals("0", ui.<Label>node("assignedCount").getText()); return null; });
            samples(ui);
            fx(() -> {
                TableView<GnData.RequestRow> table = ui.node("assignedRequests");
                assertEquals(6, table.getColumns().size()); assertEquals(2, table.getItems().size());
                assertEquals("2", ui.<Label>node("assignedCount").getText());
                assertEquals("1", ui.<Label>node("notificationCount").getText());
                assertEquals("Not supplied", ui.<TableColumn<GnData.RequestRow, String>>node("priorityColumn").getCellData(0));
                assertEquals("Sample priority", ui.<TableColumn<GnData.RequestRow, String>>node("priorityColumn").getCellData(1));
                assertTrue(ui.<Label>node("detailsHeading").getText().contains("Fictional Citizen A"));
                assertEquals(1, ui.<TableView<GnData.Document>>node("documents").getItems().size());
                assertTrue(ui.<ListView<String>>node("history").getItems().getFirst().contains("Sample initial status"));
                assertEquals(2, ui.<ListView<String>>node("history").getItems().size());
                assertEquals(ui.<ListView<String>>node("notifications").getItems(), ui.<ListView<String>>node("recentNotifications").getItems());
                for (String id : List.of("verifyButton", "informationButton", "forwardButton", "rejectButton", "sendButton"))
                    assertTrue(ui.<Button>node(id).isDisabled(), "Unsupported backend operation: " + id);
                return null;
            });
        }
    }

    @Test void actionValidationRequiresReasonsAndSelectedDocumentsWithoutChangingStatus() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                ComboBox<GnData.ActionKind> kind = ui.node("actionKind"); Button check = ui.node("checkActionButton");
                kind.setValue(GnData.ActionKind.REJECT_REQUEST); check.fire();
                assertEquals("Enter a rejection reason.", ui.<Label>node("feedback").getText());
                kind.setValue(GnData.ActionKind.REQUEST_INFORMATION); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("additional information"));
                kind.setValue(GnData.ActionKind.VERIFY_DOCUMENT); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Select a supporting document"));
                ui.<TableView<GnData.Document>>node("documents").getSelectionModel().selectFirst(); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been sent"));
                kind.setValue(GnData.ActionKind.REJECT_REQUEST); ui.<TextArea>node("actionNote").setText("Local fixture reason"); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been sent"));
                assertEquals(0, ui.fixture.operations);
                assertEquals("Sample review status", ui.<TableView<GnData.RequestRow>>node("assignedRequests").getItems().getFirst().status());
                return null;
            });
        }
    }

    @Test void ackDoesNotOptimisticallyVerifyDocumentsOrChangeRequestState() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); ui.fixture.exerciseActions = true; ui.fixture.nextReply = new CompletableFuture<>();
            fx(() -> {
                ui.controller().attach(ui.fixture, () -> { });
                ui.<TableView<GnData.Document>>node("documents").getSelectionModel().selectFirst();
                ui.<Button>node("verifyButton").fire();
                assertTrue(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertTrue(ui.<TableView<?>>node("assignedRequests").isDisabled());
                assertEquals("PREVIEW-DOC-001", ui.fixture.lastAction.documentId());
                assertEquals(GnData.ActionKind.VERIFY_DOCUMENT, ui.fixture.lastAction.kind());
                return null;
            });
            ui.fixture.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> {
                assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertEquals("Sample - not verified", ui.<TableView<GnData.Document>>node("documents").getItems().getFirst().verificationStatus());
                assertEquals("Sample review status", ui.<TableView<GnData.RequestRow>>node("assignedRequests").getItems().getFirst().status());
                assertEquals(2, ui.<ListView<String>>node("history").getItems().size());
                assertTrue(ui.<Label>node("feedback").getText().contains("no local status change"));
                return null;
            });
            ui.fixture.emit(new GnData.RequestDetails(new GnData.Details("PREVIEW-GN-001", "Authoritative fixture details",
                    List.of(new GnData.Document("PREVIEW-DOC-001", "fixture.pdf", "State supplied by adapter fixture")), List.of(), List.of())));
            fx(() -> { assertEquals("State supplied by adapter fixture", ui.<TableView<GnData.Document>>node("documents").getItems().getFirst().verificationStatus()); return null; });
        }
    }

    @Test void requestSpecificChatPreservesDraftsAndCannotLeakAcrossSelections() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                assertEquals(1, ui.<ListView<String>>node("conversation").getItems().size());
                ui.<TextArea>node("chatText").setText("Unsent chat draft"); ui.<TextArea>node("actionNote").setText("Unsent decision reason"); return null;
            });
            ui.fixture.emit(new GnData.Requests(List.of(new GnData.RequestRow("PREVIEW-GN-001", "Fictional citizen", "Fixture type", Instant.EPOCH, "Updated fixture status", null),
                    new GnData.RequestRow("PREVIEW-GN-002", "Other fictional citizen", "Fixture type", Instant.EPOCH, "Other status", null))));
            fx(() -> {
                assertEquals("Unsent chat draft", ui.<TextArea>node("chatText").getText());
                assertEquals("Unsent decision reason", ui.<TextArea>node("actionNote").getText());
                ComboBox<GnData.RequestRow> request = ui.node("messageRequest"); assertEquals("PREVIEW-GN-001", request.getValue().id());
                request.getSelectionModel().selectLast();
                assertEquals("PREVIEW-GN-002", ui.<TableView<GnData.RequestRow>>node("assignedRequests").getSelectionModel().getSelectedItem().id());
                assertTrue(ui.<ListView<String>>node("conversation").getItems().isEmpty());
                assertEquals("", ui.<TextArea>node("chatText").getText());
                assertEquals("", ui.<TextArea>node("actionNote").getText()); return null;
            });
        }
    }

    @Test void officerButtonsValidateBeforeSendingAndServerErrorsStayVisible() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); ui.fixture.exerciseActions = true;
            fx(() -> {
                ui.controller().attach(ui.fixture, () -> { });
                ui.<Button>node("rejectButton").fire(); assertEquals(0, ui.fixture.operations);
                assertEquals("Enter a rejection reason.", ui.<Label>node("feedback").getText());
                ui.<Button>node("sendButton").fire(); assertEquals(0, ui.fixture.operations);
                assertTrue(ui.<Label>node("feedback").getText().contains("Enter a message"));
                ui.<TextArea>node("actionNote").setText("Fixture reason");
                ui.fixture.nextReply = CompletableFuture.completedFuture(new Message(MessageType.ERROR,
                        JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED").put("message", "Officer service unavailable")));
                ui.<Button>node("rejectButton").fire(); return null;
            });
            fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains("NOT_IMPLEMENTED")); return null; });
        }
    }

    @Test void timeoutIsUnconfirmedAndConnectionLossLocksAllOfficerActions() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); ui.fixture.exerciseActions = true; ui.fixture.nextReply = CompletableFuture.failedFuture(new TimeoutException());
            fx(() -> { ui.controller().attach(ui.fixture, () -> { }); ui.<Button>node("forwardButton").fire(); return null; });
            fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains("status is unconfirmed")); return null; });
            ui.fixture.disconnected();
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled());
                assertTrue(ui.<Label>node("feedback").getText().contains("Actions are locked"));
                assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible()); return null;
            });
        }
    }
}
