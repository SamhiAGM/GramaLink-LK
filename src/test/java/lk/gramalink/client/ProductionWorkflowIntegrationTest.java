package lk.gramalink.client;

import lk.gramalink.server.*;
import lk.gramalink.server.backend.BackendDispatcher;
import lk.gramalink.server.repository.Database;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real credentials, TCP and JDBC. Only a generated schema in the dedicated test database is mutated. */
@EnabledIfEnvironmentVariable(named="GRAMALINK_TEST_DB_URL", matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class ProductionWorkflowIntegrationTest {
    @TempDir static Path storage;
    private String url, user, password, schema;
    private Database database;
    private NetworkServer server;
    private Thread serverThread;
    private int port;
    @BeforeAll void setup() throws Exception {
        url=System.getenv("GRAMALINK_TEST_DB_URL");
        if (!url.matches("jdbc:postgresql://[^/]+/gramalink_test(?:_[a-z0-9]+)*(?:\\?.*)?"))
            throw new IllegalArgumentException("This fixture requires the separate gramalink_test database.");
        user=System.getenv("GRAMALINK_TEST_DB_USER"); password=System.getenv("GRAMALINK_TEST_DB_PASSWORD");
        schema="client_test_"+UUID.randomUUID().toString().replace("-", "");
        try (Connection c=DriverManager.getConnection(url,user,password); Statement s=c.createStatement()) {
            s.execute("CREATE SCHEMA "+schema);
        }
        database=new Database(url+(url.contains("?") ? "&" : "?")+"currentSchema="+schema,user,password);
        database.transaction(c -> {
            try (Statement s=c.createStatement()) {
                s.execute(Files.readString(Path.of("sql/schema.sql")).replace("BEGIN;", "").replace("COMMIT;", ""));
                s.execute(Files.readString(Path.of("sql/seed.sql")).replace("BEGIN;", "").replace("COMMIT;", ""));
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            return null;
        });
        var sessions=new SessionManager();
        var files=new lk.gramalink.server.service.FileTransferService(database,sessions,storage.resolve("private"),10485760,Set.of("application/pdf"));
        try (ServerSocket available=new ServerSocket(0)) { port=available.getLocalPort(); }
        server=new NetworkServer(port,sessions,Duration.ofSeconds(35),Duration.ofSeconds(5),files,new BackendDispatcher(database,sessions,files));
        serverThread=new Thread(() -> { try { server.start(); } catch (java.io.IOException e) { if (!Thread.currentThread().isInterrupted()) throw new RuntimeException(e); } });
        serverThread.setDaemon(true); serverThread.start();
    }
    @AfterAll void cleanup() throws Exception {
        if (server!=null) server.close();
        if (serverThread!=null) serverThread.join(5000);
        if (schema!=null) try (Connection c=DriverManager.getConnection(url,user,password); Statement s=c.createStatement()) {
            s.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    private LoginService login(String username) throws Exception {
        var service=new LoginService(ignored -> {}, Runnable::run);
        try {
            assertEquals(MessageType.LOGIN_OK, service.login(new LoginService.Input("127.0.0.1",port,username,"DemoPass123!")).get(15,TimeUnit.SECONDS).type());
            return service;
        } catch (Exception | AssertionError error) { service.close(); throw error; }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="gramalink.uiTests", matches="true")
    void realCitizenFormUsesRegisteredDivisionAndCreatesVisiblePersistedIncomeRequest() throws Exception {
        FxTestSupport.start();
        try (var service = login("citizen.demo")) {
            var gateway = service.citizenGateway().orElseThrow();
            var loader = new javafx.fxml.FXMLLoader(CitizenDashboardView.class.getResource("citizen-dashboard.fxml"));
            FxTestSupport.fx(() -> {
                javafx.scene.Parent root = loader.load();
                new javafx.scene.Scene(root);
                ((CitizenDashboardController)loader.getController()).attach(gateway, () -> { });
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!FxTestSupport.fx(() -> "DEMO-A".equals(((javafx.scene.control.TextField)loader.getNamespace().get("division")).getText()))) {
                assertTrue(System.nanoTime() < deadline, "Registered division did not reach the actual JavaFX form");
                Thread.sleep(50);
            }
            FxTestSupport.fx(() -> {
                assertFalse(((javafx.scene.control.TextField)loader.getNamespace().get("division")).isEditable());
                ((javafx.scene.control.TextField)loader.getNamespace().get("requestType")).setText("INCOME");
                ((javafx.scene.control.TextField)loader.getNamespace().get("requestTitle")).setText("Income form regression");
                ((javafx.scene.control.TextArea)loader.getNamespace().get("description")).setText("I need a verified income certificate to be submitted to university");
                ((javafx.scene.control.Button)loader.getNamespace().get("submitButton")).fire();
                return null;
            });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            String id;
            while (true) {
                id = FxTestSupport.fx(() -> {
                    var table = (javafx.scene.control.TableView<?>)loader.getNamespace().get("requestsTable");
                    return table.getItems().stream().map(CitizenData.RequestRow.class::cast)
                            .filter(r -> r.type().equals("INCOME")).map(CitizenData.RequestRow::id).findFirst().orElse(null);
                });
                if (id != null) break;
                assertTrue(System.nanoTime() < deadline, "Persisted income request did not appear in My Requests");
                Thread.sleep(50);
            }
            String createdId = id;
            assertTrue(FxTestSupport.fx(() -> ((javafx.scene.control.Label)loader.getNamespace().get("feedback")).getText().contains(createdId)));
            database.transaction(c -> {
                try (var p = c.prepareStatement("SELECT citizen_id,division,title FROM requests WHERE id=?")) {
                    p.setObject(1, UUID.fromString(createdId));
                    try (var r = p.executeQuery()) {
                        assertTrue(r.next());
                        assertEquals("20000000-0000-0000-0000-000000000001", r.getString(1));
                        assertEquals("DEMO-A", r.getString(2));
                        assertEquals("Income form regression", r.getString(3));
                    }
                }
                return null;
            });
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="gramalink.uiTests", matches="true")
    void realGnReceiveButtonSendsStatusUpdateAndRefreshesPersistedState() throws Exception {
        FxTestSupport.start();
        String id;
        try (var citizen = login("citizen.demo")) {
            var reply = citizen.citizenGateway().orElseThrow().createRequest(new CitizenData.Draft(
                    "OTHER", "DEMO-A", "GN button regression", "Synthetic isolated request", null)).get(15, TimeUnit.SECONDS);
            assertEquals(MessageType.ACK, reply.type());
            id = reply.payload().path("data").path("id").asText();
        }
        try (var gn = login("gn.demo")) {
            var loader = new javafx.fxml.FXMLLoader(GnDashboardView.class.getResource("gn-dashboard.fxml"));
            FxTestSupport.fx(() -> {
                javafx.scene.Parent root = loader.load(); new javafx.scene.Scene(root);
                ((GnDashboardController)loader.getController()).attach(gn.gnGateway().orElseThrow(), () -> { });
                return null;
            });
            awaitFx(() -> {
                var table = (javafx.scene.control.TableView<?>)loader.getNamespace().get("assignedRequests");
                return table.getItems().stream().map(GnData.RequestRow.class::cast).anyMatch(r -> r.id().equals(id));
            }, "Assigned request did not reach the GN form");
            FxTestSupport.fx(() -> {
                @SuppressWarnings("unchecked") var table = (javafx.scene.control.TableView<GnData.RequestRow>)loader.getNamespace().get("assignedRequests");
                @SuppressWarnings("unchecked") var kind = (javafx.scene.control.ComboBox<GnData.ActionKind>)loader.getNamespace().get("actionKind");
                kind.setValue(null);
                table.getSelectionModel().select(table.getItems().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
                assertEquals("SUBMITTED", table.getSelectionModel().getSelectedItem().status());
                kind.setValue(GnData.ActionKind.RECEIVE);
                return null;
            });
            awaitFx(() -> !((javafx.scene.control.Button)loader.getNamespace().get("performSelectedButton")).isDisabled(), "Receive did not become enabled");
            FxTestSupport.fx(() -> {
                ((javafx.scene.control.Button)loader.getNamespace().get("checkActionButton")).fire();
                ((javafx.scene.control.Button)loader.getNamespace().get("performSelectedButton")).fire();
                assertTrue(((javafx.scene.control.Button)loader.getNamespace().get("performSelectedButton")).isDisabled());
                return null;
            });
            awaitFx(() -> {
                var table = (javafx.scene.control.TableView<?>)loader.getNamespace().get("assignedRequests");
                return table.getItems().stream().map(GnData.RequestRow.class::cast).anyMatch(r -> r.id().equals(id) && r.status().equals("RECEIVED_BY_GN") && r.version() == 1L);
            }, "GN status did not refresh from PostgreSQL");
            assertTrue(FxTestSupport.fx(() -> ((javafx.scene.control.Button)loader.getNamespace().get("performSelectedButton")).isDisabled()), "Receive must be disabled after reception");
            database.transaction(c -> {
                try (var p = c.prepareStatement("SELECT status,version,(SELECT count(*) FROM request_history WHERE request_id=r.id AND action='RECEIVE') FROM requests r WHERE id=?")) {
                    p.setObject(1, UUID.fromString(id));
                    try (var r = p.executeQuery()) {
                        assertTrue(r.next()); assertEquals("RECEIVED_BY_GN", r.getString(1));
                        assertEquals(1L, r.getLong(2)); assertEquals(1, r.getInt(3));
                    }
                }
                return null;
            });
        }
    }
    private static void awaitFx(Callable<Boolean> condition, String failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.fx(condition)) {
            assertTrue(System.nanoTime() < deadline, failure); Thread.sleep(50);
        }
    }

    @Test void citizenSubmissionChatRefreshAndReconnectUseCommittedPostgresRecords() throws Exception {
        String id;
        try (var service=login("citizen.demo")) {
            var gateway=service.citizenGateway().orElseThrow();
            var snapshots=new LinkedBlockingQueue<CitizenData.Update>();
            gateway.setListener(new CitizenGateway.Listener() { public void onUpdate(CitizenData.Update u) { snapshots.add(u); } });
            Message reply=gateway.createRequest(new CitizenData.Draft("OTHER","DEMO-A","Integration title","Synthetic integration request",null)).get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,reply.type()); id=reply.payload().path("data").path("id").asText();
            UUID.fromString(id);
            gateway.refresh();
            CitizenData.RequestDetails details=awaitDetails(snapshots,id);
            assertTrue(details.details().description().contains("Integration title"));
            assertEquals("SUBMITTED",details.details().timeline().getFirst().status());
            assertEquals(MessageType.ACK,gateway.sendMessage(id,"Persisted question").get(15,TimeUnit.SECONDS).type());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            boolean received=false;
            while(System.nanoTime()<deadline) {
                var update=snapshots.poll(1,TimeUnit.SECONDS);
                if(update instanceof CitizenData.RequestDetails d && d.details().messages().stream().anyMatch(m -> m.text().equals("Persisted question"))) { received=true; break; }
            }
            assertTrue(received,"Committed chat must appear via authoritative GET_CHAT");
        }
        try (var service=login("citizen.demo")) {
            var gateway=service.citizenGateway().orElseThrow(); var updates=new LinkedBlockingQueue<CitizenData.Update>();
            gateway.setListener(new CitizenGateway.Listener(){ public void onUpdate(CitizenData.Update u){ updates.add(u); }});
            gateway.refresh(); assertTrue(awaitDetails(updates,id).details().messages().stream().anyMatch(m -> m.text().equals("Persisted question")));
        }
        database.transaction(c -> { try(PreparedStatement p=c.prepareStatement("SELECT title FROM requests WHERE id=?")) {
            p.setObject(1,UUID.fromString(id)); try(ResultSet r=p.executeQuery()){ assertTrue(r.next()); assertEquals("Integration title",r.getString(1)); }
        } return null; });
    }
    private static CitizenData.RequestDetails awaitDetails(BlockingQueue<CitizenData.Update> updates,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) { var u=updates.poll(1,TimeUnit.SECONDS); if(u instanceof CitizenData.RequestDetails d && d.details().requestId().equals(id)) return d; }
        throw new AssertionError("No authoritative request details received");
    }
    @Test void citizenGnDsWorkflowUsesProductionGatewaysAndAuthoritativePushRefresh() throws Exception {
        try(var citizen=login("citizen.demo"); var gn=login("gn.demo"); var ds=login("ds.demo"); var outsider=login("citizen.other")) {
            var cg=citizen.citizenGateway().orElseThrow(); var gg=gn.gnGateway().orElseThrow(); var dg=ds.dsGateway().orElseThrow();
            var gUpdates=new LinkedBlockingQueue<GnData.Update>(); var dUpdates=new LinkedBlockingQueue<DsData.Update>(); var cUpdates=new LinkedBlockingQueue<CitizenData.Update>();
            gg.setListener(new GnGateway.Listener(){ public void onUpdate(GnData.Update u){gUpdates.add(u);} });
            dg.setListener(new DsGateway.Listener(){ public void onUpdate(DsData.Update u){dUpdates.add(u);} });
            cg.setListener(new CitizenGateway.Listener(){ public void onUpdate(CitizenData.Update u){cUpdates.add(u);} });
            Message created=cg.createRequest(new CitizenData.Draft("RESIDENCE","DEMO-A","Full workflow","Synthetic workflow evidence",null)).get(15,TimeUnit.SECONDS);
            String id=created.payload().path("data").path("id").asText();
            gg.refresh();
            for(GnData.ActionKind action:List.of(GnData.ActionKind.RECEIVE,GnData.ActionKind.REVIEW,GnData.ActionKind.VERIFY_REQUEST,GnData.ActionKind.FORWARD_TO_DS)) {
                String expected=switch(action){case RECEIVE -> "SUBMITTED";case REVIEW -> "RECEIVED_BY_GN";case VERIFY_REQUEST -> "UNDER_GN_REVIEW";default -> "VERIFIED_BY_GN";};
                GnData.RequestRow row=awaitGn(gUpdates,id,expected);
                Message reply=gg.perform(new GnData.Action(action,id,null,"",row.version())).get(15,TimeUnit.SECONDS);
                assertEquals(MessageType.ACK,reply.type(),reply.payload().toString());
            }
            dg.refresh(); DsData.RequestRow forwarded=awaitDs(dUpdates,id,"FORWARDED_TO_DS");
            assertEquals("4",forwarded.version().value());
            Message approved=dg.perform(new DsData.Action(DsData.ActionKind.APPROVE,id,forwarded.version(),"")).get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,approved.type(),approved.payload().toString());
            assertEquals("5",awaitDs(dUpdates,id,"APPROVED").version().value());
            cg.refresh();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15); boolean visible=false;
            while(System.nanoTime()<deadline){ var u=cUpdates.poll(1,TimeUnit.SECONDS); if(u instanceof CitizenData.Requests r && r.rows().stream().anyMatch(row -> row.id().equals(id)&&row.status().equals("APPROVED"))){visible=true;break;} }
            assertTrue(visible,"Citizen must receive the committed final status");
            Message denied=outsider.citizenGateway().orElseThrow().sendMessage(id,"Must be denied").get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ERROR,denied.type()); assertEquals("FORBIDDEN",denied.payload().path("code").asText());
        }
    }
    private static GnData.RequestRow awaitGn(BlockingQueue<GnData.Update> updates,String id,String status) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var u=updates.poll(1,TimeUnit.SECONDS); if(u instanceof GnData.Requests r){var row=r.rows().stream().filter(x->x.id().equals(id)&&x.status().equals(status)).findFirst();if(row.isPresent())return row.get();}}
        throw new AssertionError("No GN snapshot with status "+status);
    }
    private static DsData.RequestRow awaitDs(BlockingQueue<DsData.Update> updates,String id,String status) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var u=updates.poll(1,TimeUnit.SECONDS); if(u instanceof DsData.Requests r){var row=r.rows().stream().filter(x->x.id().equals(id)&&x.status().equals(status)).findFirst();if(row.isPresent())return row.get();}}
        throw new AssertionError("No DS snapshot with status "+status);
    }
    private record AdminForm(javafx.fxml.FXMLLoader loader) {
        @SuppressWarnings("unchecked") <T> T node(String id) { return (T)loader.getNamespace().get(id); }
    }
    private static AdminData.UserRow adminUser(AdminForm ui,String username,String state) throws Exception {
        awaitFx(()->ui.<javafx.scene.control.TableView<AdminData.UserRow>>node("usersTable").getItems().stream()
                .anyMatch(u->u.username().equals(username) && u.state().equals(state)),"User management did not refresh: "+username+"/"+state);
        return FxTestSupport.fx(()->ui.<javafx.scene.control.TableView<AdminData.UserRow>>node("usersTable").getItems().stream().filter(u->u.username().equals(username)).findFirst().orElseThrow());
    }
    private static AdminData.OfficeRow adminOffice(AdminForm ui,String name) throws Exception {
        awaitFx(()->ui.<javafx.scene.control.TableView<AdminData.OfficeRow>>node("officesTable").getItems().stream().anyMatch(o->name.equals(o.name())),"Office management did not refresh: "+name);
        return FxTestSupport.fx(()->ui.<javafx.scene.control.TableView<AdminData.OfficeRow>>node("officesTable").getItems().stream().filter(o->name.equals(o.name())).findFirst().orElseThrow());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="gramalink.uiTests", matches="true")
    void administratorManagementButtonsPersistUsersOfficesAndAuditThroughRealTcp() throws Exception {
        FxTestSupport.start();
        String suffix=UUID.randomUUID().toString().substring(0,8),username="admin.ui."+suffix,password="SyntheticAdminTest123!";
        String division="ADMIN-UI-"+suffix,dsName="Synthetic DS "+suffix,gnName="Synthetic GN "+suffix;
        long auditStart=database.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT coalesce(max(id),0) FROM audit_logs")){r.next();return r.getLong(1);}});
        try(var admin=login("admin.demo")) {
            var ui=FxTestSupport.fx(()->{
                var loader=new javafx.fxml.FXMLLoader(AdminDashboardView.class.getResource("admin-dashboard.fxml"));
                javafx.scene.Parent root=loader.load();new javafx.scene.Scene(root);
                ((AdminDashboardController)loader.getController()).attach(admin.adminGateway().orElseThrow(),()->{});
                return new AdminForm(loader);
            });
            awaitFx(()->ui.<javafx.scene.control.TableView<?>>node("usersTable").getItems().size()>=7,"Administrator records did not load");
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TextField>node("username").setText(username);
                ui.<javafx.scene.control.PasswordField>node("userPassword").setText(password);
                ui.<javafx.scene.control.TextField>node("userDivision").setText(division);
                ui.<javafx.scene.control.ComboBox<AdminData.Choice>>node("userRole").setValue(new AdminData.Choice("CITIZEN","CITIZEN"));
                assertFalse(ui.<javafx.scene.control.Button>node("createUserButton").isDisabled());
                ui.<javafx.scene.control.Button>node("checkUserButton").fire();
                assertTrue(ui.<javafx.scene.control.Label>node("feedback").getText().contains("fields are valid"));
                ui.<javafx.scene.control.Button>node("createUserButton").fire();
                assertTrue(ui.<javafx.scene.control.PasswordField>node("userPassword").getText().isEmpty());return null;
            });
            var user=adminUser(ui,username,"Active");UUID.fromString(user.id());
            try(var newUser=new LoginService(ignored->{},Runnable::run)) {
                assertEquals(MessageType.LOGIN_OK,newUser.login(new LoginService.Input("127.0.0.1",port,username,password)).get(15,TimeUnit.SECONDS).type());
            }
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TableView<AdminData.UserRow>>node("usersTable").getSelectionModel().select(user);
                assertFalse(ui.<javafx.scene.control.Button>node("disableUserButton").isDisabled());
                assertTrue(ui.<javafx.scene.control.TextArea>node("userReason").getText().isEmpty());
                ui.<javafx.scene.control.Button>node("disableUserButton").fire();return null;
            });
            var inactive=adminUser(ui,username,"Inactive");
            try(var denied=new LoginService(ignored->{},Runnable::run)) {
                var reply=denied.login(new LoginService.Input("127.0.0.1",port,username,password)).get(15,TimeUnit.SECONDS);
                assertEquals(MessageType.ERROR,reply.type());assertEquals("UNAUTHENTICATED",reply.payload().path("code").asText());
            }
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TableView<AdminData.UserRow>>node("usersTable").getSelectionModel().select(inactive);
                assertFalse(ui.<javafx.scene.control.Button>node("activateUserButton").isDisabled());
                ui.<javafx.scene.control.Button>node("activateUserButton").fire();return null;
            });
            adminUser(ui,username,"Active");
            try(var reactivated=new LoginService(ignored->{},Runnable::run)) {
                assertEquals(MessageType.LOGIN_OK,reactivated.login(new LoginService.Input("127.0.0.1",port,username,password)).get(15,TimeUnit.SECONDS).type());
            }
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TextField>node("officeName").setText(dsName);
                ui.<javafx.scene.control.TextField>node("officeArea").setText(division);
                ui.<javafx.scene.control.ComboBox<AdminData.Choice>>node("officeKind").setValue(new AdminData.Choice("DS","DS"));
                ui.<javafx.scene.control.Button>node("createOfficeButton").fire();return null;
            });
            var ds=adminOffice(ui,dsName);
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TextField>node("officeName").setText(gnName);
                ui.<javafx.scene.control.ComboBox<AdminData.Choice>>node("officeKind").setValue(new AdminData.Choice("GN","GN"));
                ui.<javafx.scene.control.TextField>node("officeParentId").setText(ds.id());
                ui.<javafx.scene.control.Button>node("createOfficeButton").fire();return null;
            });
            var gn=adminOffice(ui,gnName);String renamed=gnName+" renamed";
            FxTestSupport.fx(()->{
                ui.<javafx.scene.control.TableView<AdminData.OfficeRow>>node("officesTable").getSelectionModel().select(gn);
                ui.<javafx.scene.control.TextField>node("officeName").setText(renamed);
                ui.<javafx.scene.control.TextField>node("officeArea").clear();
                ui.<javafx.scene.control.Button>node("updateOfficeButton").fire();return null;
            });
            assertEquals(gn.id(),adminOffice(ui,renamed).id());
            awaitFx(()->ui.<javafx.scene.control.TableView<AdminData.AuditEntry>>node("auditTable").getItems().stream().anyMatch(a->a.activity().equals("RENAME_OFFICE") && a.outcome().equals("SUCCESS")),"Committed management audit did not refresh");
            database.transaction(c->{
                try(var p=c.prepareStatement("SELECT role,division,active FROM users WHERE id=?")) {
                    p.setObject(1,UUID.fromString(user.id()));try(var r=p.executeQuery()){assertTrue(r.next());assertEquals("CITIZEN",r.getString(1));assertEquals(division,r.getString(2));assertTrue(r.getBoolean(3));}
                }
                try(var p=c.prepareStatement("SELECT name,kind,division,parent_id FROM offices WHERE id=?")) {
                    p.setObject(1,UUID.fromString(gn.id()));try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(renamed,r.getString(1));assertEquals("GN",r.getString(2));assertEquals(division,r.getString(3));assertEquals(ds.id(),r.getString(4));}
                }
                try(var p=c.prepareStatement("SELECT action,count(*) FROM audit_logs WHERE id>? AND outcome='SUCCESS' GROUP BY action")) {
                    p.setLong(1,auditStart);var counts=new HashMap<String,Long>();try(var r=p.executeQuery()){while(r.next())counts.put(r.getString(1),r.getLong(2));}
                    assertEquals(Map.of("CREATE_USER",1L,"SET_USER_ACTIVE",2L,"CREATE_OFFICE",2L,"RENAME_OFFICE",1L),counts);
                }
                return null;
            });
        }
    }

    @Test void administratorListsCreatesRenamesAndActivatesUsingCommittedBackendData() throws Exception {
        try(var service=login("admin.demo")) {
            var gateway=service.adminGateway().orElseThrow(); var updates=new LinkedBlockingQueue<AdminData.Update>();
            gateway.setListener(new AdminGateway.Listener(){public void onUpdate(AdminData.Update u){updates.add(u);}}); gateway.refresh();
            awaitAdmin(updates,u->u instanceof AdminData.Users users && users.rows().size()>=7);
            var dsKind=new AdminData.Choice("DS","DS"); var gnKind=new AdminData.Choice("GN","GN"); var citizenRole=new AdminData.Choice("CITIZEN","CITIZEN");
            Message ds=gateway.perform(new AdminData.Command(AdminData.Operation.CREATE_DEMO_OFFICE,null,null,new AdminData.OfficeDraft("Isolated DS",dsKind,"ISOLATED"),"")).get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,ds.type(),ds.payload().toString()); String dsId=ds.payload().path("data").asText();
            awaitAdmin(updates,u->u instanceof AdminData.Offices offices && offices.rows().stream().anyMatch(o->o.id().equals(dsId)));
            Message gn=gateway.perform(new AdminData.Command(AdminData.Operation.CREATE_DEMO_OFFICE,null,null,new AdminData.OfficeDraft("Isolated GN",gnKind,"ISOLATED",dsId),"")).get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,gn.type(),gn.payload().toString()); String gnId=gn.payload().path("data").asText();
            awaitAdmin(updates,u->u instanceof AdminData.Offices offices && offices.rows().stream().anyMatch(o->o.id().equals(gnId)));
            assertEquals(MessageType.ACK,gateway.perform(new AdminData.Command(AdminData.Operation.UPDATE_DEMO_OFFICE,gnId,null,new AdminData.OfficeDraft("Renamed GN",gnKind,"ISOLATED",dsId),"")).get(15,TimeUnit.SECONDS).type());
            Message created=gateway.perform(new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER,null,new AdminData.UserDraft("isolated.citizen","unused",citizenRole,null,"DemoPass123!","ISOLATED"),null,"")).get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,created.type(),created.payload().toString()); String userId=created.payload().path("data").asText();
            awaitAdmin(updates,u->u instanceof AdminData.Users users && users.rows().stream().anyMatch(x->x.id().equals(userId)));
            assertEquals(MessageType.ACK,gateway.perform(new AdminData.Command(AdminData.Operation.DISABLE_DEMO_USER,userId,null,null,"Isolated test")).get(15,TimeUnit.SECONDS).type());
            awaitAdmin(updates,u->u instanceof AdminData.Users users && users.rows().stream().anyMatch(x->x.id().equals(userId)&&x.state().equals("Inactive")));
            try(var rejected=new LoginService(ignored->{},Runnable::run)) {
                Message reply=rejected.login(new LoginService.Input("127.0.0.1",port,"isolated.citizen","DemoPass123!")).get(15,TimeUnit.SECONDS);
                assertEquals(MessageType.ERROR,reply.type()); assertTrue(rejected.citizenGateway().isEmpty());
            }
            assertEquals(MessageType.ACK,gateway.perform(new AdminData.Command(AdminData.Operation.ENABLE_USER,userId,null,null,"")).get(15,TimeUnit.SECONDS).type());
            awaitAdmin(updates,u->u instanceof AdminData.Users users && users.rows().stream().anyMatch(x->x.id().equals(userId)&&x.state().equals("Active")));
            awaitAdmin(updates,u->u instanceof AdminData.Audit audit && audit.rows().stream().anyMatch(x->x.activity().equals("CREATE_USER")));
            try(var createdLogin=login("isolated.citizen")){
                assertTrue(createdLogin.citizenGateway().isPresent());
                var activeGateway=createdLogin.citizenGateway().orElseThrow();
                assertEquals(MessageType.ACK,gateway.perform(new AdminData.Command(AdminData.Operation.DISABLE_DEMO_USER,userId,null,null,"Live revocation test")).get(15,TimeUnit.SECONDS).type());
                activeGateway.refresh();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(createdLogin.citizenGateway().isPresent() && System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(createdLogin.citizenGateway().isEmpty(),"Backend UNAUTHENTICATED must revoke local dashboard access");
            }
        }
    }
    private static void awaitAdmin(BlockingQueue<AdminData.Update> updates,java.util.function.Predicate<AdminData.Update> condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline){var u=updates.poll(1,TimeUnit.SECONDS);if(u!=null && condition.test(u))return;}
        throw new AssertionError("No expected authoritative Admin update");
    }
    @Test void plainPdfUploadIsStoredAndAuthorizedDownloadMatchesChecksum() throws Exception {
        Path pdf=storage.resolve("synthetic-evidence.pdf");
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument()){
            var page=new org.apache.pdfbox.pdmodel.PDPage(); document.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)){
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),10);
                var text=new StringBuilder();for(int n=0;n<5000;n++)text.append(UUID.randomUUID()).append(' ');
                content.showText(text.toString());content.endText();
            }
            document.save(pdf.toFile());
        }
        assertTrue(Files.size(pdf)>FileChunk.MAX_CHUNK_BYTES,"Exercise multiple actual binary chunks");
        String requestId,documentId;
        try(var citizen=login("citizen.demo");var gn=login("gn.demo")) {
            var gateway=citizen.citizenGateway().orElseThrow();
            Message created=gateway.createRequest(new CitizenData.Draft("OTHER","DEMO-A","Document workflow","Fictional PDF evidence",null)).get(15,TimeUnit.SECONDS);
            requestId=created.payload().path("data").path("id").asText();
            documentId=gateway.upload(requestId,pdf).get(30,TimeUnit.SECONDS);UUID.fromString(documentId);
            assertEquals(documentId,gateway.upload(requestId,pdf).get(30,TimeUnit.SECONDS),"Repeated upload returns the authoritative existing document ID");
            Path target=storage.resolve("officer-downloaded.pdf");
            assertEquals(target.toAbsolutePath(),gn.gnGateway().orElseThrow().download(requestId,documentId,target).get(30,TimeUnit.SECONDS));
            assertEquals(ClientFiles.hash(pdf),ClientFiles.hash(target));assertArrayEquals(Files.readAllBytes(pdf),Files.readAllBytes(target));
            database.transaction(c->{try(var p=c.prepareStatement("SELECT size_bytes,sha256 FROM documents WHERE id=?")){
                p.setObject(1,UUID.fromString(documentId));try(var row=p.executeQuery()){assertTrue(row.next());assertEquals(Files.size(pdf),row.getLong(1));assertEquals(ClientFiles.hash(pdf),row.getString(2));}
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;});
        }
        try(var relogin=login("citizen.demo")) {
            assertEquals(documentId,relogin.citizenGateway().orElseThrow().upload(requestId,pdf).get(30,TimeUnit.SECONDS));
        }
        database.transaction(c->{
            try(var p=c.prepareStatement("SELECT count(*) FROM documents WHERE request_id=?")) {
                p.setObject(1,UUID.fromString(requestId));try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(1,r.getInt(1),"No duplicate after retry or fresh login");}
            }
            return null;
        });
    }
    @Test void malformedPdfIsRejectedWithoutRevokingSessionAndNoDocumentIsPersisted() throws Exception {
        Path invalid=storage.resolve("invalid-evidence.pdf");Files.writeString(invalid,"Not a PDF, despite the filename and MIME type");
        try(var service=login("citizen.demo")) {
            var gateway=service.citizenGateway().orElseThrow();
            var reply=gateway.createRequest(new CitizenData.Draft("OTHER","DEMO-A","Rejected PDF regression","Synthetic isolated request",null)).get(15,TimeUnit.SECONDS);
            String requestId=reply.payload().path("data").path("id").asText();
            var rejected=assertThrows(ExecutionException.class,()->gateway.upload(requestId,invalid).get(30,TimeUnit.SECONDS));
            assertTrue(rejected.getCause().getMessage().contains("FILE_TRANSFER_FAILED"));
            assertTrue(gateway.isCitizenAuthorized(),"Content validation rejection is not a lost session");
            assertEquals(MessageType.ACK,gateway.sendMessage(requestId,"Still authenticated after rejected PDF").get(15,TimeUnit.SECONDS).type());
            database.transaction(c->{
                try(var p=c.prepareStatement("SELECT count(*) FROM documents WHERE request_id=?")) {
                    p.setObject(1,UUID.fromString(requestId));try(var r=p.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
                }
                return null;
            });
        }
    }
    @Test void authenticatedDashboardsOpenWithProductionFxmlAndRoleGuards() throws Exception {
        if(!Boolean.getBoolean("gramalink.uiTests")) { org.junit.jupiter.api.Assumptions.abort("Desktop UI tests disabled"); }
        FxTestSupport.start();
        for(String role:List.of("citizen","gn","ds","admin")) {
            record LoginUi(javafx.stage.Stage stage,LoginController controller) { }
            LoginUi ui=FxTestSupport.fx(()->{
                var loader=new javafx.fxml.FXMLLoader(ClientMain.class.getResource("login.fxml"));
                javafx.scene.Parent root=loader.load();var stage=new javafx.stage.Stage();
                stage.setTitle("GramaLink LK | Sign in");stage.setScene(new javafx.scene.Scene(root,1000,720));stage.show();
                ((javafx.scene.control.TextField)loader.getNamespace().get("hostField")).setText("127.0.0.1");
                ((javafx.scene.control.TextField)loader.getNamespace().get("portField")).setText(Integer.toString(port));
                ((javafx.scene.control.TextField)loader.getNamespace().get("usernameField")).setText(role+".demo");
                ((javafx.scene.control.PasswordField)loader.getNamespace().get("passwordField")).setText("DemoPass123!");
                ((javafx.scene.control.Button)loader.getNamespace().get("loginButton")).fire();
                return new LoginUi(stage,loader.getController());
            });
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(FxTestSupport.fx(()->ui.stage().getTitle().contains("Sign in")) && System.nanoTime()<deadline)Thread.sleep(20);
                FxTestSupport.fx(()->{
                    assertTrue(ui.stage().isShowing());assertFalse(ui.stage().getTitle().contains("Sign in"));
                    assertTrue(ui.stage().getTitle().toLowerCase(Locale.ROOT).contains(role.equals("admin")?"admin":role));
                    assertFalse(ui.stage().getScene().lookup("#workspace").isDisabled());assertFalse(ui.stage().getTitle().contains("PREVIEW"));
                    return null;
                });
            } finally { FxTestSupport.fx(()->{ui.controller().close();ui.stage().close();return null;}); }
        }
    }
    @Test void correctionsUseFreshCitizenVersionAndPersistReplacementTitle() throws Exception {
        try(var citizen=login("citizen.demo");var gn=login("gn.demo")){
            var cg=citizen.citizenGateway().orElseThrow();var gg=gn.gnGateway().orElseThrow();
            var gu=new LinkedBlockingQueue<GnData.Update>();var cu=new LinkedBlockingQueue<CitizenData.Update>();
            gg.setListener(new GnGateway.Listener(){public void onUpdate(GnData.Update u){gu.add(u);}});
            cg.setListener(new CitizenGateway.Listener(){public void onUpdate(CitizenData.Update u){cu.add(u);}});
            Message created=cg.createRequest(new CitizenData.Draft("OTHER","DEMO-A","Original title","Original description",null)).get(15,TimeUnit.SECONDS);
            String id=created.payload().path("data").path("id").asText();gg.refresh();
            var initial=awaitGn(gu,id,"SUBMITTED");
            assertEquals(MessageType.ACK,gg.perform(new GnData.Action(GnData.ActionKind.RECEIVE,id,null,"",initial.version())).get(15,TimeUnit.SECONDS).type());
            var received=awaitGn(gu,id,"RECEIVED_BY_GN");
            assertEquals(MessageType.ACK,gg.perform(new GnData.Action(GnData.ActionKind.REQUEST_INFORMATION,id,null,"Please correct the title",received.version())).get(15,TimeUnit.SECONDS).type());
            cg.refresh();CitizenData.RequestRow correction=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline && correction==null){var u=cu.poll(1,TimeUnit.SECONDS);if(u instanceof CitizenData.Requests r)correction=r.rows().stream().filter(x->x.id().equals(id)&&x.status().equals("NEEDS_CORRECTION")).findFirst().orElse(null);}
            assertNotNull(correction);
            Message changed=cg.resubmit(id,correction.version(),"Corrected title","Corrected description").get(15,TimeUnit.SECONDS);
            assertEquals(MessageType.ACK,changed.type(),changed.payload().toString());
            assertEquals("Corrected title",changed.payload().path("data").path("title").asText());
            assertEquals("SUBMITTED",changed.payload().path("data").path("status").asText());
        }
    }
}
