package gitlite.db;

import gitlite.model.Commit;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CommitDAO — all SQL operations related to commits.
 * DAO = Data Access Object. Standard enterprise Java pattern.
 *
 * Every method here is one clean database operation.
 * Repository and services never write SQL directly — they call these methods.
 *
 * DBMS concepts demonstrated:
 *   - Prepared statements (prevents SQL injection)
 *   - INSERT OR REPLACE (upsert pattern)
 *   - JOIN query (commits + commit_files together)
 *   - Parameterized queries
 */
public class CommitDAO {

    private final Connection connection;

    public CommitDAO(Connection connection) {
        this.connection = connection;
    }

    // ======================== WRITE ========================

    // Save a new commit to the database
    public void save(Commit commit) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO commits
                (commit_id, message, author, timestamp, parent1, parent2, branch_name)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, commit.getId());
        pst.setString(2, commit.getMessage());
        pst.setString(3, commit.getAuthor());
        pst.setString(4, commit.getTimestamp());
        pst.setString(5, commit.getParent1());   // null for root commit — JDBC handles this
        pst.setString(6, commit.getParent2());   // null for non-merge commits
        pst.setString(7, commit.getBranchName());
        pst.executeUpdate();
        pst.close();

        // Save the file snapshot (filename -> hash) for this commit
        saveSnapshot(commit.getId(), commit.getSnapshot());
    }

    // Save each file in the commit's snapshot to the commit_files junction table
    private void saveSnapshot(String commitId, Map<String, String> snapshot) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO commit_files (commit_id, filename, hash)
            VALUES (?, ?, ?)
        """;

        PreparedStatement pst = connection.prepareStatement(sql);
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            pst.setString(1, commitId);
            pst.setString(2, entry.getKey());   // filename
            pst.setString(3, entry.getValue()); // blob hash
            pst.addBatch(); // batch insert — more efficient than one-by-one
        }
        pst.executeBatch();
        pst.close();
    }

    // ======================== READ ========================

    // Load a single commit by its ID — used during checkout and diff
    public Commit findById(String commitId) throws SQLException {
        String sql = "SELECT * FROM commits WHERE commit_id = ?";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, commitId);
        ResultSet rs = pst.executeQuery();

        if (rs.next()) {
            Commit commit = buildCommit(rs);
            rs.close();
            pst.close();
            return commit;
        }
        rs.close();
        pst.close();
        return null; // commit not found
    }

    // Load all commits for a specific branch — used to rebuild commit history on startup
    public List<Commit> findByBranch(String branchName) throws SQLException {
        String sql = "SELECT * FROM commits WHERE branch_name = ? ORDER BY timestamp DESC";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, branchName);
        ResultSet rs = pst.executeQuery();

        List<Commit> commits = new ArrayList<>();
        while (rs.next()) {
            commits.add(buildCommit(rs));
        }
        rs.close();
        pst.close();
        return commits;
    }

    // Load every commit in the database — used to rebuild the full commit graph on startup
    public List<Commit> findAll() throws SQLException {
        String sql = "SELECT * FROM commits ORDER BY timestamp DESC";
        ResultSet rs = connection.createStatement().executeQuery(sql);

        List<Commit> commits = new ArrayList<>();
        while (rs.next()) {
            commits.add(buildCommit(rs));
        }
        rs.close();
        return commits;
    }

    // Get the file snapshot (filename -> hash) for a specific commit
    // Used by DiffService to compare two commits side by side
    public Map<String, String> getSnapshot(String commitId) throws SQLException {
        String sql = "SELECT filename, hash FROM commit_files WHERE commit_id = ?";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, commitId);
        ResultSet rs = pst.executeQuery();

        Map<String, String> snapshot = new HashMap<>();
        while (rs.next()) {
            snapshot.put(rs.getString("filename"), rs.getString("hash"));
        }
        rs.close();
        pst.close();
        return snapshot;
    }

    // ======================== HELPER ========================

    // Build a Commit object from a ResultSet row
    private Commit buildCommit(ResultSet rs) throws SQLException {
        String commitId  = rs.getString("commit_id");
        String message   = rs.getString("message");
        String author    = rs.getString("author");
        String timestamp = rs.getString("timestamp");
        String parent1   = rs.getString("parent1");
        String parent2   = rs.getString("parent2");
        String branch    = rs.getString("branch_name");

        // Load this commit's file snapshot from commit_files table
        Map<String, String> snapshot = getSnapshot(commitId);

        return new Commit(commitId, message, author, timestamp,
                parent1, parent2, branch, snapshot);
    }
}