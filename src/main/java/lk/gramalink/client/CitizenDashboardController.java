package lk.gramalink.client;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import lk.gramalink.shared.Message;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import javafx.util.StringConverter;

/** FX controls and local drafts only. All server data comes through CitizenProtocol. */
public final class CitizenDashboardController {
    @FXML private TabPane sections;
    @FXML private Tab overviewTab, newRequestTab, requestsTab, notificationsTab, documentsTab, messagesTab, profileTab;
    @FXML private VBox workspace;
    @FXML private Label citizenName, connectionLabel, feedback, requestCount, notificationCount;
    @FXML private Label requestCountCaption, notificationCountCaption, overviewHint;
    @FXML private Circle connectionDot;
    @FXML private TextField requestType, division;
    @FXML private TextArea description, messageText, conversationText;
    @FXML private Button submitButton, sendButton, conversationSendButton, chooseAttachmentButton, removeAttachmentButton, validateButton;
    @FXML private Button documentChooseButton, documentRemoveButton;
    @FXML private Button navDashboard, navNewRequest, navRequests, navNotifications, navDocuments, navMessages, navProfile;
    @FXML private Label attachmentLabel, transferLabel, detailsLabel, documentLabel, documentProgressLabel, profileName, profileUserId, activityLabel;
    @FXML private ProgressBar transferProgress, documentProgress;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private HBox activityRow;
    @FXML private ComboBox<CitizenData.RequestRow> messageRequest;
    @FXML private TableView<CitizenData.RequestRow> requestsTable;
    @FXML private TableColumn<CitizenData.RequestRow, String> idColumn, typeColumn, dateColumn, statusColumn;
    @FXML private ListView<String> timeline, messages, notifications, conversation;
    private final Map<String, CitizenData.Details> details = new HashMap<>();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Colombo"));
    private CitizenWorkspace gateway;
    private Runnable returnToLogin;
    private CitizenData.Attachment attachment;
    private boolean busy;
    private boolean active;
    private long selectionVersion;
    private boolean synchronizingSelection;

