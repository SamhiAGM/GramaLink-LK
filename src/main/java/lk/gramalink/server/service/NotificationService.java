package lk.gramalink.server.service;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.backend.Validation;
import lk.gramalink.server.repository.*;
import java.util.*;

public final class NotificationService extends ServiceSupport {
    public NotificationService(Database db) { super(db); }
    public Result<Map<String, Object>> sendChat(Actor actor, Chat command) {
        return guarded(actor, "CHAT_MESSAGE", c -> {
            User u = actor(c, actor); Request r = requests.require(c, command.requestId()); access(u, r);
            if (u.role() == Role.ADMIN) throw ServiceException.forbidden();
            String body = Validation.text(command.body(), 4000, "body"); UUID id = UUID.randomUUID();
            Sql.update(c, "INSERT INTO messages(id,request_id,sender_id,body) VALUES (?,?,?,?)", id, r.id(), u.id(), body);
            notify(c, r, "CHAT_MESSAGE", "New request message.");
            audit(c, u.id(), r.id(), "CHAT_MESSAGE", "SUCCESS");
            var row = Sql.rows(c, "SELECT * FROM messages WHERE id=?", id).getFirst();
            return new Result<>(row, List.of(new Event("CHAT_MESSAGE", recipients(r), row),
                    new Event("NOTIFICATION", recipients(r), Map.of("requestId", r.id(), "refresh", true))));
        });
    }
    public List<Map<String, Object>> chat(Actor actor, UUID request, int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor, "GET_CHAT", c -> { User u = actor(c, actor); access(u, requests.require(c, request));
            return Sql.rows(c, "SELECT * FROM messages WHERE request_id=? ORDER BY created_at,id LIMIT 50 OFFSET ?", request, offset); });
    }
    public List<Map<String, Object>> notifications(Actor actor, long afterId) {
        if (afterId < 0) throw ServiceException.invalid("afterId must be nonnegative.");
        return guarded(actor, "GET_NOTIFICATIONS", c -> { User u = actor(c, actor);
            return Sql.rows(c, "SELECT * FROM notifications WHERE user_id=? AND id>? ORDER BY id LIMIT 100", u.id(), afterId); });
    }
    public void markRead(Actor actor, long id) {
        guarded(actor, "READ_NOTIFICATION", c -> {
            User u = actor(c, actor);
            if (Sql.update(c, "UPDATE notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=? AND user_id=?", id, u.id()) != 1)
                throw new ServiceException(ServiceException.Code.NOT_FOUND, "Notification not found.");
            return null;
        });
    }
}
