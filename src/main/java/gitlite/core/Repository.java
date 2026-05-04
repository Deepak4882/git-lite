package gitlite.core;

import gitlite.datastructures.Graph;
import gitlite.datastructures.LinkedList;
import gitlite.db.*;
import gitlite.model.Branch;
import gitlite.model.Commit;
import gitlite.db.CommitDAO;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repository — the central coordinator of the entire system.
 *
 * This is the only class that all services know about.
 * It owns:
 *   - The database connection (via DatabaseManager)
 *   - All DAO instances (CommitDAO, FileDAO, BranchDAO, StagingDAO)
 *   - All in-memory data structures (Graph, LinkedList)
 *   - All service instances (CommitService, CheckoutService, etc.)
 *   - HEAD state (currentBranch, headCommitId)
 *
 * Every git-lite command (init, add, commit, log...) calls a method here.
 * Repository delegates to the appropriate service — it does not
 * implement business logic itself. It is the orchestrator.
 *
 * HEAD management:
 *   currentBranch  = the Branch object currently checked out
 *   The branch object itself holds headCommitId
 *   Both are persisted to the DB head table and restored on startup
 */
public class Repository {

    private static final String GITLITE_DIR = ".gitlite";

    // ── Infrastructure ───────────────────────────────────────────────────
    private final DatabaseManager dbManager;
    private Connection connection;

    // ── DAOs ─────────────────────────────────────────────────────────────
    private CommitDAO   commitDAO;
    private FileDAO     fileDAO;
    private BranchDAO   branchDAO;
    private StagingDAO  stagingDAO;

    // ── In-memory data structures ─────────────────────────────────────────
    private Graph            commitGraph;    // full commit DAG
    private LinkedList<Commit> commitHistory; // current branch history chain

    // ── Services ──────────────────────────────────────────────────────────
    private StagingArea     stagingArea;
    private CommitService   commitService;
    private CheckoutService checkoutService;
    private DiffService     diffService;
    private MergeService    mergeService;

    // ── HEAD state ────────────────────────────────────────────────────────
    private Branch currentBranch; // the currently active branch
    private Map<String, Branch> branches; // all known branches: name -> Branch

    // ── Identity ──────────────────────────────────────────────────────────
    private final String author;

    public Repository() {
        this.author     = System.getProperty("user.name", "anonymous");
        this.dbManager  = new DatabaseManager();
        this.branches   = new HashMap<>();
        this.commitGraph   = new Graph();
        this.commitHistory = new LinkedList<>();
    }

    // ════════════════════════════════════════════════════════════════════
    // INIT
    // ════════════════════════════════════════════════════════════════════

