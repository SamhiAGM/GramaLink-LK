package lk.gramalink.server.repository;

import lk.gramalink.server.backend.ServiceException;
import java.sql.*;

/** A fresh connection for every transaction; no mutable connection is shared between clients. */
public final class Database {
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException; }
    private final String url, username, password;
    public Database(String url, String username, String password) {
        this.url = url; this.username = username; this.password = password;
    }
    public static Database fromEnvironment() {
        String password = System.getenv("GRAMALINK_DB_PASSWORD");
        if (password == null) throw new IllegalStateException("Set GRAMALINK_DB_PASSWORD before starting the backend.");
        return new Database(System.getenv().getOrDefault("GRAMALINK_DB_URL", "jdbc:postgresql://localhost:5432/gramalink"),
                System.getenv().getOrDefault("GRAMALINK_DB_USER", "gramalink"), password);
    }
    public <T> T transaction(Work<T> work) {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        } catch (SQLException e) {
            System.err.println("Database operation failed; SQL state=" + e.getSQLState());
            if ("23505".equals(e.getSQLState())) throw new ServiceException(ServiceException.Code.CONFLICT, "A record with these identifiers already exists.");
            if ("23503".equals(e.getSQLState()) || "23514".equals(e.getSQLState())) throw ServiceException.invalid("The supplied values violate a data constraint.");
            throw new ServiceException(ServiceException.Code.INTERNAL_ERROR, "The database operation could not be completed.");
        }
    }
}
