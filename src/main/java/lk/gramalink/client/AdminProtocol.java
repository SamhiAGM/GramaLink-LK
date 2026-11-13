package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.util.*;

/** No administrative wire operations exist yet. A coordinated backend contract must supply all capabilities. */
public interface AdminProtocol {
    default Optional<AdminData.Identity> administratorAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(AdminData.Operation operation) { return false; }
    /** Empty today; do not repurpose CREATE_REQUEST/STATUS_UPDATE as undocumented administrative operations. */
    default Set<MessageType> outboundTypes(AdminData.Operation operation) { return Set.of(); }
    default Set<MessageType> eventTypes() { return Set.of(); }
    default Message encode(AdminData.Command command) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<AdminData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() { return new UnsupportedOperationException("Administrative integration awaits a coordinated backend protocol contract."); }
    static AdminProtocol pendingBackend() { return new AdminProtocol() { }; }
}
