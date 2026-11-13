package lk.gramalink.client;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import lk.gramalink.shared.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** DS controls only. Server events, not action replies, replace decision/status data. */
public final class DsDashboardController {
    @FXML private TabPane sections;
    @FXML private VBox workspace;
    @FXML private Button navDashboard, navRequests, navNotifications, navChat;
    @FXML private Label officerName, connectionLabel, feedback, forwardedCount, notificationCount, forwardedCaption, notificationCaption;
    @FXML private Circle connectionDot;
    @FXML private HBox activityRow;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label activityLabel, detailsHeading, detailsDescription, originatingGn, versionLabel;
    @FXML private TableView<DsData.RequestRow> forwardedRequests;
    @FXML private TableColumn<DsData.RequestRow, String> idColumn, citizenColumn, officeColumn, typeColumn, statusColumn, dateColumn, versionColumn;
    @FXML private TableView<DsData.Document> documents;
    @FXML private TableColumn<DsData.Document, String> documentNameColumn, documentStateColumn;
    @FXML private ListView<String> history, notifications, recentActivity, statusBreakdown, conversation;
    @FXML private ComboBox<DsData.RequestRow> chatRequest;
    @FXML private ComboBox<DsData.ActionKind> decisionKind;
    @FXML private TextArea decisionNote, chatText;
    @FXML private Button approveButton, rejectButton, returnButton, sendButton, checkDecisionButton;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Colombo"));
    private final Map<String, DsData.Details> details = new HashMap<>();
    private final Set<String> refreshRequired = new HashSet<>();
    private long bindingEpoch;
    private DsWorkspace gateway;
    private Runnable returnToLogin;
    private boolean active, busy, synchronizing;
    private String renderedId;

