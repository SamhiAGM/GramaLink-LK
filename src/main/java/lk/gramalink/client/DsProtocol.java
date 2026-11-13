package lk.gramalink.client;

import lk.gramalink.shared.Message;
import java.util.Optional;

/** Pending DS application contract; role claims, payload fields and version encodings are not guessed. */
public interface DsProtocol {
    default Optional<DsData.Identity> dsAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(DsData.ActionKind kind) { return false; }
    /** Conservative default. A production adapter must implement the agreed per-operation rule. */
    default boolean requiresVersion(DsData.ActionKind kind) { return kind != DsData.ActionKind.CHAT; }
    /** Must encode expectedVersion using the exact backend field/type when required. */
    default Message encode(DsData.Action action) { throw pending(); }
    default Optional<DsData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() { return new UnsupportedOperationException("DS integration is waiting for the backend contract."); }
    static DsProtocol pendingBackend() { return new DsProtocol() { }; }
}
