package gitlite.db;

import gitlite.model.Branch;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * BranchDAO — all SQL operations related to branches.
 *
 * Branches are the simplest table in the system.
 * Each row is just: name, current HEAD commit ID, parent branch name.
 * The entire "branch" concept in Git is just this pointer — nothing more.
 *
 * DBMS concepts demonstrated:
 *   - UPDATE specific column (advancing HEAD pointer after a commit)
 *   - INSERT OR REPLACE (upsert — create or overwrite)
 *   - Simple SELECT with WHERE clause
 */
public class BranchDAO {

    private final Connection connection;

    public BranchDAO(Connection connection) {
        this.connection = connection;
    }

    // ======================== WRITE ========================

    // Save a new branch or overwrite an existing one
    public void save(Branch branch) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO branches (name, head_commit_id, parent_branch)
            VALUES (?, ?, ?)
        """;

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, branch.getName());
        pst.setString(2, branch.getHeadCommitId()); // null if no commits yet
        pst.setString(3, branch.getParentBranch()); // null for "main"
        pst.executeUpdate();
        pst.close();
    }

    // Advance the HEAD pointer of a branch after a new commit is made
    // This is the most frequent branch operation — called after every commit
    public void updateHead(String branchName, String newCommitId) throws SQLException {
        String sql = "UPDATE branches SET head_commit_id = ? WHERE name = ?";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, newCommitId);
        pst.setString(2, branchName);
        pst.executeUpdate();
        pst.close();
    }

    // ======================== READ ========================

    // Find a single branch by name
    public Branch findByName(String name) throws SQLException {
        String sql = "SELECT * FROM branches WHERE name = ?";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, name);
        ResultSet rs = pst.executeQuery();

        if (rs.next()) {
            Branch branch = new Branch(
                    rs.getString("name"),
                    rs.getString("head_commit_id"),
                    rs.getString("parent_branch")
            );
            rs.close();
            pst.close();
            return branch;
        }
        rs.close();
        pst.close();
        return null; // branch not found
    }

    // Load all branches — used on startup to rebuild the in-memory branch map
    public List<Branch> findAll() throws SQLException {
        String sql = "SELECT * FROM branches ORDER BY name";
        ResultSet rs = connection.createStatement().executeQuery(sql);

        List<Branch> branches = new ArrayList<>();
        while (rs.next()) {
            branches.add(new Branch(
                    rs.getString("name"),
                    rs.getString("head_commit_id"),
                    rs.getString("parent_branch")
            ));
        }
        rs.close();
        return branches;
    }

    // Check if a branch with this name already exists
    public boolean exists(String name) throws SQLException {
        String sql = "SELECT 1 FROM branches WHERE name = ?";

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, name);
        ResultSet rs = pst.executeQuery();
        boolean found = rs.next();
        rs.close();
        pst.close();
        return found;
    }
}