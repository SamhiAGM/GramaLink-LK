package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/**
 * Integration boundary for the backend's future documented application payloads.
 * No production decoder exists yet. Implementations must validate server identity,
 * Citizen role, ownership and complete payload shapes; client-entered roles are never evidence.
 */
public interface CitizenProtocol {
    enum Operation { CREATE_REQUEST, CHAT_MESSAGE }
    default Optional<CitizenData.Identity> citizenAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(Operation operation) { return false; }
    default Message createRequest(CitizenData.Draft draft) { throw pending(); }
    default Message chatMessage(String requestId, String text) { throw pending(); }
    default Optional<CitizenData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() {
        return new UnsupportedOperationException("This operation is waiting for the backend protocol contract.");
    }
    static CitizenProtocol pendingBackend() { return new CitizenProtocol() { }; }
}
