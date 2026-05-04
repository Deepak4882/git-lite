package gitlite.db;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

/**
 * StagingDAO — all SQL operations related to the staging area.
 *
 * The staging table is temporary by design.
 * It holds files that have been "git-lite add"ed but not yet committed.
 * After every successful commit, the entire staging table is cleared.
 *
 * This mirrors exactly how real Git's index (staging area) works.
 *
 * DBMS concepts demonstrated:
 *   - DELETE FROM (full table clear after commit)
 *   - INSERT OR REPLACE (re-staging a modified file replaces the old entry)
 *   - Loading entire table into a Map (in-memory representation)
 */
public class StagingDAO {

    private final Connection connection;

    public StagingDAO(Connection connection) {
        this.connection = connection;
    }

    // ======================== WRITE ========================

    // Stage a file — if the same filename is staged again, replace with new hash
    public void add(String filename, String hash) throws SQLException {
        String sql = "INSERT OR REPLACE INTO staging (filename, hash) VALUES (?, ?)";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, filename);
        pst.setString(2, hash);
        pst.executeUpdate();
        pst.close();
    }

    // Remove a single file from staging — for "git-lite unstage <file>"
    public void remove(String filename) throws SQLException {
        String sql = "DELETE FROM staging WHERE filename = ?";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, filename);
        pst.executeUpdate();
        pst.close();
    }

    // Clear the entire staging area — called after every successful commit
    public void clearAll() throws SQLException {
        connection.createStatement().execute("DELETE FROM staging");
    }

    // ======================== READ ========================

    // Load all staged files as a Map (filename -> hash)
    // Called on startup to restore the staging area into memory
    public Map<String, String> loadAll() throws SQLException {
        String sql = "SELECT filename, hash FROM staging";
        ResultSet rs = connection.createStatement().executeQuery(sql);

        Map<String, String> staged = new HashMap<>();
        while (rs.next()) {
            staged.put(rs.getString("filename"), rs.getString("hash"));
        }
        rs.close();
        return staged;
    }

    // Check if the staging area is empty
    public boolean isEmpty() throws SQLException {
        String sql = "SELECT COUNT(*) FROM staging";
        ResultSet rs = connection.createStatement().executeQuery(sql);
        int count = rs.next() ? rs.getInt(1) : 0;
        rs.close();
        return count == 0;
    }

    // Check if a specific file is already staged
    public boolean contains(String filename) throws SQLException {
        String sql = "SELECT 1 FROM staging WHERE filename = ?";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, filename);
        ResultSet rs = pst.executeQuery();
        boolean found = rs.next();
        rs.close();
        pst.close();
        return found;
    }
}