package lk.gramalink.server.service;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.backend.Validation;
import lk.gramalink.server.repository.*;
import java.sql.*;
import java.util.*;

public final class RequestService extends ServiceSupport {
    public RequestService(Database db) { super(db); }
    public Result<Request> create(Actor actor, CreateRequest command) {
        return guarded(actor, "CREATE_REQUEST", c -> {
            User u = actor(c, actor);
            if (u.role() != Role.CITIZEN) throw ServiceException.forbidden();
            String division = Validation.text(command.division(), 80, "division");
            if (!u.division().equals(division)) throw ServiceException.forbidden();
            String type = Validation.text(command.type(), 30, "type");
            if (!Set.of("RESIDENCE", "INCOME", "CHARACTER", "OTHER").contains(type)) throw ServiceException.invalid("Unsupported request type.");
            String title = Validation.text(command.title(), 160, "title"), description = Validation.text(command.description(), 5000, "description");
            var assignments = Sql.rows(c, """
                SELECT g.id AS gn_id,d.id AS ds_id,o.id AS gn_office_id,p.id AS ds_office_id
                FROM offices o JOIN offices p ON p.id=o.parent_id AND p.kind='DS'
                JOIN users g ON g.office_id=o.id AND g.role='GN' AND g.active AND g.division=o.division
                JOIN users d ON d.office_id=p.id AND d.role='DS' AND d.active
                WHERE o.kind='GN' AND o.division=? ORDER BY g.id,d.id LIMIT 1
                """, division);
            if (assignments.isEmpty()) throw ServiceException.invalid("No active GN/DS assignment is configured for this division.");
            var a = assignments.getFirst(); UUID id = UUID.randomUUID();
            Sql.update(c, """
                INSERT INTO requests(id,citizen_id,gn_id,ds_id,gn_office_id,ds_office_id,division,type,title,description,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,'SUBMITTED')
                """, id, u.id(), a.get("gn_id"), a.get("ds_id"), a.get("gn_office_id"), a.get("ds_office_id"), division, type, title, description);
            Request request = requests.require(c, id);
            history(c, u, request, null, "CREATE", null);
            audit(c, u.id(), id, "CREATE_REQUEST", "SUCCESS");
            notify(c, request, "NEW_REQUEST", "New request submitted.");
            return result(request, "NEW_REQUEST");
        });
    }
    public Result<Request> change(Actor actor, ChangeRequest command) {
        return guarded(actor, "STATUS_UPDATE", c -> {
            User u = actor(c, actor); Request old = requests.require(c, command.requestId()); access(u, old);
            if (command.expectedVersion() < 0) throw ServiceException.invalid("expectedVersion must be nonnegative.");
            if (old.version() != command.expectedVersion()) throw new ServiceException(ServiceException.Code.CONFLICT, "Request changed. Reload it before retrying.");
            Status next = next(u.role(), old.status(), command.action());
            String reason = command.reason();
            if (Set.of(Action.REQUEST_CORRECTION, Action.RETURN, Action.REJECT).contains(command.action())) reason = Validation.text(reason, 1000, "reason");
            else if (reason != null) reason = Validation.text(reason, 1000, "reason");
            String title = old.title(), description = old.description();
            if (command.action() == Action.RESUBMIT) {
                title = Validation.text(command.title(), 160, "title");
                description = Validation.text(command.description(), 5000, "description");
            }
            requests.saveVersion(c, old, next, title, description);
            Request changed = requests.require(c, old.id());
            history(c, u, changed, old.status(), command.action().name(), reason);
            audit(c, u.id(), old.id(), command.action().name(), "SUCCESS");
            notify(c, changed, "REQUEST_UPDATED", "Request status: " + next.name());
            return result(changed, "REQUEST_UPDATED");
        });
    }
    public static Status next(Role role, Status state, Action action) {
        if (action == null) throw ServiceException.invalid("action is required.");
        Status next = switch (role) {
            case CITIZEN -> action == Action.RESUBMIT && state == Status.NEEDS_CORRECTION ? Status.SUBMITTED : null;
            case GN -> switch (action) {
                case RECEIVE -> state == Status.SUBMITTED ? Status.RECEIVED_BY_GN : null;
                case REVIEW -> Set.of(Status.RECEIVED_BY_GN, Status.RETURNED_TO_GN).contains(state) ? Status.UNDER_GN_REVIEW : null;
                case VERIFY -> state == Status.UNDER_GN_REVIEW ? Status.VERIFIED_BY_GN : null;
                case FORWARD -> state == Status.VERIFIED_BY_GN ? Status.FORWARDED_TO_DS : null;
                case REQUEST_CORRECTION -> Set.of(Status.RECEIVED_BY_GN, Status.UNDER_GN_REVIEW, Status.VERIFIED_BY_GN).contains(state) ? Status.NEEDS_CORRECTION : null;
                case REJECT -> Set.of(Status.RECEIVED_BY_GN, Status.UNDER_GN_REVIEW, Status.VERIFIED_BY_GN).contains(state) ? Status.REJECTED : null;
                default -> null;
            };
            case DS -> state != Status.FORWARDED_TO_DS ? null : switch (action) {
                case APPROVE -> Status.APPROVED;
                case REJECT -> Status.REJECTED;
                case RETURN -> Status.RETURNED_TO_GN;
                default -> null;
            };
            case ADMIN -> null;
        };
        if (next == null) throw new ServiceException(ServiceException.Code.INVALID_TRANSITION, "Action is not permitted for this role and request status.");
        return next;
    }
    private void history(Connection c, User u, Request r, Status from, String action, String reason) throws SQLException {
        Sql.update(c, "INSERT INTO request_history(request_id,actor_id,from_status,to_status,action,reason,version) VALUES (?,?,?,?,?,?,?)",
                r.id(), u.id(), from == null ? null : from.name(), r.status().name(), action, reason, r.version());
    }
    private Result<Request> result(Request r, String type) {
        return new Result<>(r, List.of(new Event(type, recipients(r), r),
                new Event("NOTIFICATION", recipients(r), Map.of("requestId", r.id(), "refresh", true))));
    }
    public Request detail(Actor actor, UUID id) {
        return guarded(actor, "GET_REQUEST", c -> { User u = actor(c, actor); Request r = requests.require(c, id); access(u, r); return r; });
    }
    public List<Map<String, Object>> history(Actor actor, UUID id) {
        return history(actor,id,0);
    }
    public List<Map<String, Object>> history(Actor actor, UUID id, int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor, "GET_HISTORY", c -> { User u = actor(c, actor); Request r = requests.require(c, id); access(u, r);
            return Sql.rows(c, "SELECT * FROM request_history WHERE request_id=? ORDER BY version LIMIT 100 OFFSET ?", id, offset); });
    }
    public List<Map<String, Object>> list(Actor actor, int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor, "LIST_REQUESTS", c -> {
            User u = actor(c, actor);
            return Sql.rows(c, """
                SELECT * FROM requests WHERE (?='ADMIN') OR
                (?='CITIZEN' AND citizen_id=? AND division=?) OR
                (?='GN' AND gn_id=? AND gn_office_id=? AND division=?) OR
                (?='DS' AND ds_id=? AND ds_office_id=? AND status IN ('FORWARDED_TO_DS','RETURNED_TO_GN','APPROVED','REJECTED'))
                ORDER BY created_at DESC,id LIMIT 50 OFFSET ?
                """, u.role().name(), u.role().name(), u.id(), u.division(), u.role().name(), u.id(), u.officeId(), u.division(),
                    u.role().name(), u.id(), u.officeId(), offset);
        });
    }
}
