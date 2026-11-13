package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/** Backend contract boundary. A future adapter must validate GN role, assignments and payloads. */
public interface GnProtocol {
    default Optional<GnData.Identity> gnAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(GnData.ActionKind action) { return false; }
    /** Only encode once Shadhir and Samhi agree the operation's payload and existing wire type. */
    default Message encode(GnData.Action action) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<GnData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() {
        return new UnsupportedOperationException("GN officer integration is waiting for the backend contract.");
    }
    static GnProtocol pendingBackend() { return new GnProtocol() { }; }
}
