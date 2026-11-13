package lk.gramalink.server.backend;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.gramalink.server.*;
import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.repository.Database;
import lk.gramalink.server.service.*;
import lk.gramalink.shared.*;
import java.net.Socket;
import java.util.*;

/** Maps typed services to existing framed TCP. Event sends always occur after service commit. */
public final class BackendDispatcher implements MessageDispatcher {
    private final ObjectMapper json=new ObjectMapper();
    private final Database db;
    private final SessionManager sessions;
    private final EventRouter events;
    private final AuthService auth;
    private final RequestService requests;
    private final NotificationService notifications;
    private final AdminService admin;
    private final lk.gramalink.server.service.FileTransferService files;
    public BackendDispatcher(Database db,SessionManager sessions,lk.gramalink.server.service.FileTransferService files) {
        this.db=db; this.sessions=sessions; this.files=files; events=new EventRouter(sessions);
        auth=new AuthService(db); requests=new RequestService(db); notifications=new NotificationService(db);
        admin=new AdminService(db,sessions::getAuthenticatedSessionCount);
    }
    @Override public Message handle(Socket socket,Message command) {
        try {
            ObjectNode p=command.payload();
            if (command.type()==MessageType.LOGIN) {
                if (sessions.findSession(socket).isPresent()) throw ServiceException.invalid("Reconnect before logging in as another account.");
                var identity=auth.login(text(p,"username"),text(p,"password"));
                sessions.registerVerifiedSession(identity,socket);
                return response(MessageType.LOGIN_OK,command,Map.of("userId",identity.userId(),"role",identity.role()));
            }
            SessionManager.Session session=sessions.findSession(socket).orElseThrow(() -> new ServiceException(ServiceException.Code.UNAUTHENTICATED,"Login is required."));
            Actor actor=new Actor(Validation.id(session.identity().userId()));
            Object value=switch (command.type()) {
                case CREATE_REQUEST -> publish(requests.create(actor,new CreateRequest(text(p,"type"),text(p,"title"),text(p,"description"),text(p,"division"))));
                case STATUS_UPDATE -> publish(requests.change(actor,new ChangeRequest(id(p,"requestId"),number(p,"expectedVersion",true),
                        enumeration(Action.class,text(p,"action")),optional(p,"reason"),optional(p,"title"),optional(p,"description"))));
                case CHAT_MESSAGE -> publish(notifications.sendChat(actor,new Chat(id(p,"requestId"),text(p,"body"))));
                case GET_REQUEST -> requests.detail(actor,id(p,"requestId"));
                case LIST_REQUESTS -> requests.list(actor,offset(p));
                case GET_HISTORY -> requests.history(actor,id(p,"requestId"),offset(p));
                case GET_CHAT -> notifications.chat(actor,id(p,"requestId"),offset(p));
                case GET_NOTIFICATIONS -> notifications.notifications(actor,number(p,"afterId",false));
                case READ_NOTIFICATION -> { notifications.markRead(actor,number(p,"notificationId",true)); yield Map.of("read",true); }
                case LIST_DOCUMENTS -> files.list(actor,id(p,"requestId"),offset(p));
                case ME -> admin.me(actor);
                case LIST_OFFICES -> admin.offices(actor,offset(p));
                case LIST_USERS -> admin.users(actor,offset(p));
                case CREATE_USER -> admin.createUser(actor,new NewUser(text(p,"username"),text(p,"password"),enumeration(Role.class,text(p,"role")),nullableId(p,"officeId"),text(p,"division")));
                case SET_USER_ACTIVE -> {
                    if (!p.path("active").isBoolean()) throw ServiceException.invalid("active must be boolean.");
                    admin.setActive(actor,id(p,"userId"),p.path("active").booleanValue()); yield Map.of("updated",true);
                }
                case CREATE_OFFICE -> admin.createOffice(actor,new NewOffice(text(p,"name"),text(p,"kind"),text(p,"division"),nullableId(p,"parentId")));
                case RENAME_OFFICE -> { admin.renameOffice(actor,id(p,"officeId"),text(p,"name")); yield Map.of("updated",true); }
                case GET_STATISTICS -> admin.statistics(actor);
                case GET_AUDIT -> admin.audit(actor,number(p,"afterId",false));
                default -> throw ServiceException.invalid("Unsupported client command.");
            };
            return response(MessageType.ACK,command,value);
        } catch (ServiceException e) { return error(command,e.code().name(),e.getMessage()); }
        catch (RuntimeException e) {
            System.err.println("Backend command failed ("+e.getClass().getSimpleName()+").");
            return error(command,"INTERNAL_ERROR","The operation could not be completed.");
        }
    }
    private <T> T publish(Result<T> result) {
        for (Event event:result.events()) {
            try {
                ObjectNode p=json.createObjectNode(); p.set("data",json.valueToTree(event.payload()));
                List<String> active=db.transaction(c -> {
                    List<String> ids=new ArrayList<>();
                    for(String id:event.recipients()) if(!lk.gramalink.server.repository.Sql.rows(c,"SELECT id FROM users WHERE id=? AND active",UUID.fromString(id)).isEmpty()) ids.add(id);
                    return ids;
                });
                events.sendToUsers(active,new Message(MessageType.valueOf(event.type()),p));
            } catch (Exception e) { System.err.println("Post-commit event delivery failed; reload durable state."); }
        }
        return result.value();
    }
    private Message response(MessageType type,Message command,Object value) {
        ObjectNode payload=json.createObjectNode(); payload.put("operation",command.type().name()); payload.set("data",json.valueToTree(value));
        return new Message(type,command.correlationId(),null,payload);
    }
    private Message error(Message command,String code,String message) {
        return new Message(MessageType.ERROR,command.correlationId(),null,json.createObjectNode().put("code",code).put("message",message));
    }
    private static String text(ObjectNode p,String key) {
        if (!p.path(key).isTextual()) throw ServiceException.invalid(key+" must be text."); return p.path(key).textValue();
    }
    private static String optional(ObjectNode p,String key) { return p.hasNonNull(key) ? text(p,key) : null; }
    private static UUID id(ObjectNode p,String key) { return Validation.id(text(p,key)); }
    private static UUID nullableId(ObjectNode p,String key) { return p.hasNonNull(key) ? id(p,key) : null; }
    private static long number(ObjectNode p,String key,boolean required) {
        JsonNode n=p.get(key); if (n==null && !required) return 0;
        if (n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue()<0) throw ServiceException.invalid(key+" must be a nonnegative integer.");
        return n.longValue();
    }
    private static int offset(ObjectNode p) { long n=number(p,"offset",false); if(n>Integer.MAX_VALUE) throw ServiceException.invalid("offset is too large."); return (int)n; }
    private static <E extends Enum<E>> E enumeration(Class<E> type,String value) {
        try { return Enum.valueOf(type,value); } catch(IllegalArgumentException e) { throw ServiceException.invalid("Unsupported "+type.getSimpleName()+"."); }
    }
}