    @FXML private void initialize() {
        idColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().id()));
        typeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().type()));
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(DATE.format(cell.getValue().submittedAt())));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status()));
        requestsTable.setPlaceholder(new Label("Requests have not been loaded from the server."));
        timeline.setPlaceholder(new Label("No status history loaded."));
        messages.setPlaceholder(new Label("No request messages loaded."));
        notifications.setPlaceholder(new Label("Notifications have not been loaded from the server."));
        conversation.setItems(messages.getItems());
        conversation.setPlaceholder(new Label("Select a request to view its messages."));
        for (ListView<String> list : List.of(timeline, messages, notifications, conversation)) wrapCells(list);
        sections.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> highlightNavigation(selected.intValue()));
        highlightNavigation(sections.getSelectionModel().getSelectedIndex());
        messageRequest.setConverter(new StringConverter<>() {
            public String toString(CitizenData.RequestRow row) { return row == null ? "" : row.id() + " | " + row.type(); }
            public CitizenData.RequestRow fromString(String value) { throw new UnsupportedOperationException(); }
        });
        requestsTable.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (!synchronizingSelection && !Objects.equals(old == null ? null : old.id(), selected == null ? null : selected.id())) {
                messageText.clear(); conversationText.clear();
            }
            if (!synchronizingSelection) {
                synchronizingSelection = true;
                messageRequest.getSelectionModel().select(selected);
                synchronizingSelection = false;
            }
            showDetails();
        });
        messageRequest.getSelectionModel().selectedItemProperty().addListener((observable, old, selected) -> {
            if (synchronizingSelection) return;
            messageText.clear(); conversationText.clear();
            synchronizingSelection = true;
            if (selected == null) requestsTable.getSelectionModel().clearSelection();
            else requestsTable.getSelectionModel().select(selected);
            synchronizingSelection = false;
        });
        busyIndicator.managedProperty().bind(busyIndicator.visibleProperty());
        activityLabel.managedProperty().bind(activityLabel.visibleProperty());
        activityRow.visibleProperty().bind(busyIndicator.visibleProperty());
        activityRow.managedProperty().bind(activityRow.visibleProperty());
        documentChooseButton.disableProperty().bind(chooseAttachmentButton.disableProperty());
        documentRemoveButton.disableProperty().bind(removeAttachmentButton.disableProperty());
        workspace.setDisable(true);
    }

    void attach(CitizenWorkspace gateway, Runnable returnToLogin) {
        gateway.requireCitizen();
        this.gateway = gateway;
        this.returnToLogin = returnToLogin;
        active = true;
        CitizenData.Identity identity = gateway.identity().orElseThrow();
        citizenName.setText(identity.displayName());
        profileName.setText(identity.displayName());
        profileUserId.setText(identity.userId());
        workspace.setDisable(false);
        status(true);
        refreshActions();
        gateway.setListener(new CitizenGateway.Listener() {
            public void onUpdate(CitizenData.Update update) { Platform.runLater(() -> { if (active && gateway.isCitizenAuthorized()) apply(update); }); }
            public void onStatus(NetworkClient.Status value) { Platform.runLater(() -> { if (active) status(value == NetworkClient.Status.CONNECTED && gateway.isCitizenAuthorized()); }); }
            public void onError(String error) { Platform.runLater(() -> { if (active) feedback.setText(error); }); }
        });
    }

    private void status(boolean online) {
        connectionLabel.setText(online ? "Connected" : "Disconnected - sign in again");
        connectionDot.getStyleClass().removeAll("online", "offline");
        connectionDot.getStyleClass().add(online ? "online" : "offline");
        workspace.setDisable(!online);
        if (!online) {
            setBusy(false, "");
            feedback.setText("The server connection was lost. Citizen actions are locked.");
        }
    }
    private void refreshActions() {
        boolean authorized = gateway != null && gateway.isCitizenAuthorized();
        submitButton.setDisable(busy || !authorized || attachment != null || !gateway.supports(CitizenProtocol.Operation.CREATE_REQUEST));
        sendButton.setDisable(busy || !authorized || requestsTable.getSelectionModel().getSelectedItem() == null || !gateway.supports(CitizenProtocol.Operation.CHAT_MESSAGE));
        conversationSendButton.setDisable(sendButton.isDisable());
        chooseAttachmentButton.setDisable(busy || !authorized);
        removeAttachmentButton.setDisable(busy || attachment == null);
        validateButton.setDisable(busy || !authorized);
    }

    @FXML private void showOverview() { sections.getSelectionModel().select(overviewTab); }
    @FXML private void showNewRequest() { sections.getSelectionModel().select(newRequestTab); }
    @FXML private void showRequests() { sections.getSelectionModel().select(requestsTab); }
    @FXML private void showNotifications() { sections.getSelectionModel().select(notificationsTab); }
    @FXML private void showDocuments() { sections.getSelectionModel().select(documentsTab); }
    @FXML private void showMessages() { sections.getSelectionModel().select(messagesTab); }
    @FXML private void showProfile() { sections.getSelectionModel().select(profileTab); }

    private void highlightNavigation(int selected) {
        List<Button> navigation = List.of(navDashboard, navNewRequest, navRequests, navNotifications, navDocuments, navMessages, navProfile);
        for (int i = 0; i < navigation.size(); i++) {
            navigation.get(i).getStyleClass().remove("nav-current");
            if (i == selected) navigation.get(i).getStyleClass().add("nav-current");
        }
    }

    private static void wrapCells(ListView<String> list) {
        list.setCellFactory(view -> new ListCell<>() {
            private final Label label = new Label();
            {
                label.setWrapText(true);
                label.maxWidthProperty().bind(list.widthProperty().subtract(36));
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                label.setText(empty ? null : item);
                setGraphic(empty ? null : label);
            }
        });
    }

    private CitizenData.Draft readDraft() {
        return new CitizenData.Draft(requestType.getText(), division.getText(), description.getText(), attachment);
    }
    @FXML private void validateDraft() {
        try {
            gateway.requireCitizen();
            readDraft();
            feedback.setText("Form is valid. Nothing has been sent to the server.");
        } catch (IllegalArgumentException | SecurityException error) { feedback.setText(error.getMessage()); }
    }

    @FXML private void submitRequest() {
        try {
            gateway.requireCitizen();
            var draft = readDraft();
            complete(gateway.createRequest(draft), "Server reply");
        } catch (IllegalArgumentException | SecurityException error) { feedback.setText(error.getMessage()); }
    }
    @FXML private void sendMessage() {
        sendText(messageText.getText());
    }
    @FXML private void sendConversation() {
        sendText(conversationText.getText());
    }
    private void sendText(String text) {
        CitizenData.RequestRow selected = requestsTable.getSelectionModel().getSelectedItem();
        if (selected == null) { feedback.setText("Select a request first."); return; }
        complete(gateway.sendMessage(selected.id(), text), "Message reply");
    }
    private void complete(CompletableFuture<Message> operation, String label) {
        setBusy(true, "Waiting for the server…");
        operation.whenComplete((reply, error) -> Platform.runLater(() -> {
            if (!active) return;
            setBusy(false, "");
            // ACK does not add a local request, message, or successful application status.
            feedback.setText(error != null ? operationError(error)
                    : label + ": " + (reply.type() == lk.gramalink.shared.MessageType.ERROR
                    ? LoginService.describe(reply) : reply.type() + ". Awaiting authoritative request data."));
        }));
    }

    @FXML private void chooseAttachment() {
        if (busy || !gateway.isCitizenAuthorized()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a supporting document");
        java.io.File file = chooser.showOpenDialog(chooseAttachmentButton.getScene().getWindow());
        if (file == null) return;
        selectAttachment(file.toPath());
    }
    void selectAttachment(Path path) {
        if (!active || !gateway.isCitizenAuthorized()) return;
        long version = ++selectionVersion;
        setBusy(true, "Checking the selected file…");
        gateway.inspectAttachment(path).whenComplete((selected, error) -> Platform.runLater(() -> {
            if (!active || version != selectionVersion) return;
            setBusy(false, "");
            if (error != null) feedback.setText("The selected file is not readable.");
            else {
                attachment = selected;
                attachmentLabel.setText(selected.fileName() + "  |  " + selected.totalBytes() + " bytes");
                documentLabel.setText(attachmentLabel.getText());
                transferProgress.setProgress(0);
                documentProgress.setProgress(0);
                transferLabel.setText("Selected locally. Upload awaits backend authorization.");
                documentProgressLabel.setText(transferLabel.getText());
            }
            refreshActions();
        }));
    }
    @FXML private void removeAttachment() {
        if (busy) return;
        selectionVersion++;
        attachment = null;
        attachmentLabel.setText("No attachment selected");
        documentLabel.setText(attachmentLabel.getText());
        transferProgress.setProgress(0);
        documentProgress.setProgress(0);
        transferLabel.setText("No transfer started");
        documentProgressLabel.setText(transferLabel.getText());
        refreshActions();
    }

    private void apply(CitizenData.Update update) {
        if (update instanceof CitizenData.Requests data) {
            String selectedId = Optional.ofNullable(requestsTable.getSelectionModel().getSelectedItem()).map(CitizenData.RequestRow::id).orElse(null);
            synchronizingSelection = true;
            requestsTable.getItems().setAll(data.rows());
            messageRequest.getItems().setAll(data.rows());
            details.keySet().retainAll(data.rows().stream().map(CitizenData.RequestRow::id).toList());
            CitizenData.RequestRow selected = data.rows().stream().filter(row -> Objects.equals(row.id(), selectedId)).findFirst().orElse(null);
            if (selected == null) { messageText.clear(); conversationText.clear(); }
            requestsTable.getSelectionModel().select(selected);
            messageRequest.getSelectionModel().select(selected);
            synchronizingSelection = false;
            requestsTable.setPlaceholder(new Label("No requests returned by the server."));
            requestCount.setText(Integer.toString(data.rows().size()));
            requestCountCaption.setText("Records received from server");
            overviewHint.setText("Open My requests to view the information received from your server.");
            showDetails();
        } else if (update instanceof CitizenData.RequestDetails data) {
            details.put(data.details().requestId(), data.details()); showDetails();
        } else if (update instanceof CitizenData.Notifications data) {
            notifications.getItems().setAll(data.entries().stream().map(entry -> DATE.format(entry.at()) + "\n" + entry.text()
                    + (entry.requestId() == null ? "" : "\nRequest " + entry.requestId())).toList());
            notifications.setPlaceholder(new Label("No notifications returned by the server."));
            notificationCount.setText(Integer.toString(data.entries().size()));
            notificationCountCaption.setText("Updates received from server");
        } else if (update instanceof CitizenData.TransferProgress progress) {
            transferProgress.setProgress(progress.fraction());
            documentProgress.setProgress(progress.fraction());
            transferLabel.setText(progress.receivedBytes() + " / " + progress.totalBytes() + " bytes - " + progress.stage());
            documentProgressLabel.setText(transferLabel.getText());
        }
    }
    private void showDetails() {
        timeline.getItems().clear(); messages.getItems().clear();
        CitizenData.RequestRow selected = requestsTable.getSelectionModel().getSelectedItem();
        CitizenData.Details data = selected == null ? null : details.get(selected.id());
        detailsLabel.setText(selected == null ? "Select a request to see its details." : data == null
                ? "Details for " + selected.id() + " have not been loaded from the server." : data.requestId() + "\n" + data.description());
        if (data != null) {
            timeline.getItems().setAll(data.timeline().stream().map(entry -> DATE.format(entry.at()) + "  |  " + entry.status() + "\n" + entry.description()).toList());
            messages.getItems().setAll(data.messages().stream().map(entry -> DATE.format(entry.at()) + "  |  " + entry.sender() + "\n" + entry.text()).toList());
        }
        refreshActions();
    }
    @FXML private void signOut() {
        active = false; selectionVersion++;
        workspace.setDisable(true);
        gateway.setListener(new CitizenGateway.Listener() { });
        gateway.disconnect().whenComplete((ignored, error) -> Platform.runLater(returnToLogin));
    }

    private void setBusy(boolean value, String text) {
        busy = value;
        busyIndicator.setVisible(value);
        activityLabel.setVisible(value);
        activityLabel.setText(text);
        refreshActions();
    }

    static String operationError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof TimeoutException) return "The server did not reply in time. Try again when connected.";
        if (error instanceof SecurityException) return "Citizen authorization expired. Return to sign in.";
        if (error instanceof UnsupportedOperationException) return "This service is not available on the server yet. Nothing was submitted.";
        if (error instanceof IllegalArgumentException) return error.getMessage();
        return "The operation could not be completed. Check the connection and server availability.";
    }
}
