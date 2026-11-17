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
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static lk.gramalink.client.FxTestSupport.fx;

@EnabledIfSystemProperty(named = "gramalink.uiTests", matches = "true")
@Timeout(20)
class AdminDashboardUiTest {
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    private record Ui(FXMLLoader loader, Parent root, OfflineAdminWorkspace fixture) implements AutoCloseable {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
        AdminDashboardController controller() { return loader.getController(); }
        public void close() { fixture.close(); }
    }
    private static Ui load(boolean attach) throws Exception {
        return fx(() -> {
            FXMLLoader loader = new FXMLLoader(AdminDashboardView.class.getResource("admin-dashboard.fxml")); Parent root = loader.load();
            Scene scene = new Scene(root, 1340, 920);
            scene.getStylesheets().add(AdminDashboardView.class.getResource("citizen-dashboard.css").toExternalForm());
            scene.getStylesheets().add(AdminDashboardView.class.getResource("admin-dashboard.css").toExternalForm());
            OfflineAdminWorkspace fixture = new OfflineAdminWorkspace(); if (attach) ((AdminDashboardController)loader.getController()).attach(fixture, () -> { });
            root.applyCss(); root.layout(); return new Ui(loader, root, fixture);
        });
    }
    private static void samples(Ui ui) throws Exception { ui.fixture.sampleData(); fx(() -> null); }
    private static void enable(Ui ui) throws Exception { ui.fixture.exerciseActions = true; fx(() -> { ui.controller().attach(ui.fixture, () -> { }); return null; }); }
    private static void fillUser(Ui ui) {
        ui.<TextField>node("username").setText("local.unsent.user"); ui.<TextField>node("userDisplayName").setText("Local unsent user");
        ui.<ComboBox<AdminData.Choice>>node("userRole").getSelectionModel().selectFirst();
    }
    private static void fillOffice(Ui ui) {
        ui.<TextField>node("officeName").setText("Local unsent office"); ui.<TextField>node("officeArea").setText("Local sample area");
        ui.<ComboBox<AdminData.Choice>>node("officeKind").getSelectionModel().selectFirst();
    }
    @Test void productionChecksUseWireValidationAndSelectionsClearStaleDrafts() throws Exception {
        try(Ui ui=load(false)) {
            ui.fixture.backendContract=true;ui.fixture.exerciseActions=true;
            var citizen=new AdminData.Choice("CITIZEN","CITIZEN");var administrator=new AdminData.Choice("ADMIN","ADMIN");var ds=new AdminData.Choice("DS","DS");
            fx(()->{ui.controller().attach(ui.fixture,()->{});return null;});
            ui.fixture.emit(new AdminData.Catalogues(List.of(citizen,administrator),List.of(ds,new AdminData.Choice("GN","GN"))));
            ui.fixture.emit(new AdminData.Users(List.of(
                    new AdminData.UserRow(UUID.randomUUID().toString(),"synthetic.citizen",null,citizen,null,"Active",false),
                    new AdminData.UserRow(UUID.randomUUID().toString(),"synthetic.admin",null,administrator,null,"Active",false))));
            ui.fixture.emit(new AdminData.Offices(List.of(new AdminData.OfficeRow(UUID.randomUUID().toString(),"Synthetic DS",ds,"TEST",null,false))));
            fx(()->{
                ui.<TextField>node("username").setText("synthetic.new");ui.<ComboBox<AdminData.Choice>>node("userRole").setValue(citizen);
                assertEquals(AdminData.Operation.CREATE_DEMO_USER,ui.<ComboBox<AdminData.Operation>>node("userIntent").getValue());
                assertEquals(AdminData.Operation.CREATE_DEMO_OFFICE,ui.<ComboBox<AdminData.Operation>>node("officeIntent").getValue());
                ui.<Button>node("checkUserButton").fire();assertTrue(ui.<Label>node("feedback").getText().contains("password"));
                ui.<PasswordField>node("userPassword").setText("SyntheticPass123!");
                ui.<Button>node("checkUserButton").fire();assertTrue(ui.<Label>node("feedback").getText().contains("division"));
                ui.<TextField>node("userDivision").setText("TEST");ui.<Button>node("checkUserButton").fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("fields are valid"));assertEquals(0,ui.fixture.operations);
                var users=ui.<TableView<AdminData.UserRow>>node("usersTable");users.getSelectionModel().selectFirst();
                assertEquals(AdminData.Operation.DISABLE_DEMO_USER,ui.<ComboBox<AdminData.Operation>>node("userIntent").getValue());
                assertTrue(ui.<PasswordField>node("userPassword").getText().isEmpty());assertTrue(ui.<TextField>node("userDivision").getText().isEmpty());
                assertTrue(ui.<Button>node("activateUserButton").isDisabled());assertFalse(ui.<Button>node("disableUserButton").isDisabled());
                users.getSelectionModel().select(1);assertTrue(ui.<Button>node("activateUserButton").isDisabled());assertTrue(ui.<Button>node("disableUserButton").isDisabled());
                ui.<TextField>node("officeParentId").setText(UUID.randomUUID().toString());ui.<TableView<AdminData.OfficeRow>>node("officesTable").getSelectionModel().selectFirst();
                assertTrue(ui.<TextField>node("officeParentId").getText().isEmpty());
                assertTrue(ui.<Button>node("updateUserButton").isDisabled());assertTrue(ui.<Button>node("archiveOfficeButton").isDisabled());
                return null;
            });
        }
    }

    @Test void fxmlStartsLockedAndAllSixNavigationActionsWork() throws Exception {
        try (Ui ui = load(false)) {
            fx(() -> {
                assertTrue(ui.root.lookup("#workspace").isDisabled()); ui.controller().attach(ui.fixture, () -> { });
                String[] ids = {"navOverview", "navClients", "navUsers", "navOffices", "navStatistics", "navAudit"}; TabPane sections = ui.node("sections");
                assertEquals(6, sections.getTabs().size());
                for (int i = 0; i < ids.length; i++) { ui.<Button>node(ids[i]).fire(); assertEquals(i, sections.getSelectionModel().getSelectedIndex()); assertTrue(ui.<Button>node(ids[i]).getStyleClass().contains("nav-current")); }
                assertTrue(ui.<Label>node("administratorName").getText().contains("Fictional")); return null;
            });
        }
    }
    @Test void partialTablesNeverInventGlobalCountsAndAuditIsChronological() throws Exception {
        try (Ui ui = load(true)) {
            ui.fixture.emit(OfflineAdminWorkspace.users());
            fx(() -> { assertEquals(3, ui.<TableView<?>>node("usersTable").getItems().size()); assertEquals("—", ui.<Label>node("usersCount").getText()); return null; });
            samples(ui);
            fx(() -> {
                assertEquals("12", ui.<Label>node("clientsCount").getText()); assertEquals(2, ui.<TableView<?>>node("clientsTable").getItems().size());
                assertEquals("8", ui.<Label>node("usersCount").getText()); assertEquals("4", ui.<Label>node("officesCount").getText());
                assertEquals("40", ui.<Label>node("requestsCount").getText()); assertEquals("Backend total: 40", ui.<Label>node("statisticsTotal").getText());
                assertEquals(3, ui.<TableView<?>>node("statisticsTable").getItems().size());
                assertEquals(30, ui.<TableView<AdminData.StatusCount>>node("statisticsTable").getItems().stream().mapToLong(AdminData.StatusCount::count).sum());
                assertEquals("Not supplied", ui.<TableColumn<AdminData.ClientRow, String>>node("sessionColumn").getCellData(1));
                assertEquals("Fictional state A", ui.<TableView<AdminData.StatusCount>>node("statisticsTable").getItems().getFirst().status());
                assertTrue(ui.<TableView<AdminData.AuditEntry>>node("auditTable").getItems().getFirst().activity().contains("office activity"));
                assertNull(ui.<TableView<AdminData.AuditEntry>>node("auditTable").getItems().getLast().at());
                for (String button : List.of("createUserButton", "updateUserButton", "disableUserButton", "createOfficeButton", "updateOfficeButton", "archiveOfficeButton")) assertTrue(ui.<Button>node(button).isDisabled());
                return null;
            });
            ui.fixture.emit(new AdminData.Users(List.of()));
            fx(() -> { assertTrue(ui.<TableView<?>>node("usersTable").getItems().isEmpty()); assertEquals("8", ui.<Label>node("usersCount").getText()); return null; });
        }
    }
    @Test void localUserAndOfficeDraftValidationDoesNotSubmitAnything() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                Button userCheck = ui.node("checkUserButton"); userCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("username"));
                ui.<TextField>node("username").setText("local.user"); userCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("display name"));
                ui.<TextField>node("userDisplayName").setText("Local user"); userCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("role"));
                ui.<ComboBox<AdminData.Choice>>node("userRole").getSelectionModel().selectFirst(); userCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been submitted"));
                Button officeCheck = ui.node("checkOfficeButton"); officeCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("office name"));
                ui.<TextField>node("officeName").setText("Local office"); officeCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("office type"));
                ui.<ComboBox<AdminData.Choice>>node("officeKind").getSelectionModel().selectFirst(); officeCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("area"));
                ui.<TextField>node("officeArea").setText("Local area"); officeCheck.fire(); assertTrue(ui.<Label>node("feedback").getText().contains("Nothing has been submitted"));
                assertEquals(0, ui.fixture.operations); return null;
            });
        }
    }
    @Test void previewRecordsRemainScopedAndOnlyArchivingRequiresReason() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enable(ui);
            fx(() -> {
                TableView<AdminData.UserRow> users = ui.node("usersTable"); users.getSelectionModel().selectLast();
                assertTrue(ui.<Button>node("updateUserButton").isDisabled()); assertTrue(ui.<Button>node("disableUserButton").isDisabled());
                ui.<Button>node("checkUserButton").fire(); assertTrue(ui.<Label>node("feedback").getText().contains("Other accounts are read only"));
                users.getSelectionModel().selectFirst(); assertFalse(ui.<Button>node("updateUserButton").isDisabled());
                ui.<ComboBox<AdminData.Operation>>node("userIntent").setValue(AdminData.Operation.DISABLE_DEMO_USER); ui.<Button>node("checkUserButton").fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("fields are valid"));
                TableView<AdminData.OfficeRow> offices = ui.node("officesTable"); offices.getSelectionModel().selectLast();
                assertTrue(ui.<Button>node("updateOfficeButton").isDisabled()); ui.<Button>node("checkOfficeButton").fire(); assertTrue(ui.<Label>node("feedback").getText().contains("Other offices are read only"));
                offices.getSelectionModel().selectFirst(); ui.<ComboBox<AdminData.Operation>>node("officeIntent").setValue(AdminData.Operation.ARCHIVE_DEMO_OFFICE); ui.<Button>node("checkOfficeButton").fire();
                assertTrue(ui.<Label>node("feedback").getText().contains("Enter a reason")); assertEquals(0, ui.fixture.operations); return null;
            });
        }
    }
    @Test void ackDoesNotCreateUsersOfficesStatisticsOrAuditEntries() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enable(ui); ui.fixture.nextReply = new CompletableFuture<>();
            fx(() -> { fillUser(ui); ui.<Button>node("createUserButton").fire(); assertTrue(ui.<ProgressIndicator>node("busyIndicator").isVisible()); return null; });
            ui.fixture.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> {
                assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible()); assertEquals(3, ui.<TableView<?>>node("usersTable").getItems().size());
                assertEquals(3, ui.<TableView<?>>node("auditTable").getItems().size()); assertEquals("8", ui.<Label>node("usersCount").getText());
                assertTrue(ui.<Label>node("feedback").getText().contains("Awaiting authoritative"));
                ui.fixture.nextReply = new CompletableFuture<>(); fillOffice(ui); ui.<Button>node("createOfficeButton").fire(); return null;
            });
            ui.fixture.nextReply.complete(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
            fx(() -> {
                assertEquals(3, ui.<TableView<?>>node("officesTable").getItems().size()); assertEquals(3, ui.<TableView<?>>node("auditTable").getItems().size());
                assertEquals("4", ui.<Label>node("officesCount").getText()); assertEquals("40", ui.<Label>node("requestsCount").getText()); return null;
            });
            ui.fixture.emit(new AdminData.Users(List.of(new AdminData.UserRow("FRESH-FIXTURE-ID", "fresh.fixture", "Fresh fixture", OfflineAdminWorkspace.ROLE, null, "Adapter supplied state", true))));
            fx(() -> { assertEquals("FRESH-FIXTURE-ID", ui.<TableView<AdminData.UserRow>>node("usersTable").getItems().getFirst().id()); assertEquals("8", ui.<Label>node("usersCount").getText()); return null; });
        }
    }
    @Test void incomingSnapshotsPreserveSelectedRecordAndUnsubmittedDrafts() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui);
            fx(() -> {
                ui.<TableView<AdminData.UserRow>>node("usersTable").getSelectionModel().select(1); ui.<TextField>node("userDisplayName").setText("Unsent user edit");
                ui.<TableView<AdminData.OfficeRow>>node("officesTable").getSelectionModel().selectFirst(); ui.<TextField>node("officeName").setText("Unsent office edit"); return null;
            });
            ui.fixture.emit(OfflineAdminWorkspace.users()); ui.fixture.emit(OfflineAdminWorkspace.offices());
            fx(() -> {
                assertEquals("PREVIEW-USER-002", ui.<TableView<AdminData.UserRow>>node("usersTable").getSelectionModel().getSelectedItem().id());
                assertEquals("Unsent user edit", ui.<TextField>node("userDisplayName").getText()); assertEquals("Unsent office edit", ui.<TextField>node("officeName").getText()); return null;
            });
        }
    }
    @Test void serverErrorsAreVisibleWithoutAdministrativeSuccess() throws Exception {
        for (String code : List.of("FORBIDDEN", "CONFLICT")) {
            try (Ui ui = load(true)) {
                samples(ui); enable(ui);
                ui.fixture.nextReply = CompletableFuture.completedFuture(new Message(MessageType.ERROR, JsonNodeFactory.instance.objectNode().put("code", code).put("message", "Fixture server rejection")));
                fx(() -> { fillUser(ui); ui.<Button>node("createUserButton").fire(); return null; });
                fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains(code)); assertTrue(ui.<Label>node("feedback").getStyleClass().contains("error-notice")); assertEquals(3, ui.<TableView<?>>node("usersTable").getItems().size()); return null; });
            }
        }
    }
    @Test void timeoutIsUnconfirmedAndConnectionLossLocksManagement() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); enable(ui); ui.fixture.nextReply = CompletableFuture.failedFuture(new TimeoutException());
            fx(() -> { fillOffice(ui); ui.<Button>node("createOfficeButton").fire(); return null; });
            fx(() -> { assertTrue(ui.<Label>node("feedback").getText().contains("Outcome is unconfirmed")); assertFalse(ui.<ProgressIndicator>node("busyIndicator").isVisible()); return null; });
            ui.fixture.disconnected();
            fx(() -> { assertTrue(ui.root.lookup("#workspace").isDisabled()); assertTrue(ui.<Label>node("feedback").getText().contains("actions are locked")); return null; });
        }
    }
    @Test void signOutRevokesFixtureAccessAndIgnoresLateData() throws Exception {
        try (Ui ui = load(true)) {
            samples(ui); AtomicBoolean returned = new AtomicBoolean();
            fx(() -> { ui.controller().attach(ui.fixture, () -> returned.set(true)); ui.<Button>node("signOutButton").fire(); return null; });
            ui.fixture.emit(new AdminData.Users(List.of()));
            fx(() -> { assertTrue(returned.get()); assertFalse(ui.fixture.available); assertTrue(ui.root.lookup("#workspace").isDisabled()); assertEquals(3, ui.<TableView<?>>node("usersTable").getItems().size()); return null; });
        }
    }
}
