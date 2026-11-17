package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/** DS protocol boundary. Business operations remain disabled until their adapters are implemented. */
public interface DsProtocol {
    default Optional<DsData.Identity> dsAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(DsData.ActionKind kind) { return false; }
    default boolean loadsBackend() { return false; }
    /** Conservative default. A production adapter must implement the agreed per-operation rule. */
    default boolean requiresVersion(DsData.ActionKind kind) { return kind != DsData.ActionKind.CHAT; }
    /** Must encode expectedVersion using the exact backend field/type when required. */
    default Message encode(DsData.Action action) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<DsData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() { return new UnsupportedOperationException("DS integration is waiting for the backend contract."); }
    static DsProtocol productionBackend() {
        return new DsProtocol() {
            @Override public boolean loadsBackend() { return true; }
            @Override public boolean supports(DsData.ActionKind kind) { return true; }
            @Override public Message encode(DsData.Action action) {
                if(action.kind()==DsData.ActionKind.CHAT) return BackendCommands.chat(action.requestId(),action.note());
                return BackendCommands.change(action.requestId(),BackendCommands.version(action.expectedVersion()),
                        action.kind()==DsData.ActionKind.RETURN_TO_GN ? "RETURN" : action.kind().name(),action.note());
            }
            @Override public Optional<DsData.Identity> dsAuthorization(Message reply) {
                return BackendLoginContract.identity(reply, "DS")
                        .map(identity -> new DsData.Identity(identity.userId(), BackendLoginContract.displayName(identity)));
            }
        };
    }
    static DsProtocol pendingBackend() { return new DsProtocol() { }; }
}
