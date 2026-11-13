package lk.gramalink.server.service;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.backend.Validation;
import lk.gramalink.server.repository.*;
import org.mindrot.jbcrypt.BCrypt;
import java.util.*;
import java.util.function.IntSupplier;

public final class AdminService extends ServiceSupport {
    private final IntSupplier onlineCount;
    public AdminService(Database db, IntSupplier onlineCount) { super(db); this.onlineCount = onlineCount; }
    public List<Map<String, Object>> offices(Actor actor) {
        return offices(actor,0);
    }
    public List<Map<String, Object>> offices(Actor actor, int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor, "LIST_OFFICES", c -> { actor(c, actor); return Sql.rows(c, "SELECT * FROM offices ORDER BY division,name,id LIMIT 100 OFFSET ?", offset); });
    }
    public User me(Actor actor) { return guarded(actor, "ME", c -> actor(c, actor)); }
    public List<Map<String, Object>> users(Actor actor, int offset) {
        if (offset < 0) throw ServiceException.invalid("offset must be nonnegative.");
        return guarded(actor, "LIST_USERS", c -> { admin(actor(c, actor));
            return Sql.rows(c, "SELECT id,username,role,office_id,division,active FROM users ORDER BY username LIMIT 100 OFFSET ?", offset); });
    }
    public UUID createUser(Actor actor, NewUser cmd) {
        return guarded(actor, "CREATE_USER", c -> {
            User admin = actor(c, actor); admin(admin);
            String username = Validation.text(cmd.username(), 80, "username"), division = Validation.text(cmd.division(), 80, "division");
            if (!username.matches("[A-Za-z0-9_.-]+")) throw ServiceException.invalid("Invalid username characters.");
            Validation.password(cmd.password()); if (cmd.role() == null) throw ServiceException.invalid("role is required.");
            if (cmd.role() == Role.GN || cmd.role() == Role.DS) {
                var office = Sql.rows(c, "SELECT * FROM offices WHERE id=? AND kind=? AND division=?", cmd.officeId(), cmd.role().name(), division);
                if (office.isEmpty()) throw ServiceException.invalid("Officer role/division must match the office.");
            } else if (cmd.officeId() != null) throw ServiceException.invalid("Citizen/Admin accounts cannot have officer offices.");
            UUID id = UUID.randomUUID();
            Sql.update(c, "INSERT INTO users(id,username,password_hash,role,office_id,division) VALUES (?,?,?,?,?,?)",
                    id, username, BCrypt.hashpw(cmd.password(), BCrypt.gensalt(12)), cmd.role().name(), cmd.officeId(), division);
            audit(c, admin.id(), null, "CREATE_USER", "SUCCESS"); return id;
        });
    }
    public void setActive(Actor actor, UUID id, boolean active) {
        guarded(actor, "SET_USER_ACTIVE", c -> {
            User u = actor(c, actor); admin(u);
            var targets = Sql.rows(c, "SELECT role FROM users WHERE id=?", id);
            if (targets.isEmpty()) throw new ServiceException(ServiceException.Code.NOT_FOUND,"User not found.");
            if (u.id().equals(id) || "ADMIN".equals(targets.getFirst().get("role"))) throw ServiceException.forbidden();
            if (!active && !Sql.rows(c, "SELECT id FROM requests WHERE (citizen_id=? OR gn_id=? OR ds_id=?) AND status NOT IN ('APPROVED','REJECTED') LIMIT 1", id, id, id).isEmpty())
                throw ServiceException.invalid("Account has active request assignments.");
            Sql.update(c, "UPDATE users SET active=? WHERE id=?", active, id);
            audit(c, u.id(), null, "SET_USER_ACTIVE", "SUCCESS"); return null;
        });
    }
    public UUID createOffice(Actor actor, NewOffice cmd) {
        return guarded(actor, "CREATE_OFFICE", c -> {
            User u = actor(c, actor); admin(u);
            String name = Validation.text(cmd.name(), 120, "name"), division = Validation.text(cmd.division(), 80, "division");
            if (!Set.of("GN", "DS").contains(Objects.toString(cmd.kind(), ""))) throw ServiceException.invalid("kind must be GN or DS.");
            if (cmd.kind().equals("GN") && Sql.rows(c, "SELECT id FROM offices WHERE id=? AND kind='DS'", cmd.parentId()).isEmpty())
                throw ServiceException.invalid("A GN office requires a DS parent.");
            if (cmd.kind().equals("DS") && cmd.parentId() != null) throw ServiceException.invalid("DS offices have no parent.");
            UUID id = UUID.randomUUID(); Sql.update(c, "INSERT INTO offices(id,name,kind,division,parent_id) VALUES (?,?,?,?,?)", id, name, cmd.kind(), division, cmd.parentId());
            audit(c, u.id(), null, "CREATE_OFFICE", "SUCCESS"); return id;
        });
    }
    public void renameOffice(Actor actor, UUID id, String name) {
        guarded(actor, "RENAME_OFFICE", c -> { User u = actor(c, actor); admin(u);
            if (Sql.update(c, "UPDATE offices SET name=? WHERE id=?", Validation.text(name,120,"name"), id) != 1)
                throw new ServiceException(ServiceException.Code.NOT_FOUND,"Office not found.");
            audit(c, u.id(), null, "RENAME_OFFICE", "SUCCESS"); return null; });
    }
    public Map<String, Object> statistics(Actor actor) {
        Map<String,Object> stats = guarded(actor, "STATISTICS", c -> { admin(actor(c, actor));
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("requestsByStatus", Sql.rows(c, "SELECT status,count(*) AS count FROM requests GROUP BY status ORDER BY status"));
            result.put("usersByRole", Sql.rows(c, "SELECT role,count(*) AS count FROM users GROUP BY role ORDER BY role")); return result; });
        stats.put("onlineConnections", onlineCount.getAsInt()); return stats;
    }
    public List<Map<String, Object>> audit(Actor actor, long afterId) {
        if (afterId < 0) throw ServiceException.invalid("afterId must be nonnegative.");
        return guarded(actor, "GET_AUDIT", c -> { admin(actor(c, actor)); return Sql.rows(c, "SELECT * FROM audit_logs WHERE id>? ORDER BY id LIMIT 100", afterId); });
    }
}
