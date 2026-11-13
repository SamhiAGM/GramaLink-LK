package lk.gramalink.server.repository;

import java.sql.*;
import java.util.*;

/** All variable values are bound through PreparedStatement. SQL text is server-owned. */
public final class Sql {
    private Sql() { }
    public static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        try { for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]); return p; }
        catch (SQLException e) { p.close(); throw e; }
    }
    public static int update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = prepare(c, sql, args)) { return p.executeUpdate(); }
    }
    public static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = prepare(c, sql, args); ResultSet r = p.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (r.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                    Object value = r.getObject(i);
                    row.put(r.getMetaData().getColumnLabel(i), value instanceof Timestamp ? value.toString() : value);
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
