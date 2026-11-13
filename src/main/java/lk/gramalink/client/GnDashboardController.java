package lk.gramalink.client;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import lk.gramalink.shared.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** GN view state. Decisions and documents change only on authoritative decoded updates. */
public final class GnDashboardController {
    @FXML private TabPane sections;
    @FXML private VBox workspace;
    @FXML private Button navDashboard, navRequests, navNotifications, navMessages;
    @FXML private Label officerName, connectionLabel, feedback, assignedCount, notificationCount, assignedCaption, notificationCaption;
    @FXML private Circle connectionDot;
    @FXML private HBox activityRow;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label activityLabel, detailsHeading, detailsDescription;
    @FXML private TableView<GnData.RequestRow> assignedRequests;
    @FXML private TableColumn<GnData.RequestRow, String> idColumn, citizenColumn, typeColumn, dateColumn, statusColumn, priorityColumn;
    @FXML private TableView<GnData.Document> documents;
    @FXML private TableColumn<GnData.Document, String> documentNameColumn, verificationColumn;
    @FXML private ListView<String> history, notifications, recentNotifications, statusBreakdown, conversation;
    @FXML private ComboBox<GnData.RequestRow> messageRequest;
    @FXML private ComboBox<GnData.ActionKind> actionKind;
    @FXML private TextArea actionNote, chatText;
    @FXML private Button verifyButton, informationButton, forwardButton, rejectButton, sendButton, checkActionButton;
    private final Map<String, GnData.Details> details = new HashMap<>();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Colombo"));
    private GnWorkspace gateway;
    private Runnable returnToLogin;
    private boolean active, busy, synchronizing;
    private String renderedRequestId;

