package lk.gramalink.server.service;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.repository.*;
import java.sql.*;
import java.util.*;

public class ServiceSupport {
    protected final Database db;
    protected final UserRepository users = new UserRepository();
    protected final RequestRepository requests = new RequestRepository();
    public ServiceSupport(Database db) { this.db = db; }
    protected User actor(Connection c, Actor actor) throws SQLException {
        if (actor == null || actor.userId() == null) throw new ServiceException(ServiceException.Code.UNAUTHENTICATED, "Login is required.");
        return users.require(c, actor.userId());
    }
    protected void admin(User u) { if (u.role() != Role.ADMIN) throw ServiceException.forbidden(); }
    protected void access(User u, Request r) {
        boolean permitted = switch (u.role()) {
            case ADMIN -> true;
            case CITIZEN -> u.id().equals(r.citizenId()) && u.division().equals(r.division());
            case GN -> u.id().equals(r.gnId()) && Objects.equals(u.officeId(), r.gnOfficeId()) && u.division().equals(r.division());
            case DS -> u.id().equals(r.dsId()) && Objects.equals(u.officeId(), r.dsOfficeId());
        };
        if (!permitted) throw ServiceException.forbidden();
    }
    protected List<String> recipients(Request r) { return List.of(r.citizenId().toString(), r.gnId().toString(), r.dsId().toString()); }
    protected void audit(Connection c, UUID actor, UUID request, String action, String outcome) throws SQLException {
        Sql.update(c, "INSERT INTO audit_logs(actor_id,request_id,action,outcome) VALUES (?,?,?,?)", actor, request, action, outcome);
    }
    /** Denied operations are audited in a new transaction after the business transaction rolls back. */
    protected <T> T guarded(Actor actor, String action, Database.Work<T> work) {
        try { return db.transaction(work); }
        catch (ServiceException e) {
            rejected(actor, action, e);
            throw e;
        }
    }
    protected void rejected(Actor actor, String action, ServiceException e) {
        try { db.transaction(c -> {
                UUID id = actor == null ? null : actor.userId();
                if (id != null && Sql.rows(c, "SELECT id FROM users WHERE id=?", id).isEmpty()) id = null;
                audit(c, id, null, action, e.code().name()); return null;
            }); } catch (RuntimeException ignored) { System.err.println("Unable to persist rejected-operation audit."); }
    }
    protected void notify(Connection c, Request r, String type, String body) throws SQLException {
        for (String id : recipients(r)) Sql.update(c,
                "INSERT INTO notifications(user_id,request_id,event_type,body) VALUES (?,?,?,?)", UUID.fromString(id), r.id(), type, body);
    }
}
