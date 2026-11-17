package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.util.*;

/** Administrator operations supported by the current backend contract. */
public interface AdminProtocol {
    default Optional<AdminData.Identity> administratorAuthorization(Message correlatedLoginReply) { return Optional.empty(); }
    default boolean supports(AdminData.Operation operation) { return false; }
    default boolean loadsBackend() { return false; }
    /** Explicit wire types; unsupported operations never borrow unrelated business messages. */
    default Set<MessageType> outboundTypes(AdminData.Operation operation) { return Set.of(); }
    default Set<MessageType> eventTypes() { return Set.of(); }
    default Message encode(AdminData.Command command) { throw pending(); }
    /** Fail closed: validate current-session ownership/scope and the agreed event contract before decoding. */
    default boolean acceptsEvent(Message message) { return false; }
    default Optional<AdminData.Update> decodeEvent(Message message) { return Optional.empty(); }
    static UnsupportedOperationException pending() { return new UnsupportedOperationException("Administrative integration awaits a coordinated backend protocol contract."); }
    static AdminProtocol productionBackend() {
        return new AdminProtocol() {
            @Override public boolean loadsBackend() { return true; }
            @Override public boolean supports(AdminData.Operation operation) { return !outboundTypes(operation).isEmpty(); }
            @Override public Set<MessageType> outboundTypes(AdminData.Operation operation) {
                return switch(operation) {
                    case CREATE_DEMO_USER -> Set.of(MessageType.CREATE_USER);
                    case DISABLE_DEMO_USER,ENABLE_USER -> Set.of(MessageType.SET_USER_ACTIVE);
                    case CREATE_DEMO_OFFICE -> Set.of(MessageType.CREATE_OFFICE);
                    case UPDATE_DEMO_OFFICE -> Set.of(MessageType.RENAME_OFFICE);
                    default -> Set.of();
                };
            }
            @Override public Message encode(AdminData.Command command) {
                var p=BackendQueries.object();
                MessageType type=switch(command.operation()) {
                    case CREATE_DEMO_USER -> {
                        var u=command.user();
                        if(u.password()==null || u.password().length()<10 || u.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
                            throw new IllegalArgumentException("A new account password requires at least 10 characters and at most 72 UTF-8 bytes.");
                        if(u.division()==null || u.division().isBlank()) throw new IllegalArgumentException("Enter the account division.");
                        p.put("username",u.username()).put("password",u.password()).put("role",u.role().code()).put("division",u.division());
                        if(u.officeId()!=null) p.put("officeId",u.officeId());
                        yield MessageType.CREATE_USER;
                    }
                    case ENABLE_USER,DISABLE_DEMO_USER -> { p.put("userId",command.targetId()).put("active",command.operation()==AdminData.Operation.ENABLE_USER); yield MessageType.SET_USER_ACTIVE; }
                    case CREATE_DEMO_OFFICE -> {
                        var o=command.office(); p.put("name",o.name()).put("kind",o.kind().code()).put("division",o.area());
                        if("GN".equals(o.kind().code()) && (o.parentId()==null || o.parentId().isBlank())) throw new IllegalArgumentException("A GN office requires a parent DS office ID.");
                        if(o.parentId()!=null && !o.parentId().isBlank()) p.put("parentId",o.parentId());
                        yield MessageType.CREATE_OFFICE;
                    }
                    case UPDATE_DEMO_OFFICE -> { p.put("officeId",command.targetId()).put("name",command.office().name()); yield MessageType.RENAME_OFFICE; }
                    default -> throw pending();
                };
                return BackendQueries.command(type,p);
            }
            @Override public Optional<AdminData.Identity> administratorAuthorization(Message reply) {
                return BackendLoginContract.identity(reply, "ADMIN")
                        .map(identity -> new AdminData.Identity(identity.userId(), BackendLoginContract.displayName(identity)));
            }
        };
    }
    static AdminProtocol pendingBackend() { return new AdminProtocol() { }; }
}
