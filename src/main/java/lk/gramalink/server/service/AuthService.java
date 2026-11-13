package lk.gramalink.server.service;

import lk.gramalink.server.SessionManager.VerifiedIdentity;
import lk.gramalink.server.backend.ServiceException;
import lk.gramalink.server.repository.*;
import org.mindrot.jbcrypt.BCrypt;
import java.util.*;

public final class AuthService {
    private final Database db;
    private static final String DUMMY = BCrypt.hashpw("not-a-demo-password", BCrypt.gensalt(12));
    public AuthService(Database db) { this.db = db; }
    public VerifiedIdentity login(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 80 || password == null
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            db.transaction(c -> { Sql.update(c,"INSERT INTO login_logs(user_id,success) VALUES (NULL,FALSE)"); return null; });
            throw new ServiceException(ServiceException.Code.UNAUTHENTICATED, "Invalid username or password.");
        }
        VerifiedIdentity identity = db.transaction(c -> {
            var rows = Sql.rows(c, "SELECT id,role,password_hash,active FROM users WHERE username=?", username);
            Map<String, Object> row = rows.isEmpty() ? null : rows.getFirst();
            boolean valid = BCrypt.checkpw(password, row == null ? DUMMY : (String) row.get("password_hash"));
            boolean accepted = valid && row != null && Boolean.TRUE.equals(row.get("active"));
            UUID id = row == null ? null : (UUID) row.get("id");
            Sql.update(c, "INSERT INTO login_logs(user_id,success) VALUES (?,?)", id, accepted);
            return accepted ? new VerifiedIdentity(id.toString(), (String) row.get("role")) : null;
        });
        if (identity == null) throw new ServiceException(ServiceException.Code.UNAUTHENTICATED, "Invalid username or password.");
        return identity;
    }
}
