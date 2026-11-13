package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual FXML/control tests, opt-in on a workstation with a desktop; no TestFX dependency. */
@EnabledIfSystemProperty(named = "gramalink.uiTests", matches = "true")
@Timeout(20)
class CitizenDashboardUiTest {
    @BeforeAll static void toolkit() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(10, TimeUnit.SECONDS);
    }
    @AfterAll static void stopToolkit() { Platform.exit(); }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
    private record Ui(FXMLLoader loader, Parent root, OfflineCitizenWorkspace fixture) implements AutoCloseable {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
        CitizenDashboardController controller() { return loader.getController(); }
        public void close() { fixture.close(); }
    }
    private static Ui load(boolean attach) throws Exception {
        return fx(() -> {
            FXMLLoader loader = new FXMLLoader(CitizenDashboardView.class.getResource("citizen-dashboard.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root, 1180, 860);
            scene.getStylesheets().add(CitizenDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
            OfflineCitizenWorkspace fixture = new OfflineCitizenWorkspace();
            if (attach) ((CitizenDashboardController)loader.getController()).attach(fixture, () -> { });
            root.applyCss(); root.layout();
            return new Ui(loader, root, fixture);
        });
    }
    private static void fillDraft(Ui ui) {
        ui.<TextField>node("requestType").setText("Fictional draft type");
        ui.<TextField>node("division").setText("Fictional division");
        ui.<TextArea>node("description").setText("Local validation only");
    }

    @Test void loadsLockedThenNavigatesAllSevenSectionsUsingFxmlButtons() throws Exception {
        try (Ui ui = load(false)) {
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled());
                ui.controller().attach(ui.fixture, () -> { });
                TabPane sections = ui.node("sections");
                assertEquals(7, sections.getTabs().size());
                String[] ids = {"navDashboard", "navNewRequest", "navRequests", "navNotifications", "navDocuments", "navMessages", "navProfile"};
                for (int i = 0; i < ids.length; i++) {
                    ui.<Button>node(ids[i]).fire();
                    assertEquals(i, sections.getSelectionModel().getSelectedIndex());
                    assertTrue(ui.<Button>node(ids[i]).getStyleClass().contains("nav-current"));
                }
                assertEquals("Offline preview user", ui.<Label>node("profileName").getText());
                assertEquals("PREVIEW-NOT-A-SESSION", ui.<Label>node("profileUserId").getText());
                return null;
            });
        }
    }

    @Test void validatesRequiredFieldsWithoutSubmittingOrCreatingRows() throws Exception {
        try (Ui ui = load(true)) {
            fx(() -> {
                Button validate = ui.node("validateButton");
                validate.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("request type"));
                ui.<TextField>node("requestType").setText("Local draft type");
                validate.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("GN division"));
                ui.<TextField>node("division").setText("Local division");
                validate.fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Describe"));
                ui.<TextArea>node("description").setText("A local draft");
                validate.fire();
                assertEquals("Form is valid. Nothing has been sent to the server.", ui.<Label>node("feedback").getText());
                assertTrue(ui.<Button>node("submitButton").isDisabled());
                assertEquals(0, ui.fixture.submissions);
                assertTrue(ui.<TableView<?>>node("requestsTable").getItems().isEmpty());
                return null;
            });
        }
    }

    @Test void distinguishesUnloadedFromEmptyAndSynchronizesTableTimelineAndConversations() throws Exception {
        try (Ui ui = load(true)) {
            fx(() -> { assertEquals("—", ui.<Label>node("requestCount").getText()); return null; });
            ui.fixture.emit(new CitizenData.Requests(List.of()));
            fx(() -> { assertEquals("0", ui.<Label>node("requestCount").getText()); return null; });
            ui.fixture.sampleData();
            fx(() -> {
                TableView<CitizenData.RequestRow> table = ui.node("requestsTable");
                assertEquals(2, table.getItems().size());
                assertEquals("2", ui.<Label>node("requestCount").getText());
                assertEquals("1", ui.<Label>node("notificationCount").getText());
                table.getSelectionModel().selectFirst();
                assertTrue(ui.<ListView<String>>node("timeline").getItems().getFirst().contains("Sample status A"));
                assertEquals(2, ui.<ListView<String>>node("timeline").getItems().size());
                assertEquals(ui.<ListView<String>>node("messages").getItems(), ui.<ListView<String>>node("conversation").getItems());
                ComboBox<CitizenData.RequestRow> request = ui.node("messageRequest");
                assertEquals("PREVIEW-001", request.getValue().id());
                request.getSelectionModel().selectLast();
                assertEquals("PREVIEW-002", table.getSelectionModel().getSelectedItem().id());
                assertTrue(ui.<ListView<String>>node("conversation").getItems().isEmpty());
                return null;
            });
        }
    }

    @Test void incomingUpdatesPreserveSelectionAndUnsentMessageDrafts() throws Exception {
        try (Ui ui = load(true)) {
            ui.fixture.sampleData();
            fx(() -> {
                ui.<TableView<CitizenData.RequestRow>>node("requestsTable").getSelectionModel().selectFirst();
                ui.<TextArea>node("messageText").setText("Keep this draft");
                ui.<TextArea>node("conversationText").setText("Keep conversation draft");
                return null;
            });
            ui.fixture.emit(new CitizenData.Requests(List.of(new CitizenData.RequestRow("PREVIEW-001", "Fictional service", Instant.EPOCH, "New sample status"))));
            ui.fixture.emit(new CitizenData.RequestDetails(new CitizenData.Details("PREVIEW-001", "Updated fixture", List.of(), List.of())));
            fx(() -> {
                assertEquals("PREVIEW-001", ui.<TableView<CitizenData.RequestRow>>node("requestsTable").getSelectionModel().getSelectedItem().id());
                assertEquals("Keep this draft", ui.<TextArea>node("messageText").getText());
                assertEquals("Keep conversation draft", ui.<TextArea>node("conversationText").getText());
                assertTrue(ui.<Label>node("detailsLabel").getText().contains("Updated fixture"));
                return null;
            });
        }
    }

    @Test void attachmentSelectionShowsLoadingAndZeroProgressWithoutUploading(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("sample.txt");
        Files.writeString(file, "local sample");
        try (Ui ui = load(true)) {
            ui.fixture.nextAttachment = new CompletableFuture<>();
            fx(() -> {
                ui.controller().selectAttachment(file);
                assertTrue(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertTrue(ui.<Button>node("documentChooseButton").isDisabled());
                return null;
            });
            ui.fixture.nextAttachment.complete(new CitizenData.Attachment(file, "sample.txt", Files.size(file)));
            fx(() -> {
                assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertEquals(ui.<Label>node("attachmentLabel").getText(), ui.<Label>node("documentLabel").getText());
                assertTrue(ui.<Label>node("documentLabel").getText().contains("sample.txt"));
                assertEquals(0, ui.<ProgressBar>node("documentProgress").getProgress());
                assertTrue(ui.<Button>node("submitButton").isDisabled());
                ui.<Button>node("documentRemoveButton").fire();
                assertEquals("No attachment selected", ui.<Label>node("attachmentLabel").getText());
                assertEquals(0, ui.fixture.submissions);
                return null;
            });
        }
    }

    @Test void ackDoesNotPersistAndTimeoutAndDisconnectGiveMeaningfulFeedback() throws Exception {
        try (Ui ui = load(true)) {
            ui.fixture.exerciseAcknowledgement = true;
            ui.fixture.nextReply = new CompletableFuture<>();
            fx(() -> {
                fillDraft(ui);
                // Validate recalculates availability after the test fixture capability change.
                ui.controller().attach(ui.fixture, () -> { });
                ui.<Button>node("submitButton").fire();
                assertTrue(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                return null;
            });
            ui.fixture.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> {
                assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible());
                assertTrue(ui.<TableView<?>>node("requestsTable").getItems().isEmpty());
                assertTrue(ui.<Label>node("feedback").getText().contains("Awaiting authoritative request data"));
                ui.fixture.nextReply = CompletableFuture.failedFuture(new TimeoutException());
                ui.<Button>node("submitButton").fire();
                return null;
            });
            fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains("did not reply in time")); return null; });
            ui.fixture.disconnectEvent();
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled());
                assertTrue(ui.<Label>node("feedback").getText().contains("actions are locked"));
                return null;
            });
        }
    }
}
