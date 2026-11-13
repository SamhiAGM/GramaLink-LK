package lk.gramalink.server.repository;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import java.sql.*;
import java.util.UUID;

public final class UserRepository {
    public User require(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = Sql.prepare(c, "SELECT id,username,role,office_id,division,active FROM users WHERE id=?", id);
             ResultSet r = p.executeQuery()) {
            if (!r.next() || !r.getBoolean("active")) throw new ServiceException(ServiceException.Code.UNAUTHENTICATED, "An active account is required.");
            return new User(r.getObject("id", UUID.class), r.getString("username"), Role.valueOf(r.getString("role")),
                    r.getObject("office_id", UUID.class), r.getString("division"), true);
        }
    }
}
