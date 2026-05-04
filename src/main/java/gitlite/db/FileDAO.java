package gitlite.db;

import gitlite.model.Blob;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FileDAO — all SQL operations related to file blobs.
 *
 * The files table is a content-addressable store.
 * Key insight: we never update a blob. Blobs are immutable.
 * If a file changes, a new blob with a new hash is created.
 * The old blob stays forever (needed for checkout of old commits).
 *
 * DBMS concepts demonstrated:
 *   - INSERT OR IGNORE (deduplication — don't overwrite identical content)
 *   - Content-addressable storage pattern
 *   - SELECT with JOIN (getting all files for a commit)
 */
public class FileDAO {

    private final Connection connection;

    public FileDAO(Connection connection) {
        this.connection = connection;
    }

    // ======================== WRITE ========================

    // Save a blob — INSERT OR IGNORE means if the hash already exists, skip it
    // This is how deduplication works: same content = same hash = one row
    public void save(Blob blob) throws SQLException {
        String sql = """
            INSERT OR IGNORE INTO files (hash, filename, content)
            VALUES (?, ?, ?)
        """;

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, blob.getHash());
        pst.setString(2, blob.getFilename());
        pst.setString(3, blob.getContent());
        pst.executeUpdate();
        pst.close();
    }

    // ======================== READ ========================

    // Find a blob by its hash — used during checkout to restore file content
    public Blob findByHash(String hash) throws SQLException {
        String sql = "SELECT * FROM files WHERE hash = ?";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, hash);
        ResultSet rs = pst.executeQuery();

        if (rs.next()) {
            Blob blob = new Blob(
                    rs.getString("hash"),
                    rs.getString("filename"),
                    rs.getString("content")
            );
            rs.close();
            pst.close();
            return blob;
        }
        rs.close();
        pst.close();
        return null; // blob not found
    }

    // Get all blobs associated with a specific commit
    // Uses a JOIN between files and commit_files tables
    // Used by DiffService and CheckoutService
    public List<Blob> findByCommit(String commitId) throws SQLException {
        String sql = """
            SELECT f.hash, f.filename, f.content
            FROM files f
            JOIN commit_files cf ON f.hash = cf.hash
            WHERE cf.commit_id = ?
            ORDER BY f.filename
        """;

        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, commitId);
        ResultSet rs = pst.executeQuery();

        List<Blob> blobs = new ArrayList<>();
        while (rs.next()) {
            blobs.add(new Blob(
                    rs.getString("hash"),
                    rs.getString("filename"),
                    rs.getString("content")
            ));
        }
        rs.close();
        pst.close();
        return blobs;
    }

    // Check if a blob already exists — used before staging to detect unchanged files
    public boolean exists(String hash) throws SQLException {
        String sql = "SELECT 1 FROM files WHERE hash = ?";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, hash);
        ResultSet rs = pst.executeQuery();
        boolean found = rs.next();
        rs.close();
        pst.close();
        return found;
    }
}