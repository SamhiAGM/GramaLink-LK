package lk.gramalink.client;

import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class BackendQueriesTest {
    @Test void citizenCreationUsesActualRequiredFieldsAndDoesNotSupplyIdentity() {
        var draft = new CitizenData.Draft("OTHER", "DEMO-A", "Certificate request", "Please review", null);
        Message sent = CitizenProtocol.productionBackend().createRequest(draft);
        assertEquals(MessageType.CREATE_REQUEST, sent.type());
        assertEquals("Certificate request", sent.payload().path("title").asText());
        assertEquals(4, sent.payload().size());
        assertNotNull(sent.correlationId()); assertNull(sent.token());
        assertThrows(IllegalArgumentException.class, () -> new CitizenData.Draft("OTHER", "DEMO-A", "", "Description", null));
        assertThrows(IllegalArgumentException.class, () -> CitizenProtocol.productionBackend().createRequest(
                new CitizenData.Draft("GUESS", "DEMO-A", "Title", "Description", null)));
    }
    @Test void ackWithoutAuthoritativeOperationDataIsNotAResult() {
        var sent = BackendQueries.command(MessageType.LIST_REQUESTS, BackendQueries.object());
        assertThrows(IOException.class, () -> BackendQueries.result(sent,
                new Message(MessageType.ACK, sent.correlationId(), null, BackendQueries.object())));
        var payload = BackendQueries.object().put("operation", "LIST_REQUESTS"); payload.putArray("data");
        assertThrows(IOException.class, () -> BackendQueries.result(sent,
                new Message(MessageType.ACK, "stale", null, payload)));
    }
    @Test void chatUsesBodyAndValidatesLength() {
        var sent = CitizenProtocol.productionBackend().chatMessage("request-id", "Question");
        assertEquals("Question", sent.payload().path("body").asText()); assertFalse(sent.payload().has("text"));
        assertThrows(IllegalArgumentException.class, () -> CitizenProtocol.productionBackend().chatMessage("id", "x".repeat(4001)));
    }
    @Test void officerMappingsUseNumericVersionsAndActualActionNames() {
        Message returned=DsProtocol.productionBackend().encode(new DsData.Action(DsData.ActionKind.RETURN_TO_GN,"id",new DsData.Version("7"),"Missing evidence"));
        assertEquals("RETURN",returned.payload().path("action").asText());
        assertTrue(returned.payload().path("expectedVersion").isIntegralNumber()); assertEquals(7,returned.payload().path("expectedVersion").longValue());
        assertThrows(IllegalArgumentException.class,()->DsProtocol.productionBackend().encode(new DsData.Action(DsData.ActionKind.APPROVE,"id",new DsData.Version("opaque"),"")));
        Message verified=GnProtocol.productionBackend().encode(new GnData.Action(GnData.ActionKind.VERIFY_REQUEST,"id",null,"",2L));
        assertEquals("VERIFY",verified.payload().path("action").asText()); assertFalse(verified.payload().has("documentId"));
        assertFalse(GnProtocol.productionBackend().supports(GnData.ActionKind.VERIFY_DOCUMENT));
        assertEquals("REQUEST_CORRECTION",GnProtocol.productionBackend().encode(new GnData.Action(GnData.ActionKind.REQUEST_INFORMATION,"id",null,"More detail",2L)).payload().path("action").asText());
    }
    @Test void adminUsesOnlySupportedOperationsAndRequiresPasswordAndGnParent() {
        var protocol=AdminProtocol.productionBackend();
        assertFalse(protocol.supports(AdminData.Operation.UPDATE_DEMO_USER));
        assertFalse(protocol.supports(AdminData.Operation.ARCHIVE_DEMO_OFFICE));
        var role=new AdminData.Choice("CITIZEN","CITIZEN");
        assertThrows(IllegalArgumentException.class,()->protocol.encode(new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER,null,
                new AdminData.UserDraft("test.user","Test",role,null),null,"")));
        var draft=new AdminData.UserDraft("test.user","Test",role,null,"SyntheticTest123!","DEMO-A");
        Message user=protocol.encode(new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER,null,draft,null,""));
        assertEquals(MessageType.CREATE_USER,user.type()); assertEquals("DEMO-A",user.payload().path("division").asText());
        assertFalse(user.payload().has("displayName")); assertFalse(draft.toString().contains("SyntheticTest123!"));
        assertThrows(IllegalArgumentException.class,()->protocol.encode(new AdminData.Command(AdminData.Operation.CREATE_DEMO_OFFICE,null,null,
                new AdminData.OfficeDraft("Office",new AdminData.Choice("GN","GN"),"DEMO-A"),"")));
        var active=protocol.encode(new AdminData.Command(AdminData.Operation.ENABLE_USER,"id",null,null,""));
        assertEquals(MessageType.SET_USER_ACTIVE,active.type()); assertTrue(active.payload().path("active").booleanValue());
    }
}
