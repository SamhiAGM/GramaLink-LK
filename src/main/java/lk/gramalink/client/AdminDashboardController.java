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

/** Administrator controls. Local drafts/replies never mutate authoritative tables, totals or audit records. */
public final class AdminDashboardController {
    @FXML private TabPane sections;
    @FXML private VBox workspace, userForm, officeForm;
    @FXML private Button navOverview, navClients, navUsers, navOffices, navStatistics, navAudit;
    @FXML private Label administratorName, connectionLabel, feedback, clientsCount, usersCount, officesCount, requestsCount, overviewAsOf;
    @FXML private Label statisticsTotal, statisticsScope, statisticsAsOf;
    @FXML private Circle connectionDot;
    @FXML private HBox activityRow;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label activityLabel;
    @FXML private TableView<AdminData.ClientRow> clientsTable;
    @FXML private TableColumn<AdminData.ClientRow, String> connectionColumn, clientUserColumn, clientRoleColumn, clientOfficeColumn, addressColumn, sessionColumn, connectedColumn, lastActivityColumn;
    @FXML private TableView<AdminData.UserRow> usersTable;
    @FXML private TableColumn<AdminData.UserRow, String> userIdColumn, usernameColumn, displayNameColumn, roleColumn, userOfficeColumn, userStateColumn, demoUserColumn;
    @FXML private TableView<AdminData.OfficeRow> officesTable;
    @FXML private TableColumn<AdminData.OfficeRow, String> officeIdColumn, officeNameColumn, officeKindColumn, officeAreaColumn, officeStateColumn, demoOfficeColumn;
    @FXML private TableView<AdminData.StatusCount> statisticsTable;
    @FXML private TableColumn<AdminData.StatusCount, String> requestStatusColumn, requestCountColumn;
    @FXML private TableView<AdminData.AuditEntry> auditTable;
    @FXML private TableColumn<AdminData.AuditEntry, String> auditDateColumn, auditUserColumn, auditActivityColumn, auditTargetColumn, auditOutcomeColumn;
    @FXML private TextField username, userDisplayName, officeName, officeArea;
    @FXML private ComboBox<AdminData.Choice> userRole, officeKind;
    @FXML private ComboBox<AdminData.OfficeRow> userOffice;
    @FXML private ComboBox<AdminData.Operation> userIntent, officeIntent;
    @FXML private TextArea userReason, officeReason;
    @FXML private Button createUserButton, updateUserButton, disableUserButton, checkUserButton;
    @FXML private Button createOfficeButton, updateOfficeButton, archiveOfficeButton, checkOfficeButton;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.of("Asia/Colombo"));
    private long bindingEpoch;
    private AdminWorkspace gateway;
    private Runnable returnToLogin;
    private boolean active, busy, synchronizing;

    @FXML private void initialize() {
        column(connectionColumn, AdminData.ClientRow::connectionId); column(clientUserColumn, AdminData.ClientRow::user);
        column(clientRoleColumn, AdminData.ClientRow::role); column(clientOfficeColumn, AdminData.ClientRow::office);
        column(addressColumn, AdminData.ClientRow::remoteAddress); column(sessionColumn, AdminData.ClientRow::sessionReference);
        column(connectedColumn, row -> date(row.connectedAt())); column(lastActivityColumn, row -> date(row.lastActivityAt()));
        column(userIdColumn, AdminData.UserRow::id); column(usernameColumn, AdminData.UserRow::username); column(displayNameColumn, AdminData.UserRow::displayName);
        column(roleColumn, row -> row.role() == null ? null : row.role().label()); column(userOfficeColumn, AdminData.UserRow::officeId);
        column(userStateColumn, AdminData.UserRow::state); column(demoUserColumn, row -> row.demoAccount() ? "Server-identified demo" : "Read only");
        column(officeIdColumn, AdminData.OfficeRow::id); column(officeNameColumn, AdminData.OfficeRow::name);
        column(officeKindColumn, row -> row.kind() == null ? null : row.kind().label()); column(officeAreaColumn, AdminData.OfficeRow::area);
        column(officeStateColumn, AdminData.OfficeRow::state); column(demoOfficeColumn, row -> row.demoOffice() ? "Server-identified demo" : "Read only");
        column(requestStatusColumn, AdminData.StatusCount::status); column(requestCountColumn, row -> Long.toString(row.count()));
        column(auditDateColumn, row -> date(row.at())); column(auditUserColumn, AdminData.AuditEntry::user);
        column(auditActivityColumn, AdminData.AuditEntry::activity); column(auditTargetColumn, AdminData.AuditEntry::target); column(auditOutcomeColumn, AdminData.AuditEntry::outcome);
        for (TableView<?> table : List.of(clientsTable, usersTable, officesTable, statisticsTable, auditTable))
            table.setPlaceholder(new Label("Data has not been loaded from the administrative server."));
        userIntent.getItems().setAll(AdminData.Operation.CREATE_DEMO_USER, AdminData.Operation.UPDATE_DEMO_USER, AdminData.Operation.DISABLE_DEMO_USER);
        officeIntent.getItems().setAll(AdminData.Operation.CREATE_DEMO_OFFICE, AdminData.Operation.UPDATE_DEMO_OFFICE, AdminData.Operation.ARCHIVE_DEMO_OFFICE);
        userIntent.setValue(AdminData.Operation.CREATE_DEMO_USER); officeIntent.setValue(AdminData.Operation.CREATE_DEMO_OFFICE);
        usersTable.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> { if (!synchronizing) fillUser(row); });
        officesTable.getSelectionModel().selectedItemProperty().addListener((observable, old, row) -> { if (!synchronizing) fillOffice(row); });
        sections.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> highlight(selected.intValue())); highlight(0);
        activityRow.visibleProperty().bind(busyIndicator.visibleProperty()); activityRow.managedProperty().bind(activityRow.visibleProperty());
        workspace.setDisable(true);
    }
    void attach(AdminWorkspace gateway, Runnable returnToLogin) {
        gateway.requireAdministrator();
        if (this.gateway != null) this.gateway.setListener(new AdminGateway.Listener() { });
        if (this.gateway != null && this.gateway != gateway) resetView();
        final long epoch = ++bindingEpoch; this.gateway = gateway; this.returnToLogin = returnToLogin; active = true;
        administratorName.setText(gateway.identity().orElseThrow().displayName()); setBusy(false); status(true); refreshActions();
        gateway.setListener(new AdminGateway.Listener() {
            public void onRecovery(ClientReliability.State state) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) recovery(state); }); }
            public void onUpdate(AdminData.Update update) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway) && gateway.isAdministratorAuthorized()) apply(update); }); }
            public void onStatus(NetworkClient.Status value) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) status(value == NetworkClient.Status.CONNECTED && gateway.isAdministratorAuthorized()); }); }
            public void onError(String error) { Platform.runLater(() -> { if (bindingCurrent(epoch, gateway)) feedback(error, true); }); }
        });
    }
    @FXML private void showOverview() { sections.getSelectionModel().select(0); }
    @FXML private void showClients() { sections.getSelectionModel().select(1); }
    @FXML private void showUsers() { sections.getSelectionModel().select(2); }
    @FXML private void showOffices() { sections.getSelectionModel().select(3); }
    @FXML private void showStatistics() { sections.getSelectionModel().select(4); }
    @FXML private void showAudit() { sections.getSelectionModel().select(5); }
    private void highlight(int selected) {
        List<Button> buttons = List.of(navOverview, navClients, navUsers, navOffices, navStatistics, navAudit);
        for (int i = 0; i < buttons.size(); i++) { buttons.get(i).getStyleClass().remove("nav-current"); if (selected == i) buttons.get(i).getStyleClass().add("nav-current"); }
    }
    private void resetView() {
        clientsTable.getItems().clear(); usersTable.getItems().clear(); officesTable.getItems().clear(); statisticsTable.getItems().clear(); auditTable.getItems().clear();
        userRole.getItems().clear(); userOffice.getItems().clear(); officeKind.getItems().clear(); fillUser(null); fillOffice(null);
        clientsCount.setText("\u2014"); usersCount.setText("\u2014"); officesCount.setText("\u2014"); requestsCount.setText("\u2014");
        overviewAsOf.setText("Server overview has not been loaded."); statisticsTotal.setText("Backend total: \u2014"); statisticsScope.setText("Scope: Not supplied"); statisticsAsOf.setText("Statistics have not been loaded.");
    }
    private boolean bindingCurrent(long epoch, AdminWorkspace source) {
        return active && bindingEpoch == epoch && gateway == source;
    }
    private void recovery(ClientReliability.State state) {
        if (state == ClientReliability.State.ONLINE && gateway.isAdministratorAuthorized()) { status(true); return; }
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
        connectionLabel.setText(online ? "Connected" : "Disconnected - Administrator sign-in required");
        connectionDot.getStyleClass().removeAll("online", "offline"); connectionDot.getStyleClass().add(online ? "online" : "offline");
        workspace.setDisable(!online);
        if (!online) { setBusy(false); feedback("Administrator authorization is unavailable. Management actions are locked.", true); }
    }
    private boolean can(AdminData.Operation operation, boolean ready) { return ready && gateway.supports(operation); }
    private void refreshActions() {
        boolean ready = gateway != null && gateway.isAdministratorAuthorized() && !busy;
        AdminData.UserRow user = usersTable.getSelectionModel().getSelectedItem(); AdminData.OfficeRow office = officesTable.getSelectionModel().getSelectedItem();
        createUserButton.setDisable(!can(AdminData.Operation.CREATE_DEMO_USER, ready && !userRole.getItems().isEmpty()));
        updateUserButton.setDisable(!can(AdminData.Operation.UPDATE_DEMO_USER, ready && user != null && user.demoAccount()));
        disableUserButton.setDisable(!can(AdminData.Operation.DISABLE_DEMO_USER, ready && user != null && user.demoAccount()));
        createOfficeButton.setDisable(!can(AdminData.Operation.CREATE_DEMO_OFFICE, ready && !officeKind.getItems().isEmpty()));
        updateOfficeButton.setDisable(!can(AdminData.Operation.UPDATE_DEMO_OFFICE, ready && office != null && office.demoOffice()));
        archiveOfficeButton.setDisable(!can(AdminData.Operation.ARCHIVE_DEMO_OFFICE, ready && office != null && office.demoOffice()));
        checkUserButton.setDisable(!ready); checkOfficeButton.setDisable(!ready);
        usersTable.setDisable(busy); officesTable.setDisable(busy); userForm.setDisable(busy); officeForm.setDisable(busy);
    }
    private AdminData.Command userCommand(AdminData.Operation operation) {
        AdminData.UserRow selected = usersTable.getSelectionModel().getSelectedItem();
        if (!operation.userOperation()) throw new IllegalArgumentException("Select a demo user action.");
        if (!operation.create() && (selected == null || !selected.demoAccount())) throw new IllegalArgumentException("Select a server-identified demo account. Other accounts are read only.");
        AdminData.UserDraft draft = operation == AdminData.Operation.DISABLE_DEMO_USER ? null : new AdminData.UserDraft(username.getText(), userDisplayName.getText(), userRole.getValue(), userOffice.getValue() == null ? null : userOffice.getValue().id());
        return new AdminData.Command(operation, operation.create() ? null : selected.id(), draft, null, userReason.getText());
    }
    private AdminData.Command officeCommand(AdminData.Operation operation) {
        AdminData.OfficeRow selected = officesTable.getSelectionModel().getSelectedItem();
        if (operation.userOperation()) throw new IllegalArgumentException("Select a demo office action.");
        if (!operation.create() && (selected == null || !selected.demoOffice())) throw new IllegalArgumentException("Select a server-identified demo office. Other offices are read only.");
        AdminData.OfficeDraft draft = operation == AdminData.Operation.ARCHIVE_DEMO_OFFICE ? null : new AdminData.OfficeDraft(officeName.getText(), officeKind.getValue(), officeArea.getText());
        return new AdminData.Command(operation, operation.create() ? null : selected.id(), null, draft, officeReason.getText());
    }
    @FXML private void checkUser() {
        try { gateway.requireAdministrator(); userCommand(userIntent.getValue()); feedback("Demo user fields are valid. Nothing has been submitted or changed.", false); }
        catch (IllegalArgumentException | SecurityException error) { feedback(error.getMessage(), true); }
    }
    @FXML private void checkOffice() {
        try { gateway.requireAdministrator(); officeCommand(officeIntent.getValue()); feedback("Demo office fields are valid. Nothing has been submitted or changed.", false); }
        catch (IllegalArgumentException | SecurityException error) { feedback(error.getMessage(), true); }
    }
    @FXML private void createUser() { performUser(AdminData.Operation.CREATE_DEMO_USER); }
    @FXML private void updateUser() { performUser(AdminData.Operation.UPDATE_DEMO_USER); }
    @FXML private void disableUser() { performUser(AdminData.Operation.DISABLE_DEMO_USER); }
    @FXML private void createOffice() { performOffice(AdminData.Operation.CREATE_DEMO_OFFICE); }
    @FXML private void updateOffice() { performOffice(AdminData.Operation.UPDATE_DEMO_OFFICE); }
    @FXML private void archiveOffice() { performOffice(AdminData.Operation.ARCHIVE_DEMO_OFFICE); }
    private void performUser(AdminData.Operation operation) {
        try { gateway.requireAdministrator(); perform(userCommand(operation)); }
        catch (IllegalArgumentException | SecurityException error) { feedback(error.getMessage(), true); }
    }
    private void performOffice(AdminData.Operation operation) {
        try { gateway.requireAdministrator(); perform(officeCommand(operation)); }
        catch (IllegalArgumentException | SecurityException error) { feedback(error.getMessage(), true); }
    }
    private void perform(AdminData.Command command) {
        final long epoch = bindingEpoch; final AdminWorkspace source = gateway;
        setBusy(true);
        gateway.perform(command).whenComplete((reply, error) -> Platform.runLater(() -> {
            if (!bindingCurrent(epoch, source)) return;
            setBusy(false);
            if (!source.isAdministratorAuthorized()) { status(false); return; }
            if (error != null) feedback(errorMessage(error), true);
            else if (reply.type() == MessageType.ERROR) feedback(LoginService.describe(reply) + " No local administrative change was applied.", true);
            else feedback("Server reply: " + reply.type() + ". Awaiting authoritative administrative data; no local change or audit entry was generated.", false);
        }));
    }
    private void fillUser(AdminData.UserRow row) {
        username.setText(row == null ? "" : Objects.toString(row.username(), "")); userDisplayName.setText(row == null ? "" : Objects.toString(row.displayName(), ""));
        userRole.setValue(row == null || !userRole.getItems().contains(row.role()) ? null : row.role());
        userOffice.setValue(row == null ? null : userOffice.getItems().stream().filter(office -> office.id().equals(row.officeId())).findFirst().orElse(null));
        userReason.clear(); userIntent.setValue(row == null ? AdminData.Operation.CREATE_DEMO_USER : AdminData.Operation.UPDATE_DEMO_USER); refreshActions();
    }
    private void fillOffice(AdminData.OfficeRow row) {
        officeName.setText(row == null ? "" : Objects.toString(row.name(), "")); officeArea.setText(row == null ? "" : Objects.toString(row.area(), ""));
        officeKind.setValue(row == null || !officeKind.getItems().contains(row.kind()) ? null : row.kind());
        officeReason.clear(); officeIntent.setValue(row == null ? AdminData.Operation.CREATE_DEMO_OFFICE : AdminData.Operation.UPDATE_DEMO_OFFICE); refreshActions();
    }
    @FXML private void clearUserForm() { usersTable.getSelectionModel().clearSelection(); fillUser(null); }
    @FXML private void clearOfficeForm() { officesTable.getSelectionModel().clearSelection(); fillOffice(null); }
    @FXML private void clearUserOffice() { userOffice.setValue(null); }
    private void apply(AdminData.Update update) {
        if (update instanceof AdminData.Overview data) {
            clientsCount.setText(count(data.connectedClients())); usersCount.setText(count(data.demoUsers())); officesCount.setText(count(data.offices())); requestsCount.setText(count(data.requests()));
            overviewAsOf.setText("Server overview as of: " + date(data.asOf()));
        } else if (update instanceof AdminData.Clients data) {
            clientsTable.getItems().setAll(data.rows()); loaded(clientsTable);
        } else if (update instanceof AdminData.Users data) {
            String id = Optional.ofNullable(usersTable.getSelectionModel().getSelectedItem()).map(AdminData.UserRow::id).orElse(null);
            synchronizing = true; usersTable.getItems().setAll(data.rows());
            AdminData.UserRow selected = data.rows().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
            usersTable.getSelectionModel().select(selected); synchronizing = false;
            if (id != null && selected == null) fillUser(null); loaded(usersTable);
        } else if (update instanceof AdminData.Offices data) {
            String id = Optional.ofNullable(officesTable.getSelectionModel().getSelectedItem()).map(AdminData.OfficeRow::id).orElse(null);
            String userOfficeId = Optional.ofNullable(userOffice.getValue()).map(AdminData.OfficeRow::id).orElse(null);
            synchronizing = true; officesTable.getItems().setAll(data.rows());
            AdminData.OfficeRow selected = data.rows().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
            officesTable.getSelectionModel().select(selected); synchronizing = false;
            userOffice.getItems().setAll(data.rows()); userOffice.setValue(data.rows().stream().filter(row -> row.id().equals(userOfficeId)).findFirst().orElse(null));
            if (id != null && selected == null) fillOffice(null); loaded(officesTable);
        } else if (update instanceof AdminData.Catalogues data) {
            AdminData.Choice role = userRole.getValue(), kind = officeKind.getValue();
            userRole.getItems().setAll(data.roles()); officeKind.getItems().setAll(data.officeKinds());
            userRole.setValue(role != null && data.roles().contains(role) ? role : null); officeKind.setValue(kind != null && data.officeKinds().contains(kind) ? kind : null);
        } else if (update instanceof AdminData.Statistics data) {
            statisticsTable.getItems().setAll(data.rows()); loaded(statisticsTable); statisticsTotal.setText("Backend total: " + count(data.total()));
            statisticsScope.setText("Scope: " + display(data.scope())); statisticsAsOf.setText("Statistics as of: " + date(data.asOf()));
        } else if (update instanceof AdminData.Audit data) { auditTable.getItems().setAll(data.rows()); loaded(auditTable); }
        refreshActions();
    }
    private void setBusy(boolean value) { busy = value; busyIndicator.setVisible(value); activityLabel.setText(value ? "Waiting for the administrative service…" : ""); refreshActions(); }
    private void feedback(String text, boolean error) {
        feedback.setText(text); feedback.getStyleClass().remove("error-notice"); if (error) feedback.getStyleClass().add("error-notice");
    }
    @FXML private void signOut() {
        bindingEpoch++; active = false; workspace.setDisable(true); gateway.setListener(new AdminGateway.Listener() { });
        gateway.disconnect().whenComplete((ignored, error) -> Platform.runLater(returnToLogin));
    }
    static String errorMessage(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof TimeoutException) return "The administrative server did not reply. Outcome is unconfirmed; check authoritative data before retrying.";
        if (error instanceof UnsupportedOperationException) return "Administrative submissions are unavailable until the backend contract is implemented. No local change was applied.";
        if (error instanceof SecurityException) return "Administrator authorization or demo-record permission is unavailable. Return to sign in or reload permitted data.";
        if (error instanceof IllegalArgumentException) return error.getMessage();
        return "The administrative operation could not be completed. Check the connection; its outcome is unconfirmed.";
    }
    private static void loaded(TableView<?> table) { table.setPlaceholder(new Label("No records returned by the server for this view.")); }
    private static String count(Long value) { return value == null ? "—" : Long.toString(value); }
    private static String date(Instant value) { return value == null ? "Not supplied" : DATE.format(value); }
    private static String display(String value) { return value == null || value.isBlank() ? "Not supplied" : value; }
    private static <T> void column(TableColumn<T, String> column, Function<T, String> value) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(display(value.apply(cell.getValue()))));
    }
}
