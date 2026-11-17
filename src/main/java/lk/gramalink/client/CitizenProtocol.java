package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/** Citizen protocol boundary. Business operations remain disabled until their adapters are implemented. */
public interface CitizenProtocol {
    enum Operation { CREATE_REQUEST, CHAT_MESSAGE }
    default Optional<CitizenData.Identity> citizenAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(Operation operation) { return false; }
    default boolean loadsBackend() { return false; }
    default Message createRequest(CitizenData.Draft draft) { throw pending(); }
    default Message chatMessage(String requestId, String text) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<CitizenData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() {
        return new UnsupportedOperationException("This operation is waiting for the backend protocol contract.");
    }
    static CitizenProtocol productionBackend() {
        return new CitizenProtocol() {
            @Override public boolean loadsBackend() { return true; }
            @Override public boolean supports(Operation operation) { return true; }
            @Override public Message createRequest(CitizenData.Draft draft) {
                if (!java.util.Set.of("RESIDENCE", "INCOME", "CHARACTER", "OTHER").contains(draft.type()))
                    throw new IllegalArgumentException("Choose RESIDENCE, INCOME, CHARACTER or OTHER.");
                return BackendQueries.command(lk.gramalink.shared.MessageType.CREATE_REQUEST, BackendQueries.object()
                        .put("type", draft.type()).put("title", draft.title()).put("description", draft.description()).put("division", draft.division()));
            }
            @Override public Message chatMessage(String requestId, String text) {
                if (text.length() > 4000) throw new IllegalArgumentException("Messages must contain at most 4000 characters.");
                return BackendQueries.command(lk.gramalink.shared.MessageType.CHAT_MESSAGE,
                        BackendQueries.object().put("requestId", requestId).put("body", text));
            }
            @Override public Optional<CitizenData.Identity> citizenAuthorization(Message reply) {
                return BackendLoginContract.identity(reply, "CITIZEN")
                        .map(identity -> new CitizenData.Identity(identity.userId(), BackendLoginContract.displayName(identity)));
            }
        };
    }
    static CitizenProtocol pendingBackend() { return new CitizenProtocol() { }; }
}
