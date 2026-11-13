package lk.gramalink.server.repository;

import lk.gramalink.server.backend.Models.*;
import lk.gramalink.server.backend.ServiceException;
import java.sql.*;
import java.util.UUID;

public final class RequestRepository {
    public Request require(Connection c, UUID id) throws SQLException {
        try (PreparedStatement p = Sql.prepare(c, "SELECT * FROM requests WHERE id=?", id); ResultSet r = p.executeQuery()) {
            if (!r.next()) throw new ServiceException(ServiceException.Code.NOT_FOUND, "Request not found.");
            return new Request(r.getObject("id", UUID.class), r.getObject("citizen_id", UUID.class),
                    r.getObject("gn_id", UUID.class), r.getObject("ds_id", UUID.class),
                    r.getObject("gn_office_id", UUID.class), r.getObject("ds_office_id", UUID.class),
                    r.getString("division"), r.getString("type"), r.getString("title"), r.getString("description"),
                    Status.valueOf(r.getString("status")), r.getLong("version"));
        }
    }
    public void saveVersion(Connection c, Request old, Status next, String title, String description) throws SQLException {
        int updated = Sql.update(c, "UPDATE requests SET status=?,title=?,description=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",
                next.name(), title, description, old.id(), old.version());
        if (updated != 1) throw new ServiceException(ServiceException.Code.CONFLICT, "Request changed. Reload it before retrying.");
    }
}
