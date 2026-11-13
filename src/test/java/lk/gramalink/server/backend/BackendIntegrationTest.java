package lk.gramalink.server.backend;

import lk.gramalink.server.SessionManager;
import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.repository.*;
import lk.gramalink.server.service.*;
import lk.gramalink.shared.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL, isolated generated schema; never truncates tables in the caller's default schema. */
@EnabledIfEnvironmentVariable(named="GRAMALINK_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BackendIntegrationTest {
    protected Database db;
    protected RequestService requests;
    protected NotificationService notifications;
    protected AuthService auth;
    protected AdminService admin;
    protected SessionManager sessions;
    protected FileTransferService files;
    @TempDir protected Path storage;
    protected static final Actor CITIZEN=actor(1),GN=actor(2),DS=actor(3),ADMIN=actor(4),OTHER=actor(5),OTHER_GN=actor(6),OTHER_DS=actor(7);
    private String baseUrl,user,password,schema;
    private final List<Pair> pairs=new ArrayList<>();
    private final List<Thread> handlers=new ArrayList<>();
    protected static Actor actor(int n) { return new Actor(UUID.fromString("20000000-0000-0000-0000-"+String.format("%012d",n))); }
    @BeforeAll void initializeDatabase() throws Exception {
        baseUrl=System.getenv("GRAMALINK_TEST_DB_URL"); user=System.getenv().getOrDefault("GRAMALINK_TEST_DB_USER","gramalink_test");
        password=System.getenv().getOrDefault("GRAMALINK_TEST_DB_PASSWORD","");
        schema="test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(baseUrl,user,password); Statement s=c.createStatement()) { s.execute("CREATE SCHEMA "+schema); }
        String url=baseUrl+(baseUrl.contains("?") ? "&" : "?")+"currentSchema="+schema;
        db=new Database(url,user,password);
        db.transaction(c -> { Sql.update(c,script("schema.sql")); return null; });
    }
    private static String script(String name) {
        try { return Files.readString(Path.of("sql",name)).replace("BEGIN;","").replace("COMMIT;",""); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    @BeforeEach void reset() throws Exception {
        db.transaction(c -> { Sql.update(c,"TRUNCATE offices,users,requests,login_logs,audit_logs RESTART IDENTITY CASCADE"); Sql.update(c,script("seed.sql")); return null; });
        sessions=new SessionManager(); requests=new RequestService(db); notifications=new NotificationService(db); auth=new AuthService(db);
        admin=new AdminService(db,sessions::getAuthenticatedSessionCount);
        files=new FileTransferService(db,sessions,storage.resolve(UUID.randomUUID().toString()),1048576,Set.of("application/pdf"));
    }
    @AfterEach void closeSockets() throws Exception { for(Pair p:pairs) p.close(); pairs.clear(); for(Thread t:handlers) t.join(5000); handlers.clear(); }
    @AfterAll void removeSchema() throws Exception {
        if (schema!=null) try(Connection c=DriverManager.getConnection(baseUrl,user,password); Statement s=c.createStatement()) { s.execute("DROP SCHEMA "+schema+" CASCADE"); }
    }
    protected Models.Request create() { return requests.create(CITIZEN,new CreateRequest("RESIDENCE","Demo request","Synthetic description","DEMO-A")).value(); }
    protected Models.Request change(Actor actor,Models.Request r,Action action) {
        return requests.change(actor,new ChangeRequest(r.id(),r.version(),action,"Synthetic reason","Updated demo","Corrected description")).value();
    }
    protected void code(ServiceException.Code code,org.junit.jupiter.api.function.Executable action) { assertEquals(code,assertThrows(ServiceException.class,action).code()); }
    @Test void loginValidInvalidAndRoleClaimsAreNotTrusted() {
        assertEquals("GN",auth.login("gn.demo","DemoPass123!").role());
        code(ServiceException.Code.UNAUTHENTICATED,()->auth.login("gn.demo","wrong"));
        code(ServiceException.Code.UNAUTHENTICATED,()->auth.login("missing.demo","wrong"));
        long logCount=((Number)db.transaction(c -> Sql.rows(c,"SELECT count(*) AS n FROM login_logs").getFirst().get("n"))).longValue();
        assertEquals(3L,logCount);
        code(ServiceException.Code.FORBIDDEN,()->admin.users(CITIZEN,0));
        assertTrue(db.transaction(c -> Sql.rows(c,"SELECT * FROM audit_logs WHERE outcome='FORBIDDEN'").size())>0);
    }
    @Test void completeWorkflowHistoryAndNotificationsAreDurable() {
        var r=create(); r=change(GN,r,Action.RECEIVE); r=change(GN,r,Action.REVIEW); r=change(GN,r,Action.VERIFY);
        r=change(GN,r,Action.FORWARD); r=change(DS,r,Action.APPROVE);
        assertEquals(Status.APPROVED,r.status()); assertEquals(5,r.version());
        assertEquals(6,requests.history(CITIZEN,r.id()).size());
        assertEquals(6,new NotificationService(db).notifications(CITIZEN,0).size());
        var done=r; code(ServiceException.Code.INVALID_TRANSITION,()->change(GN,done,Action.REVIEW));
    }
    @Test void correctionResubmissionAndDsReturnRequireReverification() {
        var r=change(GN,create(),Action.RECEIVE); r=change(GN,r,Action.REQUEST_CORRECTION);
        r=change(CITIZEN,r,Action.RESUBMIT); assertEquals("Updated demo",r.title());
        r=change(GN,r,Action.RECEIVE); r=change(GN,r,Action.REVIEW); r=change(GN,r,Action.VERIFY); r=change(GN,r,Action.FORWARD);
        r=change(DS,r,Action.RETURN); var returned=r;
        code(ServiceException.Code.INVALID_TRANSITION,()->change(GN,returned,Action.FORWARD));
        r=change(GN,r,Action.REVIEW); r=change(GN,r,Action.VERIFY); r=change(GN,r,Action.FORWARD);
        assertEquals(Status.REJECTED,change(DS,r,Action.REJECT).status());
    }
    @Test void crossUserOfficeAndRoleAccessDenied() {
        var r=create();
        for(Actor outsider:List.of(OTHER,OTHER_GN,OTHER_DS)) {
            code(ServiceException.Code.FORBIDDEN,()->requests.detail(outsider,r.id()));
            code(ServiceException.Code.FORBIDDEN,()->notifications.chat(outsider,r.id(),0));
            code(ServiceException.Code.FORBIDDEN,()->change(outsider,r,Action.RECEIVE));
            assertTrue(requests.list(outsider,0).isEmpty());
        }
        code(ServiceException.Code.INVALID_TRANSITION,()->change(ADMIN,r,Action.RECEIVE));
        code(ServiceException.Code.FORBIDDEN,()->requests.create(GN,new CreateRequest("OTHER","title","description","DEMO-A")));
        code(ServiceException.Code.FORBIDDEN,()->requests.create(CITIZEN,new CreateRequest("OTHER","title","description","DEMO-B")));
        assertEquals(0,requests.detail(CITIZEN,r.id()).version());
    }
    @Test void concurrentChangesCommitOnceAndCreateOneHistoryEntry() throws Exception {
        var r=create(); var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> update=()->{ gate.await(); try {change(GN,r,Action.RECEIVE); return "OK";} catch(ServiceException e) {return e.code().name();} };
            var first=pool.submit(update); var second=pool.submit(update); gate.countDown();
            assertEquals(Set.of("OK","CONFLICT"),Set.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)));
        }
        assertEquals(1,requests.detail(CITIZEN,r.id()).version()); assertEquals(2,requests.history(CITIZEN,r.id()).size());
        assertEquals(2,notifications.notifications(CITIZEN,0).size());
    }
    @Test void databaseFailureRollsBackRequestHistoryAndNotifications() {
        var r=create();
        db.transaction(c -> { Sql.update(c,"""
            CREATE FUNCTION fail_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$;
            CREATE TRIGGER fail_history BEFORE INSERT ON request_history FOR EACH ROW EXECUTE FUNCTION fail_history();
            """); return null; });
        try { code(ServiceException.Code.INTERNAL_ERROR,()->change(GN,r,Action.RECEIVE)); }
        finally { db.transaction(c -> {Sql.update(c,"DROP TRIGGER fail_history ON request_history; DROP FUNCTION fail_history()"); return null;}); }
        assertEquals(Status.SUBMITTED,requests.detail(CITIZEN,r.id()).status()); assertEquals(0,requests.detail(CITIZEN,r.id()).version());
        assertEquals(1,requests.history(CITIZEN,r.id()).size()); assertEquals(1,notifications.notifications(CITIZEN,0).size());
    }
    @Test void offlineChatAndNotificationRetrievalAreScopedToUser() {
        var r=create(); notifications.sendChat(GN,new Chat(r.id(),"Synthetic officer message"));
        assertEquals(1,new NotificationService(db).chat(CITIZEN,r.id(),0).size());
        var list=notifications.notifications(CITIZEN,0); long id=((Number)list.getFirst().get("id")).longValue();
        code(ServiceException.Code.NOT_FOUND,()->notifications.markRead(OTHER,id));
        notifications.markRead(CITIZEN,id); assertNotNull(notifications.notifications(CITIZEN,0).getFirst().get("read_at"));
    }
    @Test void adminManagementAndStatisticsUseRealData() {
        UUID user=admin.createUser(ADMIN,new NewUser("new.demo","DemoPass123!",Role.CITIZEN,null,"DEMO-A"));
        admin.setActive(ADMIN,user,false); code(ServiceException.Code.UNAUTHENTICATED,()->auth.login("new.demo","DemoPass123!"));
        admin.setActive(ADMIN,user,true); assertEquals("CITIZEN",auth.login("new.demo","DemoPass123!").role());
        UUID dsOffice=admin.createOffice(ADMIN,new NewOffice("Another demo DS","DS","DEMO-C",null));
        UUID gnOffice=admin.createOffice(ADMIN,new NewOffice("Another demo GN","GN","DEMO-C",dsOffice));
        admin.renameOffice(ADMIN,gnOffice,"Renamed demo GN");
        code(ServiceException.Code.VALIDATION_ERROR,()->admin.createUser(ADMIN,new NewUser("bad.demo","DemoPass123!",Role.DS,gnOffice,"DEMO-C")));
        create(); code(ServiceException.Code.VALIDATION_ERROR,()->admin.setActive(ADMIN,GN.userId(),false));
        assertEquals(0,admin.statistics(ADMIN).get("onlineConnections")); assertFalse(admin.audit(ADMIN,0).isEmpty());
    }
    protected byte[] pdf() throws Exception {
        try(var doc=new PDDocument(); var bytes=new ByteArrayOutputStream()) {doc.addPage(new PDPage()); doc.save(bytes); return bytes.toByteArray();}
    }
    protected Message metadata(UUID request,String name,byte[] data) throws Exception {
        return new Message(MessageType.FILE_META,JsonNodeFactory.instance.objectNode().put("direction","UPLOAD").put("requestId",request.toString())
                .put("fileName",name).put("mediaType","application/pdf").put("totalBytes",data.length)
                .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))));
    }
    protected SessionManager.Session session(String username) throws Exception {
        Pair pair=new Pair(); pairs.add(pair); return sessions.registerVerifiedSession(auth.login(username,"DemoPass123!"),pair.server);
    }
    @Test void validPdfUploadDownloadAndIntegrityVerification() throws Exception {
        var r=create(); byte[] data=pdf(); var session=session("citizen.demo");
        var grant=files.authorize(session,metadata(r.id(),"demo.pdf",data));
        files.receiveChunk(session,grant,new FileChunk(grant.transferId(),0,true,data));
        assertEquals("STORED",files.completion(session,grant.transferId()).orElseThrow().payload().path("status").asText());
        assertEquals(1,files.list(CITIZEN,r.id()).size());
        var request=new Message(MessageType.FILE_META,JsonNodeFactory.instance.objectNode().put("direction","DOWNLOAD").put("documentId",grant.transferId().toString()));
        var download=files.authorize(session,request);
        assertNotEquals(grant.transferId(),download.transferId());
        try(var in=files.openDownload(session,download)) {assertArrayEquals(data,in.readAllBytes());}
        var outsider=session("citizen.other"); code(ServiceException.Code.FORBIDDEN,()->files.authorize(outsider,request));
        Path destination=Files.walk(storage).filter(p->p.getFileName().toString().equals(grant.transferId()+".pdf")).findFirst().orElseThrow();
        Files.write(destination,new byte[data.length]); var corrupt=files.authorize(session,request);
        assertThrows(IOException.class,()->files.openDownload(session,corrupt)); files.abort(session,corrupt);
    }
    @Test void rejectsForbiddenOversizeTraversalMalformedPdfAndChecksumMismatch() throws Exception {
        var r=create(); var session=session("citizen.demo"); byte[] pdf=pdf();
        for(String name:List.of("../demo.pdf","C:\\demo.pdf","demo.exe","bad..pdf"))
            code(ServiceException.Code.VALIDATION_ERROR,()->files.authorize(session,metadata(r.id(),name,pdf)));
        var oversized=metadata(r.id(),"demo.pdf",pdf); oversized.payload().put("totalBytes",1048577);
        code(ServiceException.Code.VALIDATION_ERROR,()->files.authorize(session,oversized));
        var wrongType=metadata(r.id(),"demo.pdf",pdf); wrongType.payload().put("mediaType","application/x-msdownload");
        code(ServiceException.Code.VALIDATION_ERROR,()->files.authorize(session,wrongType));
        byte[] fake="%PDF-this is not a real PDF".getBytes(); var bad=files.authorize(session,metadata(r.id(),"fake.pdf",fake));
        code(ServiceException.Code.VALIDATION_ERROR,()->files.receiveChunk(session,bad,new FileChunk(bad.transferId(),0,true,fake)));
        var wrongHash=metadata(r.id(),"demo.pdf",pdf); wrongHash.payload().put("sha256","0".repeat(64));
        var badHash=files.authorize(session,wrongHash);
        code(ServiceException.Code.VALIDATION_ERROR,()->files.receiveChunk(session,badHash,new FileChunk(badHash.transferId(),0,true,pdf)));
        assertTrue(files.list(CITIZEN,r.id()).isEmpty());
        try(var paths=Files.walk(storage)) { assertEquals(0,paths.filter(Files::isRegularFile).count()); }
    }
    @Test void interruptedUploadAndMetadataFailureCleanFiles() throws Exception {
        var r=create(); byte[] data=pdf(); var session=session("citizen.demo"); var grant=files.authorize(session,metadata(r.id(),"demo.pdf",data));
        files.receiveChunk(session,grant,new FileChunk(grant.transferId(),0,false,Arrays.copyOf(data,50)));
        files.abort(session,grant); files.abort(session,grant); assertTrue(files.list(CITIZEN,r.id()).isEmpty());
        db.transaction(c -> {Sql.update(c,"ALTER TABLE documents ADD CONSTRAINT synthetic_failure CHECK(size_bytes<0)");return null;});
        try {
            var failing=files.authorize(session,metadata(r.id(),"demo.pdf",data));
            assertThrows(ServiceException.class,()->files.receiveChunk(session,failing,new FileChunk(failing.transferId(),0,true,data)));
        } finally { db.transaction(c -> {Sql.update(c,"ALTER TABLE documents DROP CONSTRAINT synthetic_failure");return null;}); }
        try(var paths=Files.walk(storage)) { assertEquals(0,paths.filter(Files::isRegularFile).count()); }
        assertTrue(files.list(CITIZEN,r.id()).isEmpty());
    }
    @Test void realTcpLoginWorkflowPushFilesAndRecovery() throws Exception {
        Connected citizen=connect("citizen.demo"),gn=connect("gn.demo"),ds=connect("ds.demo"),administrator=connect("admin.demo");
        assertEquals(4,sessions.getAuthenticatedSessionCount());
        var created=citizen.call(MessageType.CREATE_REQUEST,JsonNodeFactory.instance.objectNode().put("type","RESIDENCE")
                .put("title","TCP demo").put("description","Synthetic TCP workflow").put("division","DEMO-A"));
        UUID request=UUID.fromString(created.payload().path("data").path("id").asText());
        assertEquals(MessageType.NEW_REQUEST,gn.readMessage().type());
        assertEquals(MessageType.NOTIFICATION,gn.readMessage().type());
        long version=0;
        for(Action action:List.of(Action.RECEIVE,Action.REVIEW,Action.VERIFY,Action.FORWARD)) {
            var reply=gn.call(MessageType.STATUS_UPDATE,JsonNodeFactory.instance.objectNode().put("requestId",request.toString())
                    .put("expectedVersion",version++).put("action",action.name()));
            assertEquals(MessageType.ACK,reply.type(),reply.toString());
        }
        assertEquals(MessageType.ACK,ds.call(MessageType.STATUS_UPDATE,JsonNodeFactory.instance.objectNode().put("requestId",request.toString())
                .put("expectedVersion",4).put("action","APPROVE")).type());
        assertEquals("APPROVED",citizen.call(MessageType.GET_REQUEST,JsonNodeFactory.instance.objectNode().put("requestId",request.toString())).payload().path("data").path("status").asText());
        assertEquals(4,administrator.call(MessageType.GET_STATISTICS,JsonNodeFactory.instance.objectNode()).payload().path("data").path("onlineConnections").asInt());
        assertEquals(MessageType.ACK,citizen.call(MessageType.HEARTBEAT,JsonNodeFactory.instance.objectNode()).type());
        citizen.pair.client.close();
        Connected reconnect=connect("citizen.demo");
        assertFalse(reconnect.call(MessageType.GET_NOTIFICATIONS,JsonNodeFactory.instance.objectNode()).payload().path("data").isEmpty());

        UUID uploadRequest=create().id(); byte[] data=pdf(); Message meta=metadata(uploadRequest,"demo.pdf",data);
        Message ready=reconnect.call(MessageType.FILE_META,meta.payload());
        UUID transfer=UUID.fromString(ready.payload().path("transferId").asText());
        TransportCodec.writeChunk(reconnect.output,new FileChunk(transfer,0,true,data));
        Message transport=reconnect.readMessage(); assertEquals("TRANSPORT_RECEIVED",transport.payload().path("stage").asText());
        Message stored=reconnect.readMessage(); assertEquals("STORED",stored.payload().path("status").asText());
        Message download=reconnect.call(MessageType.FILE_META,JsonNodeFactory.instance.objectNode().put("direction","DOWNLOAD").put("documentId",transfer.toString()));
        assertEquals("READY",download.payload().path("status").asText());
        TransportFrame frame=TransportCodec.readFrame(reconnect.input); assertInstanceOf(FileChunk.class,frame); assertArrayEquals(data,((FileChunk)frame).data());
        assertEquals(MessageType.ACK,reconnect.call(MessageType.HEARTBEAT,JsonNodeFactory.instance.objectNode()).type());
    }
    @Test void tcpRejectsForgedRoleAndUnauthenticatedCommands() throws Exception {
        Connected c=connect(null);
        assertEquals("UNAUTHENTICATED",c.call(MessageType.GET_STATISTICS,JsonNodeFactory.instance.objectNode()).payload().path("code").asText());
        var login=c.call(MessageType.LOGIN,JsonNodeFactory.instance.objectNode().put("username","citizen.demo").put("password","DemoPass123!").put("role","ADMIN"));
        assertEquals("CITIZEN",login.payload().path("data").path("role").asText());
        assertEquals("FORBIDDEN",c.call(MessageType.GET_STATISTICS,JsonNodeFactory.instance.objectNode().put("role","ADMIN")).payload().path("code").asText());
        assertEquals("VALIDATION_ERROR",c.call(MessageType.STATUS_UPDATE,JsonNodeFactory.instance.objectNode().put("requestId","bad")).payload().path("code").asText());
    }
    private Connected connect(String username) throws Exception {
        Pair pair=new Pair(); pairs.add(pair); pair.client.setSoTimeout(10000);
        var dispatcher=new BackendDispatcher(db,sessions,files);
        handlers.add(Thread.startVirtualThread(new lk.gramalink.server.ClientHandler(pair.server,sessions,files,dispatcher)));
        Connected c=new Connected(pair);
        if(username!=null) assertEquals(MessageType.LOGIN_OK,c.call(MessageType.LOGIN,JsonNodeFactory.instance.objectNode().put("username",username).put("password","DemoPass123!")).type());
        return c;
    }
    private static class Connected {
        final Pair pair; final DataInputStream input; final DataOutputStream output;
        Connected(Pair p) throws IOException {pair=p;input=new DataInputStream(p.client.getInputStream()); output=new DataOutputStream(p.client.getOutputStream());}
        Message call(MessageType type,com.fasterxml.jackson.databind.node.ObjectNode payload) throws IOException {
            String correlation=UUID.randomUUID().toString(); JsonUtil.writeMessage(output,new Message(type,correlation,null,payload));
            for(int i=0;i<100;i++) { Message reply=readMessage(); if(correlation.equals(reply.correlationId())) return reply; }
            throw new IOException("Too many events before response.");
        }
        Message readMessage() throws IOException {return ((TransportFrame.JsonFrame)Objects.requireNonNull(TransportCodec.readFrame(input))).message();}
    }
    private static final class Pair implements AutoCloseable {
        final Socket client,server;
        Pair() throws IOException { try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {client=new Socket("127.0.0.1",listener.getLocalPort()); server=listener.accept();} }
        public void close() throws IOException { client.close(); server.close(); }
    }
}