    @FXML private void initialize() {
        column(idColumn, DsData.RequestRow::id); column(citizenColumn, DsData.RequestRow::citizen);
        column(officeColumn, DsData.RequestRow::gnOffice); column(typeColumn, DsData.RequestRow::type);
        column(statusColumn, DsData.RequestRow::status); column(dateColumn, row -> row.submittedAt() == null ? null : DATE.format(row.submittedAt()));
        column(versionColumn, row -> row.version() == null ? null : row.version().value());
        column(documentNameColumn, DsData.Document::fileName); column(documentStateColumn, DsData.Document::state);
        forwardedRequests.setPlaceholder(new Label("Forwarded requests have not been loaded from the server."));
        documents.setPlaceholder(new Label("No current-version document metadata loaded."));
        history.setPlaceholder(new Label("No current-version history loaded."));
        notifications.setPlaceholder(new Label("DS notifications have not been loaded."));
        recentActivity.setPlaceholder(new Label("Recent activity appears when current request histories are loaded."));
        statusBreakdown.setPlaceholder(new Label("Status statistics await forwarded-request data."));
        conversation.setPlaceholder(new Label("Select a forwarded request to view its officer conversation."));
        for (ListView<String> list : List.of(history, notifications, recentActivity, statusBreakdown, conversation)) wrapCells(list);
        chatRequest.setConverter(new javafx.util.StringConverter<>() {
            public String toString(DsData.RequestRow row) { return row == null ? "" : row.id() + " | " + display(row.gnOffice()); }
            public DsData.RequestRow fromString(String value) { throw new UnsupportedOperationException(); }
        });
        decisionKind.getItems().setAll(DsData.ActionKind.APPROVE, DsData.ActionKind.REJECT, DsData.ActionKind.RETURN_TO_GN);
        decisionKind.setValue(DsData.ActionKind.APPROVE);
        forwardedRequests.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (synchronizing) return;
            synchronizing = true; chatRequest.getSelectionModel().select(selected); synchronizing = false; renderSelected();
        });
        chatRequest.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (synchronizing) return;
            synchronizing = true;
            if (selected == null) forwardedRequests.getSelectionModel().clearSelection(); else forwardedRequests.getSelectionModel().select(selected);
            synchronizing = false; renderSelected();
        });
        sections.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> highlight(selected.intValue()));
        highlight(0);
        activityRow.visibleProperty().bind(busyIndicator.visibleProperty()); activityRow.managedProperty().bind(activityRow.visibleProperty());
        workspace.setDisable(true);
    }
    void attach(DsWorkspace gateway, Runnable returnToLogin) {
        gateway.requireDsOfficer();
        if (this.gateway != null) this.gateway.setListener(new DsGateway.Listener() { });
        if (this.gateway != null && this.gateway != gateway) resetView();
        final long epoch = ++bindingEpoch; this.gateway = gateway; this.returnToLogin = returnToLogin; active = true;
        officerName.setText(gateway.identity().orElseThrow().displayName()); setBusy(false); status(true); refreshActions();
        gateway.setListener(new DsGateway.Listener() {
            public void onRecovery(ClientReliability.State state) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) recovery(state); }); }
            public void onUpdate(DsData.Update update) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway) && gateway.isDsAuthorized()) apply(update); }); }
            public void onStatus(NetworkClient.Status value) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) status(value == NetworkClient.Status.CONNECTED && gateway.isDsAuthorized()); }); }
            public void onError(String error) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) feedback.setText(error); }); }
        });
    }
    @FXML private void showDashboard() { sections.getSelectionModel().select(0); }
    @FXML private void showRequests() { sections.getSelectionModel().select(1); }
    @FXML private void showNotifications() { sections.getSelectionModel().select(2); }
    @FXML private void showChat() { sections.getSelectionModel().select(3); }
    private void highlight(int selected) {
        List<Button> buttons = List.of(navDashboard, navRequests, navNotifications, navChat);
        for (int i = 0; i < buttons.size(); i++) { buttons.get(i).getStyleClass().remove("nav-current"); if (i == selected) buttons.get(i).getStyleClass().add("nav-current"); }
    }
    private void resetView() {
        details.clear(); refreshRequired.clear(); forwardedRequests.getItems().clear(); chatRequest.getItems().clear(); documents.getItems().clear();
        history.getItems().clear(); conversation.getItems().clear(); notifications.getItems().clear(); statusBreakdown.getItems().clear(); recentActivity.getItems().clear();
        decisionNote.clear(); chatText.clear(); renderedId = null;
        forwardedCount.setText("\u2014"); notificationCount.setText("\u2014"); forwardedCaption.setText("Awaiting server data"); notificationCaption.setText("Awaiting server data");
    }
    private boolean bindingCurrent(long epoch, DsWorkspace source) {
        return active && bindingEpoch == epoch && gateway == source;
    }
    private void recovery(ClientReliability.State state) {
        if (state == ClientReliability.State.ONLINE && gateway.isDsAuthorized()) { status(true); return; }
        status(false);
        switch (state) {
            case CONNECTING, RECONNECTING -> {
                connectionLabel.setText(state == ClientReliability.State.CONNECTING ? "Connecting..." : "Reconnecting...");
                connectionDot.setStyle("-fx-fill: #d59a22;");
                feedback.setText("Connection recovery is in progress. Actions are locked; fresh server sign-in is required.");
            }
            case AUTHENTICATION_REQUIRED, ONLINE -> {
                connectionLabel.setText("Connected - fresh sign-in required");
                feedback.setText("The socket is connected. Return to sign in for fresh server authorization; previous data is not reloaded automatically.");
            }
            case OFFLINE -> connectionLabel.setText("Offline - return to sign in");
        }
    }
    private void status(boolean online) {
        connectionDot.setStyle("");
        connectionLabel.setText(online ? "Connected" : "Disconnected - DS sign-in required");
        connectionDot.getStyleClass().removeAll("online", "offline"); connectionDot.getStyleClass().add(online ? "online" : "offline");
        workspace.setDisable(!online);
        if (!online) { setBusy(false); feedback.setText("DS authorization is unavailable. Decisions are locked; return to sign in."); }
    }
    private boolean can(DsData.ActionKind kind, boolean selected) {
        DsData.RequestRow row = forwardedRequests.getSelectionModel().getSelectedItem();
        return selected && gateway.supports(kind) && (!gateway.requiresVersion(kind) || row.version() != null);
    }
    private void refreshActions() {
        DsData.RequestRow row = forwardedRequests.getSelectionModel().getSelectedItem();
        boolean selected = gateway != null && gateway.isDsAuthorized() && row != null && !busy;
        DsData.Details current = row == null ? null : currentDetails(row);
        boolean ready = selected && current != null && !refreshRequired.contains(row.id());
        approveButton.setDisable(!can(DsData.ActionKind.APPROVE, ready)); rejectButton.setDisable(!can(DsData.ActionKind.REJECT, ready));
        returnButton.setDisable(!can(DsData.ActionKind.RETURN_TO_GN, ready)); sendButton.setDisable(!can(DsData.ActionKind.CHAT, ready));
        checkDecisionButton.setDisable(!selected);
        forwardedRequests.setDisable(busy); chatRequest.setDisable(busy); decisionKind.setDisable(busy); decisionNote.setDisable(busy); chatText.setDisable(busy);
    }
    private DsData.Action action(DsData.ActionKind kind) {
        DsData.RequestRow row = forwardedRequests.getSelectionModel().getSelectedItem();
        DsData.Action action = new DsData.Action(kind, row == null ? null : row.id(), row == null ? null : row.version(),
                kind == DsData.ActionKind.CHAT ? chatText.getText() : decisionNote.getText());
        if (gateway.requiresVersion(kind) && action.expectedVersion() == null) throw new IllegalArgumentException("The current server request version is required. Reload request data.");
        if (currentDetails(row) == null) throw new IllegalArgumentException("Load request details matching the current server version before deciding.");
        if (refreshRequired.contains(row.id())) throw new IllegalArgumentException("Reload authoritative request data after the conflict or permission error.");
        return action;
    }
    @FXML private void checkDecision() {
        try { gateway.requireDsOfficer(); action(decisionKind.getValue()); feedback.setText("Decision fields are valid. Nothing has been sent or changed."); }
        catch (IllegalArgumentException | SecurityException error) { feedback.setText(error.getMessage()); }
    }
    @FXML private void approve() { perform(DsData.ActionKind.APPROVE); }
    @FXML private void reject() { perform(DsData.ActionKind.REJECT); }
    @FXML private void returnToGn() { perform(DsData.ActionKind.RETURN_TO_GN); }
    @FXML private void sendMessage() { perform(DsData.ActionKind.CHAT); }
    private void perform(DsData.ActionKind kind) {
        final long epoch = bindingEpoch; final DsWorkspace source = gateway;
        try {
            gateway.requireDsOfficer(); DsData.Action action = action(kind); setBusy(true);
            gateway.perform(action).whenComplete((reply, error) -> Platform.runLater(() -> {
                if (!bindingCurrent(epoch, source)) return;
                setBusy(false);
                if (!source.isDsAuthorized()) { status(false); return; }
                if (error != null) {
                    if (unwrap(error) instanceof TimeoutException) refreshRequired.add(action.requestId());
                    feedback.setText(errorMessage(error));
                } else if (reply.type() == MessageType.ERROR) {
                    String code = reply.payload().path("code").asText();
                    if (code.equals("CONFLICT") || code.equals("FORBIDDEN")) refreshRequired.add(action.requestId());
                    feedback.setText(LoginService.describe(reply) + (refreshRequired.contains(action.requestId())
                            ? " Reload authoritative request data before retrying. No local decision was applied." : " No local decision was applied."));
                } else feedback.setText("Server reply: " + reply.type() + ". Awaiting authoritative status/version data; no local decision was applied.");
                refreshActions();
            }));
        } catch (IllegalArgumentException | SecurityException error) { setBusy(false); feedback.setText(error.getMessage()); }
    }
    private void setBusy(boolean value) { busy = value; busyIndicator.setVisible(value); activityLabel.setText(value ? "Waiting for the DS service…" : ""); refreshActions(); }

    private void apply(DsData.Update update) {
        if (update instanceof DsData.Requests snapshot) {
            DsData.RequestRow previous = forwardedRequests.getSelectionModel().getSelectedItem();
            String id = previous == null ? null : previous.id();
            synchronizing = true;
            forwardedRequests.getItems().setAll(snapshot.rows()); chatRequest.getItems().setAll(snapshot.rows());
            details.keySet().retainAll(snapshot.rows().stream().map(DsData.RequestRow::id).toList());
            refreshRequired.clear();
            DsData.RequestRow selected = snapshot.rows().stream().filter(row -> Objects.equals(row.id(), id)).findFirst().orElse(null);
            forwardedRequests.getSelectionModel().select(selected); chatRequest.getSelectionModel().select(selected); synchronizing = false;
            if (previous != null && selected != null && !Objects.equals(previous.version(), selected.version()))
                feedback.setText("The server supplied a different request version. Review matching details before making a decision.");
            forwardedRequests.setPlaceholder(new Label("No forwarded requests returned by the server."));
            forwardedCount.setText(Integer.toString(snapshot.rows().size())); forwardedCaption.setText("Current forwarded requests received from server");
            Map<String, Long> counts = new TreeMap<>(); snapshot.rows().forEach(row -> counts.merge(display(row.status()), 1L, Long::sum));
            statusBreakdown.getItems().setAll(counts.entrySet().stream().map(entry -> entry.getKey() + "  ·  " + entry.getValue()).toList());
            statusBreakdown.setPlaceholder(new Label("No forwarded requests returned by the server.")); renderSelected(); renderActivity();
        } else if (update instanceof DsData.RequestDetails data) {
            details.put(data.details().requestId(), data.details()); renderSelected(); renderActivity();
        } else if (update instanceof DsData.Notifications data) {
            notifications.getItems().setAll(data.entries().stream().map(entry -> DATE.format(entry.at()) + "\n" + entry.text()
                    + (entry.requestId() == null ? "" : "\nRequest " + entry.requestId())).toList());
            notifications.setPlaceholder(new Label("No DS notifications returned by the server.")); notificationCount.setText(Integer.toString(data.entries().size()));
            notificationCaption.setText("Notifications received from server");
        }
    }
    private DsData.Details currentDetails(DsData.RequestRow row) {
        if (row == null) return null;
        DsData.Details data = details.get(row.id()); return data != null && Objects.equals(data.version(), row.version()) ? data : null;
    }
    private void renderSelected() {
        DsData.RequestRow row = forwardedRequests.getSelectionModel().getSelectedItem();
        String id = row == null ? null : row.id();
        if (!Objects.equals(id, renderedId)) { decisionNote.clear(); chatText.clear(); }
        renderedId = id; documents.getItems().clear(); history.getItems().clear(); conversation.getItems().clear();
        DsData.Details data = currentDetails(row);
        detailsHeading.setText(row == null ? "Select a forwarded request" : row.id() + " | " + display(row.citizen()));
        versionLabel.setText("Current server version: " + (row == null || row.version() == null ? "Not supplied" : row.version().value()));
        originatingGn.setText("Originating GN officer: " + (data == null ? "Not loaded for this version" : display(data.originatingGnOfficer())));
        detailsDescription.setText(data == null ? "Details matching the current request version have not been loaded." : display(data.description()));
        if (data != null) {
            documents.getItems().setAll(data.documents());
            history.getItems().setAll(data.history().stream().map(entry -> DATE.format(entry.at()) + " | " + entry.status() + "\n" + entry.description()).toList());
            conversation.getItems().setAll(data.messages().stream().map(entry -> DATE.format(entry.at()) + " | " + entry.sender() + "\n" + entry.text()).toList());
        }
        refreshActions();
    }
    private record Activity(Instant at, String text) { }
    private void renderActivity() {
        List<Activity> entries = new ArrayList<>();
        for (DsData.RequestRow row : forwardedRequests.getItems()) {
            DsData.Details data = currentDetails(row);
            if (data != null) for (DsData.History item : data.history()) entries.add(new Activity(item.at(), DATE.format(item.at()) + " | " + row.id() + "\n" + item.status() + " - " + item.description()));
        }
        recentActivity.getItems().setAll(entries.stream().sorted(Comparator.comparing(Activity::at).reversed()).map(Activity::text).toList());
    }
    @FXML private void signOut() {
        bindingEpoch++; active = false; workspace.setDisable(true); gateway.setListener(new DsGateway.Listener() { });
        gateway.disconnect().whenComplete((ignored, error) -> Platform.runLater(returnToLogin));
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause(); return error;
    }
    static String errorMessage(Throwable error) {
        error = unwrap(error);
        if (error instanceof TimeoutException) return "The DS server did not reply in time. Outcome is unconfirmed; reload authoritative status/version before retrying.";
        if (error instanceof UnsupportedOperationException) return "This DS action is not available on the server yet. No local decision was applied.";
        if (error instanceof SecurityException) return "DS authorization or request access is unavailable. Return to sign in or reload permitted requests.";
        if (error instanceof IllegalArgumentException) return error.getMessage();
        return "The DS operation could not be completed. Check the connection; the outcome is unconfirmed.";
    }
    private static String display(String value) { return value == null || value.isBlank() ? "Not supplied" : value; }
    private static <T> void column(TableColumn<T, String> column, Function<T, String> value) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(display(value.apply(cell.getValue()))));
    }
    private static void wrapCells(ListView<String> list) {
        list.setCellFactory(view -> new ListCell<>() {
            private final Label label = new Label();
            { label.setWrapText(true); label.maxWidthProperty().bind(list.widthProperty().subtract(36)); }
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty); setText(null); label.setText(empty ? null : value); setGraphic(empty ? null : label);
            }
        });
    }
}
