package gitlite.core;

import gitlite.model.Blob;
import gitlite.db.FileDAO;
import gitlite.db.StagingDAO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * StagingArea — manages the in-memory staging state and syncs it to the DB.
 *
 * This is the "index" in real Git terminology.
 * When you run "git-lite add <file>":
 *   1. File is read from disk
 *   2. Content is hashed to produce a blob key
 *   3. Blob is saved to the files table (object store)
 *   4. filename -> hash mapping is saved to the staging table
 *   5. In-memory map is updated
 *
 * The in-memory map (stagedFiles) is the fast-access layer.
 * The DB is the persistent layer that survives process restarts.
 * Both are always kept in sync.
 *
 * Data structure used: Java's HashMap (justified — staging lookup
 * needs O(1) access by filename, and Java's HashMap is production-tested.
 * Building a custom one here adds no educational value over Graph/Stack.)
 */
public class StagingArea {

    // In-memory staging: filename -> blob hash
    private Map<String, String> stagedFiles;

    private final FileDAO fileDAO;
    private final StagingDAO stagingDAO;

    public StagingArea(FileDAO fileDAO, StagingDAO stagingDAO) {
        this.fileDAO    = fileDAO;
        this.stagingDAO = stagingDAO;
        this.stagedFiles = new HashMap<>();
    }

    // Load existing staged files from DB into memory — called on startup
    public void load() throws SQLException {
        stagedFiles = stagingDAO.loadAll();
        if (!stagedFiles.isEmpty()) {
            System.out.println("Restored " + stagedFiles.size() + " staged file(s) from previous session.");
        }
    }

    // Stage a file — core of "git-lite add <filename>"
    public void add(String filename) throws SQLException {
        // Read file content from disk
        String content;
        try {
            content = Files.readString(Path.of(filename));
        } catch (IOException e) {
            System.err.println("Error: file not found -> " + filename);
            return;
        }

        // Generate blob hash from filename + content
        String hash = ContentHasher.hashBlob(filename, content);

        // Check if file content has changed since last staging
        if (stagedFiles.containsKey(filename) && stagedFiles.get(filename).equals(hash)) {
            System.out.println("No changes detected in " + filename + " — already staged.");
            return;
        }

        // Save blob to object store (INSERT OR IGNORE handles duplicates)
        Blob blob = new Blob(hash, filename, content);
        fileDAO.save(blob);

        // Update staging area in DB and in memory
        stagingDAO.add(filename, hash);
        stagedFiles.put(filename, hash);

        System.out.println("Staged: " + filename + " [" + blob.getShortHash() + "]");
    }

    // Remove a file from staging — "git-lite unstage <filename>"
    public void remove(String filename) throws SQLException {
        if (!stagedFiles.containsKey(filename)) {
            System.out.println("File not staged: " + filename);
            return;
        }
        stagingDAO.remove(filename);
        stagedFiles.remove(filename);
        System.out.println("Unstaged: " + filename);
    }

    // Clear staging after a successful commit
    public void clear() throws SQLException {
        stagingDAO.clearAll();
        stagedFiles.clear();
    }

    // Return a snapshot copy of current staged files (filename -> hash)
    // CommitService uses this to build the commit's snapshot
    public Map<String, String> getSnapshot() {
        return new HashMap<>(stagedFiles); // defensive copy — caller cannot mutate our state
    }

    public boolean isEmpty() {
        return stagedFiles.isEmpty();
    }

    public int size() {
        return stagedFiles.size();
    }

    // Print current staging status — used by "git-lite status"
    public void printStatus() {
        if (stagedFiles.isEmpty()) {
            System.out.println("  Nothing staged for commit.");
            return;
        }
        System.out.println("  Changes staged for commit:");
        for (Map.Entry<String, String> entry : stagedFiles.entrySet()) {
            String shortHash = entry.getValue().substring(0, Math.min(8, entry.getValue().length()));
            System.out.println("      + " + entry.getKey() + "  [" + shortHash + "]");
        }
    }
}