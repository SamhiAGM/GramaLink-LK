package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/** GN protocol boundary. Business operations remain disabled until their adapters are implemented. */
public interface GnProtocol {
    default Optional<GnData.Identity> gnAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(GnData.ActionKind action) { return false; }
    default boolean permits(GnData.ActionKind action, GnData.RequestRow row) { return supports(action); }
    default boolean loadsBackend() { return false; }
    /** Only encode once Shadhir and Samhi agree the operation's payload and existing wire type. */
    default Message encode(GnData.Action action) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<GnData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() {
        return new UnsupportedOperationException("GN officer integration is waiting for the backend contract.");
    }
    static GnProtocol productionBackend() {
        return new GnProtocol() {
            @Override public boolean loadsBackend() { return true; }
            @Override public boolean supports(GnData.ActionKind action) { return action != null && action != GnData.ActionKind.VERIFY_DOCUMENT; }
            @Override public boolean permits(GnData.ActionKind action, GnData.RequestRow row) {
                if (action == null || row == null || !supports(action)) return false;
                if (action == GnData.ActionKind.CHAT) return true;
                if (row.version() == null || row.version() < 0 || row.status() == null) return false;
                return switch (action) {
                    case RECEIVE -> row.status().equals("SUBMITTED");
                    case REVIEW -> java.util.Set.of("RECEIVED_BY_GN", "RETURNED_TO_GN").contains(row.status());
                    case VERIFY_REQUEST -> row.status().equals("UNDER_GN_REVIEW");
                    case FORWARD_TO_DS -> row.status().equals("VERIFIED_BY_GN");
                    case REQUEST_INFORMATION, REJECT_REQUEST -> java.util.Set.of("RECEIVED_BY_GN", "UNDER_GN_REVIEW", "VERIFIED_BY_GN").contains(row.status());
                    default -> false;
                };
            }
            @Override public Message encode(GnData.Action action) {
                if(action.kind()==GnData.ActionKind.CHAT) return BackendCommands.chat(action.requestId(),action.note());
                String name=switch(action.kind()) {
                    case RECEIVE -> "RECEIVE"; case REVIEW -> "REVIEW"; case VERIFY_REQUEST -> "VERIFY";
                    case REQUEST_INFORMATION -> "REQUEST_CORRECTION"; case FORWARD_TO_DS -> "FORWARD"; case REJECT_REQUEST -> "REJECT";
                    default -> throw new UnsupportedOperationException("Document-ID verification is not a backend operation.");
                };
                return BackendCommands.change(action.requestId(),action.expectedVersion(),name,action.note());
            }
            @Override public Optional<GnData.Identity> gnAuthorization(Message reply) {
                return BackendLoginContract.identity(reply, "GN")
                        .map(identity -> new GnData.Identity(identity.userId(), BackendLoginContract.displayName(identity)));
            }
        };
    }
    static GnProtocol pendingBackend() { return new GnProtocol() { }; }
}
