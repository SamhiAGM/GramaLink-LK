package lk.gramalink.client;

import lk.gramalink.server.backend.Models;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.service.RequestService;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GnProtocolTest {
    private static GnData.RequestRow row(String status, Long version) {
        return new GnData.RequestRow("request-id", "Citizen", "INCOME", Instant.EPOCH, status, null, version);
    }
    @Test void availabilityMatchesEveryBackendGnTransitionAndEncoderUsesActualContract() {
        var protocol = GnProtocol.productionBackend();
        var actions = Map.of(GnData.ActionKind.RECEIVE, Models.Action.RECEIVE,
                GnData.ActionKind.REVIEW, Models.Action.REVIEW, GnData.ActionKind.VERIFY_REQUEST, Models.Action.VERIFY,
                GnData.ActionKind.REQUEST_INFORMATION, Models.Action.REQUEST_CORRECTION,
                GnData.ActionKind.FORWARD_TO_DS, Models.Action.FORWARD, GnData.ActionKind.REJECT_REQUEST, Models.Action.REJECT);
        for (var entry : actions.entrySet()) {
            for (var state : Models.Status.values()) {
                boolean permitted;
                try { RequestService.next(Models.Role.GN, state, entry.getValue()); permitted = true; }
                catch (ServiceException e) { assertEquals(ServiceException.Code.INVALID_TRANSITION, e.code()); permitted = false; }
                assertEquals(permitted, protocol.permits(entry.getKey(), row(state.name(), 0L)), state + "/" + entry.getKey());
            }
            var message = protocol.encode(new GnData.Action(entry.getKey(), "request-id", null, "Required reason", 0L));
            assertEquals(MessageType.STATUS_UPDATE, message.type());
            assertEquals(entry.getValue().name(), message.payload().path("action").asText());
            assertEquals("request-id", message.payload().path("requestId").asText());
            assertTrue(message.payload().path("expectedVersion").isIntegralNumber());
            assertEquals(0L, message.payload().path("expectedVersion").longValue());
        }
    }
    @Test void unavailableOrUnversionedRequestsFailClosed() {
        var protocol = GnProtocol.productionBackend();
        assertFalse(protocol.permits(GnData.ActionKind.RECEIVE, null));
        assertFalse(protocol.permits(GnData.ActionKind.RECEIVE, row("SUBMITTED", null)));
        assertFalse(protocol.permits(GnData.ActionKind.RECEIVE, row("SUBMITTED", -1L)));
        assertFalse(protocol.permits(GnData.ActionKind.RECEIVE, row("unknown", 0L)));
        assertFalse(protocol.permits(GnData.ActionKind.VERIFY_DOCUMENT, row("UNDER_GN_REVIEW", 0L)));
        assertFalse(protocol.supports(null));
    }
}
