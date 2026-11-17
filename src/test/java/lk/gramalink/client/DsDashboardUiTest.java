package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static lk.gramalink.client.FxTestSupport.fx;

@EnabledIfSystemProperty(named = "gramalink.uiTests", matches = "true")
@Timeout(20)
class DsDashboardUiTest {
    private static final DsData.Version V1 = OfflineDsWorkspace.REVISION, V2 = new DsData.Version("FICTIONAL-REV-B");
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private record Ui(FXMLLoader loader, Parent root, OfflineDsWorkspace fixture) implements AutoCloseable {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
        DsDashboardController controller() { return loader.getController(); }
        public void close() { fixture.close(); }
    }
    private static Ui load(boolean attach) throws Exception {
        return fx(() -> {
            FXMLLoader loader = new FXMLLoader(DsDashboardView.class.getResource("ds-dashboard.fxml")); Parent root = loader.load();
            Scene scene = new Scene(root, 1320, 920);
            scene.getStylesheets().add(DsDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
            scene.getStylesheets().add(DsDashboardView.class.getResource("ds-dashboard.css").toExternalForm());
            OfflineDsWorkspace fixture = new OfflineDsWorkspace(); if (attach) ((DsDashboardController)loader.getController()).attach(fixture, () -> { });
            root.applyCss(); root.layout(); return new Ui(loader, root, fixture);
        });
    }
    private static void samples(Ui ui) throws Exception {
        ui.fixture.sampleData(); fx(() -> { ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getSelectionModel().selectFirst(); return null; });
    }
    private static DsData.Requests snapshot(DsData.Version version, String state) {
        DsData.RequestRow original = OfflineDsWorkspace.first();
        return new DsData.Requests(List.of(new DsData.RequestRow(original.id(), original.citizen(), original.gnOffice(), original.type(), state, original.submittedAt(), version)));
    }
    private static DsData.RequestDetails details(DsData.Version version) {
        return new DsData.RequestDetails(new DsData.Details("PREVIEW-DS-001", version, "Authoritative adapter fixture detail", "Fictional GN origin",
                List.of(), List.of(), List.of()));
    }
    private static void enableFixtureActions(Ui ui) throws Exception {
        ui.fixture.exerciseActions = true; fx(() -> { ui.controller().attach(ui.fixture, () -> { }); return null; });
    }
    @Test void fxmlStartsLockedAndNavigationLoadsEverySection() throws Exception {
        try (Ui ui = load(false)) {
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled()); ui.controller().attach(ui.fixture, () -> { });
                String[] buttons = {"navDashboard", "navRequests", "navNotifications", "navChat"}; TabPane sections = ui.node("sections");
                assertEquals(4, sections.getTabs().size());
                for (int i = 0; i < buttons.length; i++) {
                    ui.<Button>node(buttons[i]).fire(); assertEquals(i, sections.getSelectionModel().getSelectedIndex());
                    assertTrue(ui.<Button>node(buttons[i]).getStyleClass().contains("nav-current"));
                }
                assertTrue(ui.<Label>node("officerName").getText().contains("Fictional")); return null;
            });
        }
    }
    @Test void statisticsTableDatesOriginDocumentsAndActivityUseTypedData() throws Exception {
        try (Ui ui = load(true)) {
            fx(() -> { assertEquals("—", ui.<Label>node("forwardedCount").getText()); return null; });
            ui.fixture.emit(new DsData.Requests(List.of())); fx(() -> { assertEquals("0", ui.<Label>node("forwardedCount").getText()); return null; });
            samples(ui);
            fx(() -> {
                TableView<DsData.RequestRow> table = ui.node("forwardedRequests"); assertEquals(7, table.getColumns().size()); assertEquals(2, table.getItems().size());
                assertEquals("2", ui.<Label>node("forwardedCount").getText()); assertEquals("1", ui.<Label>node("notificationCount").getText());
                assertEquals("Not supplied", ui.<TableColumn<DsData.RequestRow, String>>node("dateColumn").getCellData(1));
                assertEquals("Not supplied", ui.<TableColumn<DsData.RequestRow, String>>node("versionColumn").getCellData(1));
                assertTrue(ui.<Label>node("originatingGn").getText().contains("Fictional GN Officer A"));
                assertTrue(ui.<Label>node("versionLabel").getText().contains(V1.value()));
                assertEquals(1, ui.<TableView<?>>node("documents").getItems().size());
                assertTrue(ui.<ListView<String>>node("history").getItems().getFirst().contains("Sample initial state"));
                assertTrue(ui.<ListView<String>>node("recentActivity").getItems().getFirst().contains("Sample review state"));
                for (String button : List.of("approveButton", "rejectButton", "returnButton", "sendButton")) assertTrue(ui.<Button>node(button).isDisabled());
                return null;
            });
        }
    }
    @Test void rejectionAndReturnRequireExplanationsWithoutSubmitting() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                ComboBox<DsData.ActionKind> kind = ui.node("decisionKind"); Button check = ui.node("checkDecisionButton");
                kind.setValue(DsData.ActionKind.REJECT); check.fire(); assertEquals("Enter a rejection reason.", ui.<Label>node("feedback").getText());
                kind.setValue(DsData.ActionKind.RETURN_TO_GN); check.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("Explain why"));
                ui.<TextArea>node("decisionNote").setText("Local fixture explanation"); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been sent"));
                kind.setValue(DsData.ActionKind.APPROVE); ui.<TextArea>node("decisionNote").clear(); check.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been sent")); assertEquals(0, ui.fixture.operations); return null;
            });
        }
    }
    @Test void absentVersionsAndMismatchedDetailsDisableDecisionsUntilCurrentDataArrives() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enableFixtureActions(ui);
            fx(() -> {
                ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getSelectionModel().selectLast();
                assertTrue(ui.<Button>node("approveButton").isDisabled()); ui.<Button>node("checkDecisionButton").fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("server request version is required"));
                ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getSelectionModel().selectFirst(); return null;
            });
            ui.fixture.emit(snapshot(V2, "Different authoritative fixture state"));
            fx(() -> {
                assertTrue(ui.<Label>node("versionLabel").getText().contains(V2.value()));
                assertTrue(ui.<Button>node("approveButton").isDisabled()); assertTrue(ui.<TableView<?>>node("documents").getItems().isEmpty()); return null;
            });
            ui.fixture.emit(details(V1));
            fx(() -> { assertTrue(ui.<Button>node("approveButton").isDisabled()); return null; });
            ui.fixture.emit(details(V2));
            fx(() -> { assertFalse(ui.<Button>node("approveButton").isDisabled()); return null; });
        }
    }
    @Test void ackDoesNotCommitAStatusOrIncrementVersion() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enableFixtureActions(ui); ui.fixture.nextReply = new CompletableFuture<>();
            fx(() -> {
                ui.<Button>node("approveButton").fire(); assertTrue(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertEquals(V1, ui.fixture.lastAction.expectedVersion()); assertEquals(DsData.ActionKind.APPROVE, ui.fixture.lastAction.kind()); return null;
            });
            ui.fixture.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> {
                DsData.RequestRow row = ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getItems().getFirst();
                assertEquals("Sample review state", row.status()); assertEquals(V1, row.version());
                assertEquals(2, ui.<ListView<String>>node("history").getItems().size());
                assertTrue(ui.<Label>node("feedback").getText().contains("no local decision")); assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible()); return null;
            });
            ui.fixture.emit(snapshot(V2, "Authoritative fixture decision state")); ui.fixture.emit(details(V2));
            fx(() -> {
                DsData.RequestRow row = ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getItems().getFirst();
                assertEquals("Authoritative fixture decision state", row.status()); assertEquals(V2, row.version()); return null;
            });
        }
    }
    @Test void conflictAndForbiddenAreVisibleAndBlockRetryUntilRefreshed() throws Exception {
        for (String code : List.of("CONFLICT", "FORBIDDEN")) {
            try (Ui ui = load(true)) {
                samples(ui); enableFixtureActions(ui);
                ui.fixture.nextReply = CompletableFuture.completedFuture(new Message(MessageType.ERROR, JsonNodeFactory.instance.objectNode().put("code", code).put("message", "Fixture server error")));
                fx(() -> { ui.<TextArea>node("decisionNote").setText("Fixture reason"); ui.<Button>node("rejectButton").fire(); return null; });
                fx(() -> {
                    assertTrue(ui.<Label>node("feedback").getText().contains(code)); assertTrue(ui.<Label>node("feedback").getText().contains("Reload authoritative"));
                    assertTrue(ui.<Button>node("approveButton").isDisabled());
                    assertEquals(V1, ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getItems().getFirst().version()); return null;
                });
                ui.fixture.emit(snapshot(V1, "Sample review state"));
                fx(() -> { assertFalse(ui.<Button>node("approveButton").isDisabled()); return null; });
            }
        }
    }
    @Test void requestScopedChatAndDraftsStaySynchronizedOnIncomingUpdates() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                assertEquals(1, ui.<ListView<String>>node("conversation").getItems().size());
                ui.<TextArea>node("chatText").setText("Unsent chat"); ui.<TextArea>node("decisionNote").setText("Unsent reason"); return null;
            });
            ui.fixture.emit(snapshot(V1, "Updated authoritative text"));
            fx(() -> {
                assertEquals("Unsent chat", ui.<TextArea>node("chatText").getText()); assertEquals("Unsent reason", ui.<TextArea>node("decisionNote").getText());
                assertEquals("PREVIEW-DS-001", ui.<ComboBox<DsData.RequestRow>>node("chatRequest").getValue().id()); return null;
            });
            ui.fixture.sampleData();
            fx(() -> {
                ui.<ComboBox<DsData.RequestRow>>node("chatRequest").getSelectionModel().selectLast();
                assertEquals("PREVIEW-DS-002", ui.<TableView<DsData.RequestRow>>node("forwardedRequests").getSelectionModel().getSelectedItem().id());
                assertTrue(ui.<ListView<String>>node("conversation").getItems().isEmpty()); assertEquals("", ui.<TextArea>node("chatText").getText()); return null;
            });
        }
    }
    @Test void timeoutDoesNotImplyFailureOrSuccessAndConnectionLossLocksTheWorkspace() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enableFixtureActions(ui); ui.fixture.nextReply = CompletableFuture.failedFuture(new TimeoutException());
            fx(() -> { ui.<Button>node("approveButton").fire(); return null; });
            fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains("Outcome is unconfirmed")); assertTrue(ui.<Button>node("approveButton").isDisabled()); return null; });
            ui.fixture.disconnected();
            fx(() -> { assertTrue(ui.root.lookup("#workspace").isDisabled()); assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible()); return null; });
        }
    }
    @Test void lateEventsCannotChangeDataAfterAuthorizationIsLost() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); ui.fixture.disconnected(); ui.fixture.emit(new DsData.Requests(List.of()));
            fx(() -> { assertEquals(2, ui.<TableView<?>>node("forwardedRequests").getItems().size()); assertTrue(ui.root.lookup("#workspace").isDisabled()); return null; });
        }
    }
}
