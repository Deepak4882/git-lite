package gitlite.core;

import gitlite.datastructures.Graph;
import gitlite.datastructures.LinkedList;
import gitlite.db.CommitDAO;
import gitlite.db.BranchDAO;
import gitlite.model.Commit;
import gitlite.model.Branch;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * CommitService — responsible for creating commits and maintaining
 * the commit history chain and graph.
 *
 * Single Responsibility: this class does one thing — build and persist commits.
 * It does not handle staging (StagingArea), branching (Repository),
 * or diffing (DiffService). Each concern is isolated.
 *
 * Data structures updated on every commit:
 *   commitHistory (LinkedList) — new commit added at head
 *   commitGraph   (Graph)      — new node + parent edge added
 *
 * DB tables updated on every commit:
 *   commits table       — new row
 *   commit_files table  — one row per staged file
 *   branches table      — head_commit_id advanced
 */
public class CommitService {

    private final CommitDAO commitDAO;
    private final BranchDAO branchDAO;

    // In-memory structures — owned by Repository, passed in by reference
    private final LinkedList<Commit> commitHistory;
    private final Graph commitGraph;

    public CommitService(CommitDAO commitDAO, BranchDAO branchDAO,
                         LinkedList<Commit> commitHistory, Graph commitGraph) {
        this.commitDAO     = commitDAO;
        this.branchDAO     = branchDAO;
        this.commitHistory = commitHistory;
        this.commitGraph   = commitGraph;
    }

    /**
     * Create a new commit from the current staging snapshot.
     *
     * Steps:
     *   1. Generate a unique commit ID by hashing metadata
     *   2. Build the Commit object with the staged file snapshot
     *   3. Save to DB (commits + commit_files tables)
     *   4. Update the branch HEAD pointer in DB
     *   5. Add to in-memory LinkedList (history) and Graph (DAG)
     *
     * @param message     the commit message
     * @param author      who is making the commit
     * @param branchName  current active branch
     * @param parentId    HEAD commit ID before this commit (null for root)
     * @param snapshot    the staged files (filename -> hash)
     * @return            the newly created Commit object
     */
    public Commit createCommit(String message, String author,
                               String branchName, String parentId,
                               Map<String, String> snapshot) throws SQLException {

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Generate unique commit ID from metadata
        String commitId = ContentHasher.hashCommit(message, author, timestamp, parentId);

        // Build the commit object
        Commit commit = new Commit(commitId, message, author,
                timestamp, parentId, null,
                branchName, snapshot);

        // Persist to DB
        commitDAO.save(commit);

        // Advance the branch HEAD pointer in DB
        branchDAO.updateHead(branchName, commitId);

        // Update in-memory LinkedList — most recent commit at head
        commitHistory.addFirst(commit);

        // Update in-memory Graph — add node and parent edge
        commitGraph.addCommit(commit);
        if (parentId != null) {
            commitGraph.addParentEdge(commitId, parentId);
        }

        return commit;
    }

    /**
     * Load all commits from DB and rebuild the in-memory LinkedList and Graph.
     * Called once on startup by Repository.
     *
     * Why rebuild from DB?
     * Our data structures live in memory — they are lost when the process exits.
     * The DB is the source of truth. On every startup we reconstruct
     * the in-memory state from the persisted data.
     * This is the standard pattern in real database-backed applications.
     */
    public void loadFromDB() throws SQLException {
        List<Commit> allCommits = commitDAO.findAll();

        for (Commit commit : allCommits) {
            // Add to graph
            commitGraph.addCommit(commit);

            // Add parent edge
            if (commit.getParent1() != null) {
                commitGraph.addParentEdge(commit.getId(), commit.getParent1());
            }
            // Add second parent edge for merge commits
            if (commit.getParent2() != null) {
                commitGraph.addParentEdge(commit.getId(), commit.getParent2());
            }
        }

        // LinkedList is branch-specific — loaded separately per branch in Repository
    }

    /**
     * Load commit history for a specific branch into the LinkedList.
     * Walks from HEAD backwards through parent pointers.
     *
     * Why walk parent pointers instead of just loading by branch name?
     * After a merge, the active branch contains commits from other branches too.
     * Walking parent pointers gives the true history regardless of branch labels.
     */
    public void loadBranchHistory(String headCommitId) throws SQLException {
        commitHistory.clear();
        String currentId = headCommitId;

        while (currentId != null) {
            Commit commit = commitDAO.findById(currentId);
            if (commit == null) break;

            commitHistory.addLast(commit);
            currentId = commit.getParent1(); // walk backwards
        }
    }

    // Print commit history — "git-lite log"
    public void printLog() {
        if (commitHistory.isEmpty()) {
            System.out.println("  No commits yet.");
            return;
        }

        LinkedList.Node<Commit> current = commitHistory.getHead();
        while (current != null) {
            System.out.println(current.data.toString());
            System.out.println("─".repeat(50));
            current = current.next;
        }
    }

    public LinkedList<Commit> getCommitHistory() {
        return commitHistory;
    }

    public Graph getCommitGraph() {
        return commitGraph;
    }
}