    @FXML private void initialize() {
        column(idColumn, GnData.RequestRow::id); column(citizenColumn, GnData.RequestRow::citizen);
        column(typeColumn, GnData.RequestRow::type); column(statusColumn, GnData.RequestRow::status);
        column(dateColumn, row -> DATE.format(row.submittedAt())); column(priorityColumn, GnData.RequestRow::priority);
        column(documentNameColumn, GnData.Document::fileName); column(verificationColumn, GnData.Document::verificationStatus);
        assignedRequests.setPlaceholder(new Label("Assigned requests have not been loaded from the server."));
        documents.setPlaceholder(new Label("No supporting documents loaded for this request."));
        history.setPlaceholder(new Label("No request history loaded."));
        notifications.setPlaceholder(new Label("Officer notifications have not been loaded."));
        recentNotifications.setItems(notifications.getItems());
        recentNotifications.setPlaceholder(new Label("Officer notifications have not been loaded."));
        statusBreakdown.setPlaceholder(new Label("Status counts will appear after assigned requests are loaded."));
        conversation.setPlaceholder(new Label("Choose an assigned request to view its conversation."));
        for (ListView<String> list : List.of(history, notifications, recentNotifications, conversation, statusBreakdown)) wrapCells(list);
        messageRequest.setConverter(new javafx.util.StringConverter<>() {
            public String toString(GnData.RequestRow row) { return row == null ? "" : row.id() + " | " + row.citizen(); }
            public GnData.RequestRow fromString(String text) { throw new UnsupportedOperationException(); }
        });
        actionKind.getItems().setAll(GnData.ActionKind.VERIFY_DOCUMENT, GnData.ActionKind.REQUEST_INFORMATION,
                GnData.ActionKind.FORWARD_TO_DS, GnData.ActionKind.REJECT_REQUEST);
        actionKind.setValue(GnData.ActionKind.REQUEST_INFORMATION);
        assignedRequests.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (synchronizing) return;
            synchronizing = true; messageRequest.getSelectionModel().select(selected); synchronizing = false;
            renderSelected();
        });
        messageRequest.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (synchronizing) return;
            synchronizing = true;
            if (selected == null) assignedRequests.getSelectionModel().clearSelection(); else assignedRequests.getSelectionModel().select(selected);
            synchronizing = false; renderSelected();
        });
        documents.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> refreshActions());
        sections.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> highlight(selected.intValue()));
        highlight(0);
        activityRow.visibleProperty().bind(busyIndicator.visibleProperty());
        activityRow.managedProperty().bind(activityRow.visibleProperty());
        workspace.setDisable(true);
    }

    void attach(GnWorkspace gateway, Runnable returnToLogin) {
        gateway.requireGnOfficer();
        this.gateway = gateway; this.returnToLogin = returnToLogin; active = true;
        officerName.setText(gateway.identity().orElseThrow().displayName());
        status(true); refreshActions();
        gateway.setListener(new GnGateway.Listener() {
            public void onUpdate(GnData.Update update) { Platform.runLater(() -> { if (active && gateway.isGnAuthorized()) apply(update); }); }
            public void onStatus(NetworkClient.Status value) { Platform.runLater(() -> {
                if (active) status(value == NetworkClient.Status.CONNECTED && gateway.isGnAuthorized());
            }); }
            public void onError(String error) { Platform.runLater(() -> { if (active) feedback.setText(error); }); }
        });
    }
    @FXML private void showDashboard() { sections.getSelectionModel().select(0); }
    @FXML private void showRequests() { sections.getSelectionModel().select(1); }
    @FXML private void showNotifications() { sections.getSelectionModel().select(2); }
    @FXML private void showMessages() { sections.getSelectionModel().select(3); }
    private void highlight(int selected) {
        List<Button> navigation = List.of(navDashboard, navRequests, navNotifications, navMessages);
        for (int i = 0; i < navigation.size(); i++) {
            navigation.get(i).getStyleClass().remove("nav-current");
            if (selected == i) navigation.get(i).getStyleClass().add("nav-current");
        }
    }
    private void status(boolean online) {
        connectionLabel.setText(online ? "Connected" : "Disconnected - GN sign-in required");
        connectionDot.getStyleClass().removeAll("online", "offline");
        connectionDot.getStyleClass().add(online ? "online" : "offline");
        workspace.setDisable(!online);
        if (!online) { setBusy(false); feedback.setText("Officer authorization is unavailable. Actions are locked; return to sign in."); }
    }
    private void refreshActions() {
        boolean assigned = gateway != null && gateway.isGnAuthorized() && assignedRequests.getSelectionModel().getSelectedItem() != null && !busy;
        verifyButton.setDisable(!assigned || documents.getSelectionModel().getSelectedItem() == null || !gateway.supports(GnData.ActionKind.VERIFY_DOCUMENT));
        informationButton.setDisable(!assigned || !gateway.supports(GnData.ActionKind.REQUEST_INFORMATION));
        forwardButton.setDisable(!assigned || !gateway.supports(GnData.ActionKind.FORWARD_TO_DS));
        rejectButton.setDisable(!assigned || !gateway.supports(GnData.ActionKind.REJECT_REQUEST));
        sendButton.setDisable(!assigned || !gateway.supports(GnData.ActionKind.CHAT));
        checkActionButton.setDisable(!assigned);
        assignedRequests.setDisable(busy); documents.setDisable(busy); messageRequest.setDisable(busy);
        actionKind.setDisable(busy); actionNote.setDisable(busy); chatText.setDisable(busy);
    }
    private GnData.Action action(GnData.ActionKind kind) {
        GnData.RequestRow selected = assignedRequests.getSelectionModel().getSelectedItem();
        GnData.Document document = documents.getSelectionModel().getSelectedItem();
        return new GnData.Action(kind, selected == null ? null : selected.id(), document == null ? null : document.id(),
                kind == GnData.ActionKind.CHAT ? chatText.getText() : actionNote.getText());
    }
    @FXML private void checkAction() {
        try {
            gateway.requireGnOfficer(); action(actionKind.getValue());
            feedback.setText("Action fields are valid. Nothing has been sent or changed.");
        } catch (IllegalArgumentException | SecurityException error) { feedback.setText(error.getMessage()); }
    }
    @FXML private void verifyDocument() { perform(GnData.ActionKind.VERIFY_DOCUMENT); }
    @FXML private void requestInformation() { perform(GnData.ActionKind.REQUEST_INFORMATION); }
    @FXML private void forwardToDs() { perform(GnData.ActionKind.FORWARD_TO_DS); }
    @FXML private void rejectRequest() { perform(GnData.ActionKind.REJECT_REQUEST); }
    @FXML private void sendMessage() { perform(GnData.ActionKind.CHAT); }
    private void perform(GnData.ActionKind kind) {
        try {
            gateway.requireGnOfficer();
            GnData.Action request = action(kind);
            setBusy(true);
            gateway.perform(request).whenComplete((reply, error) -> Platform.runLater(() -> {
                if (!active) return;
                setBusy(false);
                if (!gateway.isGnAuthorized()) { status(false); return; }
                // Never mutate status, documents, history or chat based on a write/ACK alone.
                feedback.setText(error != null ? errorMessage(error) : reply.type() == MessageType.ERROR
                        ? LoginService.describe(reply) : "Server reply: " + reply.type() + ". Awaiting authoritative request data; no local status change applied.");
            }));
        } catch (IllegalArgumentException | SecurityException error) { setBusy(false); feedback.setText(error.getMessage()); }
    }
    private void setBusy(boolean value) {
        busy = value; busyIndicator.setVisible(value); activityLabel.setText(value ? "Waiting for the officer service…" : ""); refreshActions();
    }

    private void apply(GnData.Update update) {
        if (update instanceof GnData.Requests data) {
            String selectedId = Optional.ofNullable(assignedRequests.getSelectionModel().getSelectedItem()).map(GnData.RequestRow::id).orElse(null);
            synchronizing = true;
            assignedRequests.getItems().setAll(data.rows()); messageRequest.getItems().setAll(data.rows());
            details.keySet().retainAll(data.rows().stream().map(GnData.RequestRow::id).toList());
            GnData.RequestRow selected = data.rows().stream().filter(row -> Objects.equals(row.id(), selectedId)).findFirst().orElse(null);
            assignedRequests.getSelectionModel().select(selected); messageRequest.getSelectionModel().select(selected);
            synchronizing = false;
            assignedRequests.setPlaceholder(new Label("No assigned requests returned by the server."));
            assignedCount.setText(Integer.toString(data.rows().size())); assignedCaption.setText("Current assignments received from server");
            Map<String, Long> counts = new TreeMap<>();
            data.rows().forEach(row -> counts.merge(display(row.status()), 1L, Long::sum));
            statusBreakdown.getItems().setAll(counts.entrySet().stream().map(entry -> entry.getKey() + "  ·  " + entry.getValue()).toList());
            statusBreakdown.setPlaceholder(new Label("No assigned requests returned by the server."));
            renderSelected();
        } else if (update instanceof GnData.RequestDetails data) {
            details.put(data.details().requestId(), data.details()); renderSelected();
        } else if (update instanceof GnData.Notifications data) {
            notifications.getItems().setAll(data.entries().stream().map(entry -> DATE.format(entry.at()) + "\n" + entry.text()
                    + (entry.requestId() == null ? "" : "\nRequest " + entry.requestId())).toList());
            notifications.setPlaceholder(new Label("No officer notifications returned by the server."));
            recentNotifications.setPlaceholder(new Label("No officer notifications returned by the server."));
            notificationCount.setText(Integer.toString(data.entries().size())); notificationCaption.setText("Notifications received from server");
        }
    }
    private void renderSelected() {
        GnData.RequestRow row = assignedRequests.getSelectionModel().getSelectedItem();
        String selectedId = row == null ? null : row.id();
        if (!Objects.equals(selectedId, renderedRequestId)) { actionNote.clear(); chatText.clear(); }
        renderedRequestId = selectedId;
        String documentId = Optional.ofNullable(documents.getSelectionModel().getSelectedItem()).map(GnData.Document::id).orElse(null);
        documents.getItems().clear(); history.getItems().clear(); conversation.getItems().clear();
        GnData.Details data = selectedId == null ? null : details.get(selectedId);
        detailsHeading.setText(row == null ? "Select an assigned request" : row.id() + "  |  " + row.citizen());
        detailsDescription.setText(data == null ? "Request details have not been loaded from the server." : data.description());
        if (data != null) {
            documents.getItems().setAll(data.documents());
            data.documents().stream().filter(document -> Objects.equals(document.id(), documentId)).findFirst().ifPresent(document -> documents.getSelectionModel().select(document));
            history.getItems().setAll(data.history().stream().map(entry -> DATE.format(entry.at()) + "  |  " + entry.status() + "\n" + entry.description()).toList());
            conversation.getItems().setAll(data.messages().stream().map(entry -> DATE.format(entry.at()) + "  |  " + entry.sender() + "\n" + entry.text()).toList());
        }
        refreshActions();
    }
    @FXML private void signOut() {
        active = false; workspace.setDisable(true); gateway.setListener(new GnGateway.Listener() { });
        gateway.disconnect().whenComplete((ignored, error) -> Platform.runLater(returnToLogin));
    }
    static String errorMessage(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof TimeoutException) return "The server did not reply in time. Request status is unconfirmed; check authoritative data before retrying.";
        if (error instanceof UnsupportedOperationException) return "This officer action is not available on the server yet. No local status change was applied.";
        if (error instanceof SecurityException) return "GN Officer authorization expired. Return to sign in.";
        if (error instanceof IllegalArgumentException) return error.getMessage();
        return "The officer operation could not be completed. Check the connection; request status remains unconfirmed.";
    }
    private static String display(String value) { return value == null || value.isBlank() ? "Not supplied" : value; }
    private static <T> void column(TableColumn<T, String> column, Function<T, String> value) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(display(value.apply(cell.getValue()))));
    }
    private static void wrapCells(ListView<String> list) {
        list.setCellFactory(view -> new ListCell<>() {
            private final Label label = new Label();
            { label.setWrapText(true); label.maxWidthProperty().bind(list.widthProperty().subtract(36)); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty); setText(null); label.setText(empty ? null : item); setGraphic(empty ? null : label);
            }
        });
    }
}