    /**
     * git-lite init — initialize a new repository in the current directory.
     * Creates .gitlite/ folder and sets up the database schema.
     * Also creates the default "main" branch.
     */
    public void init() {
        File gitDir = new File(GITLITE_DIR);
        if (gitDir.exists()) {
            System.out.println("Repository already initialized.");
            return;
        }

        gitDir.mkdir();

        try {
            dbManager.initialize();
            connection = dbManager.getConnection();
            initDAOs();

            // Create and persist the default main branch
            Branch mainBranch = new Branch("main", null, null);
            branchDAO.save(mainBranch);

            // Set HEAD to main
            updateHeadInDB("main");

            System.out.println("Initialized empty Git-Lite repository.");
            System.out.println("Default branch: main");
            System.out.println("Database: " + GITLITE_DIR + "/repo.db");

        } catch (SQLException e) {
            System.err.println("Init failed: " + e.getMessage());
        } finally {
            close();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // LOAD — called at the start of every command except init
    // ════════════════════════════════════════════════════════════════════

    /**
     * Load the full repository state from DB into memory.
     * Called at the start of every command (except init).
     *
     * Order matters:
     *   1. Open DB connection
     *   2. Initialize DAOs
     *   3. Initialize services
     *   4. Load branches into memory
     *   5. Restore HEAD (which branch is active)
     *   6. Load commit graph (all commits, all edges)
     *   7. Load commit history for active branch
     *   8. Load staging area
     */
    public void load() {
        File gitDir = new File(GITLITE_DIR);
        if (!gitDir.exists()) {
            System.err.println("Not a git-lite repository.");
            System.err.println("Run 'git-lite init' first.");
            System.exit(1);
        }

        try {
            dbManager.initialize();
            connection = dbManager.getConnection();
            initDAOs();
            initServices();

            // Load all branches from DB
            List<Branch> allBranches = branchDAO.findAll();
            for (Branch branch : allBranches) {
                branches.put(branch.getName(), branch);
            }

            // Restore HEAD — which branch was active
            String currentBranchName = checkoutService.loadCurrentBranch();
            currentBranch = branches.getOrDefault(currentBranchName,
                    new Branch("main", null, null));

            // Rebuild commit graph and history from DB
            commitService.loadFromDB();
            if (currentBranch.getHeadCommitId() != null) {
                commitService.loadBranchHistory(currentBranch.getHeadCommitId());
            }

            // Restore staging area
            stagingArea.load();

        } catch (SQLException e) {
            System.err.println("Failed to load repository: " + e.getMessage());
            System.exit(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // COMMANDS
    // ════════════════════════════════════════════════════════════════════

    // git-lite add <filename>
    public void add(String filename) {
        try {
            stagingArea.add(filename);
        } catch (SQLException e) {
            System.err.println("Add failed: " + e.getMessage());
        }
    }

    // git-lite commit -m "<message>"
    public void commit(String message) {
        if (stagingArea.isEmpty()) {
            System.out.println("Nothing to commit. Stage files using: git-lite add <file>");
            return;
        }

        try {
            // Build cumulative snapshot:
            // Start with the full snapshot from the previous commit,
            // then overlay the newly staged files on top.
            // This ensures every commit contains the COMPLETE file tree state,
            // not just the files changed in this commit.
            Map<String, String> cumulativeSnapshot = new HashMap<>();

            // Load previous commit's full snapshot as the base
            String parentId = currentBranch.getHeadCommitId();
            if (parentId != null) {
                Commit parentCommit = commitDAO.findById(parentId);
                if (parentCommit != null) {
                    cumulativeSnapshot.putAll(parentCommit.getSnapshot());
                }
            }

            // Overlay staged files on top of the previous snapshot
            // This handles: new files (added), modified files (replaced),
            // and leaves untouched files as they were
            cumulativeSnapshot.putAll(stagingArea.getSnapshot());

            Commit newCommit = commitService.createCommit(
                    message, author,
                    currentBranch.getName(),
                    parentId, cumulativeSnapshot);

            // Advance the in-memory branch pointer
            currentBranch.updateHead(newCommit.getId());
            branches.put(currentBranch.getName(), currentBranch);

            // Clear staging after successful commit
            stagingArea.clear();

            System.out.println("Committed: [" + newCommit.getShortId() + "] " + message);
            System.out.println("Branch: " + currentBranch.getName()
                    + " | Author: " + author);

        } catch (SQLException e) {
            System.err.println("Commit failed: " + e.getMessage());
        }
    }

    // git-lite log
    public void log() {
        System.out.println("Branch: " + currentBranch.getName());
        System.out.println("═".repeat(50));
        commitService.printLog();
    }

    // git-lite status
    public void status() {
        System.out.println("On branch: " + currentBranch.getName());
        String head = currentBranch.getHeadCommitId();
        System.out.println("HEAD: " + (head != null
                ? head.substring(0, Math.min(7, head.length()))
                : "no commits yet"));
        System.out.println();
        stagingArea.printStatus();
    }

    // git-lite branch <name>
    public void createBranch(String branchName) {
        try {
            if (branchDAO.exists(branchName)) {
                System.err.println("Branch '" + branchName + "' already exists.");
                return;
            }

            // New branch starts at the same commit as the current branch
            Branch newBranch = new Branch(
                    branchName,
                    currentBranch.getHeadCommitId(),
                    currentBranch.getName());

            branchDAO.save(newBranch);
            branches.put(branchName, newBranch);

            System.out.println("Created branch '" + branchName
                    + "' from '" + currentBranch.getName() + "'");
            if (currentBranch.getHeadCommitId() != null) {
                System.out.println("Starting at commit ["
                        + currentBranch.getHeadCommitId()
                        .substring(0, Math.min(7,
                                currentBranch.getHeadCommitId().length()))
                        + "]");
            }

        } catch (SQLException e) {
            System.err.println("Branch creation failed: " + e.getMessage());
        }
    }

    // git-lite branch --list
    public void listBranches() {
        System.out.println("Branches:");
        for (Branch branch : branches.values()) {
            String marker = branch.getName().equals(currentBranch.getName())
                    ? "* " : "  ";
            System.out.println(marker + branch);
        }
    }

    // git-lite checkout <branch>
    public void checkout(String branchName) {
        try {
            Branch target = branches.get(branchName);
            if (target == null) {
                System.err.println("Branch '" + branchName + "' does not exist.");
                return;
            }

            Branch result = checkoutService.checkout(
                    branchName, currentBranch, stagingArea);

            if (result != null) {
                currentBranch = result;
                // Reload commit history for the new branch
                commitHistory.clear();
                if (currentBranch.getHeadCommitId() != null) {
                    commitService.loadBranchHistory(
                            currentBranch.getHeadCommitId());
                }
            }

        } catch (SQLException e) {
            System.err.println("Checkout failed: " + e.getMessage());
        }
    }

    // git-lite diff <commitA> <commitB>
    public void diff(String commitIdA, String commitIdB) {
        try {
            diffService.diff(commitIdA, commitIdB);
        } catch (SQLException e) {
            System.err.println("Diff failed: " + e.getMessage());
        }
    }

    // git-lite diff (no args — diff last two commits)
    public void diffLatest() {
        try {
            System.out.println("DEBUG HEAD: " + currentBranch.getHeadCommitId());
            diffService.diffLastTwoCommits(currentBranch.getHeadCommitId());
        } catch (SQLException e) {
            System.err.println("Diff failed: " + e.getMessage());
        }
    }

    // git-lite merge <branchName>
    public void merge(String sourceBranchName) {
        try {
            Branch sourceBranch = branches.get(sourceBranchName);
            if (sourceBranch == null) {
                System.err.println("Branch '" + sourceBranchName + "' does not exist.");
                return;
            }

            if (sourceBranchName.equals(currentBranch.getName())) {
                System.err.println("Cannot merge a branch into itself.");
                return;
            }

            mergeService.merge(currentBranch, sourceBranch, author);

        } catch (SQLException e) {
            System.err.println("Merge failed: " + e.getMessage());
        }
    }

    // git-lite graph
    public void printGraph() {
        commitGraph.printGraph();
    }

    // ════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ════════════════════════════════════════════════════════════════════

    private void initDAOs() {
        commitDAO  = new CommitDAO(connection);
        fileDAO    = new FileDAO(connection);
        branchDAO  = new BranchDAO(connection);
        stagingDAO = new StagingDAO(connection);
    }

    private void initServices() {
        stagingArea     = new StagingArea(fileDAO, stagingDAO);
        commitService   = new CommitService(commitDAO, branchDAO,
                commitHistory, commitGraph);
        checkoutService = new CheckoutService(branchDAO, commitDAO,
                fileDAO, connection);
        diffService     = new DiffService(commitDAO, fileDAO);
        mergeService    = new MergeService(commitDAO, branchDAO, fileDAO,
                commitGraph);
    }

    private void updateHeadInDB(String branchName) throws SQLException {
        String sql = "INSERT OR REPLACE INTO head (id, current_branch) VALUES ('HEAD', ?)";
        var pst = connection.prepareStatement(sql);
        pst.setString(1, branchName);
        pst.executeUpdate();
        pst.close();
    }

    public void close() {
        try {
            dbManager.close();
        } catch (SQLException e) {
            System.err.println("Error closing DB: " + e.getMessage());
        }
    }
}