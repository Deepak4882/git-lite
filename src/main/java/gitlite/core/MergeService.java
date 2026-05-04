package gitlite.core;

import gitlite.datastructures.Graph;
import gitlite.db.BranchDAO;
import gitlite.db.CommitDAO;
import gitlite.db.FileDAO;
import gitlite.model.Blob;
import gitlite.model.Branch;
import gitlite.model.Commit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


/**
 * MergeService — handles merging one branch into the current branch.
 *
 * Three possible merge outcomes:
 *
 *   1. Already up to date
 *      The target branch HEAD is an ancestor of the current branch HEAD.
 *      Nothing to do — current branch already contains all target changes.
 *
 *   2. Fast-forward merge
 *      The current branch HEAD is an ancestor of the target branch HEAD.
 *      No divergence — just move the current branch pointer forward.
 *      No merge commit needed.
 *
 *   3. Three-way merge
 *      Both branches have diverged from a common ancestor (LCA).
 *      Files are merged by comparing both branches against the LCA.
 *      Conflicts are flagged if both branches modified the same file differently.
 *      A merge commit is created with two parents.
 *
 * This is where the Graph's LCA algorithm (findLCA) is used.
 * In an interview: "Merge needs to find where the two branches diverged.
 * I implemented BFS-based LCA on my custom commit DAG to find that point,
 * then did a three-way comparison of files from both branches against it."
 */

public class MergeService {

    private final CommitDAO commitDAO;
    private final BranchDAO branchDAO;
    private final FileDAO fileDAO;
    private final Graph commitGraph;

    public MergeService(CommitDAO commitDAO, BranchDAO branchDAO,
                        FileDAO fileDAO, Graph commitGraph) {
        this.commitDAO = commitDAO;
        this.branchDAO = branchDAO;
        this.fileDAO = fileDAO;
        this.commitGraph = commitGraph;
    }

    public Commit merge(Branch currentBranch, Branch sourceBranch,
                        String author) throws SQLException {

        String currentHead = currentBranch.getHeadCommitId();
        String sourceHead = sourceBranch.getHeadCommitId();

        if (currentHead == null || sourceHead == null) {
            System.err.println("Error: one of the branches has no commits.");
            return null;
        }

        // Case 1: Already up to date
        if (commitGraph.isAncestor(sourceHead, currentHead)) {
            System.out.println("Already up to date.");
            return null;
        }

        // Case 2: Fast-forward
        if (commitGraph.isAncestor(currentHead, sourceHead)) {
            fastForward(currentBranch, sourceHead);
            return null;
        }

        // Case 3: Three-way merge
        String lcaId = commitGraph.findLCA(currentHead, sourceHead);
        if (lcaId == null) {
            System.err.println("No common ancestor.");
            return null;
        }

        return threeWayMerge(currentBranch, sourceBranch,
                currentHead, sourceHead, lcaId, author);
    }

    private void fastForward(Branch currentBranch, String newHead) throws SQLException {
        List<Blob> blobs = fileDAO.findByCommit(newHead);

        for (Blob blob : blobs) {
            try {
                Files.writeString(Path.of(blob.getFilename()), blob.getContent());
            } catch (IOException e) {
                System.err.println("Write error: " + blob.getFilename());
            }
        }

        branchDAO.updateHead(currentBranch.getName(), newHead);
        currentBranch.updateHead(newHead);

        System.out.println("Fast-forward complete.");
    }

    private Commit threeWayMerge(Branch currentBranch, Branch sourceBranch,
                                 String currentHead, String sourceHead,
                                 String lcaId, String author) throws SQLException {

        System.out.println("Merge base (LCA): ["
                + lcaId.substring(0, Math.min(7, lcaId.length())) + "]");
        System.out.println("Performing three-way merge...");

        Map<String, String> lcaSnapshot     = commitDAO.findById(lcaId).getSnapshot();
        Map<String, String> currentSnapshot = commitDAO.findById(currentHead).getSnapshot();
        Map<String, String> sourceSnapshot  = commitDAO.findById(sourceHead).getSnapshot();

        Set<String> allFiles = new HashSet<>();
        allFiles.addAll(lcaSnapshot.keySet());
        allFiles.addAll(currentSnapshot.keySet());
        allFiles.addAll(sourceSnapshot.keySet());

        Map<String, String> mergedSnapshot = new HashMap<>();
        boolean hasConflicts = false;

        for (String filename : allFiles) {
            String lcaHash     = lcaSnapshot.get(filename);
            String currentHash = currentSnapshot.get(filename);
            String sourceHash  = sourceSnapshot.get(filename);

            String resolved = resolveFile(filename, lcaHash, currentHash, sourceHash);

            if (resolved == null && !Objects.equals(currentHash, sourceHash)) {
                // Genuine conflict — both modified differently
                hasConflicts = true;
                writeConflictFile(filename, currentHash, sourceHash);
            } else if (resolved != null) {
                mergedSnapshot.put(filename, resolved);
            }
        }

        if (hasConflicts) {
            System.err.println("\nMerge has conflicts. Fix conflict files and commit manually.");
            return null;
        }

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message  = "Merge '" + sourceBranch.getName()
                + "' into '" + currentBranch.getName() + "'";
        String commitId = ContentHasher.hashCommit(
                message, author, timestamp, currentHead + sourceHead);

        Commit mergeCommit = new Commit(
                commitId, message, author, timestamp,
                currentHead, sourceHead,
                currentBranch.getName(), mergedSnapshot);

        commitDAO.save(mergeCommit);
        branchDAO.updateHead(currentBranch.getName(), commitId);
        currentBranch.updateHead(commitId);

        commitGraph.addCommit(mergeCommit);
        commitGraph.addParentEdge(commitId, currentHead);
        commitGraph.addParentEdge(commitId, sourceHead);

        restoreWorkingDirectory(mergedSnapshot);

        System.out.println("\nMerge complete. Merge commit: ["
                + commitId.substring(0, 7) + "]");
        System.out.println(message);

        return mergeCommit;
    }

    private String resolveFile(String filename,
                               String lcaHash,
                               String currentHash,
                               String sourceHash) {

        boolean currentChanged = !equals(currentHash, lcaHash);
        boolean sourceChanged  = !equals(sourceHash, lcaHash);

        if (!currentChanged && !sourceChanged) {
            System.out.println("  Unchanged: " + filename);
            return currentHash != null ? currentHash : lcaHash;
        }

        if (currentChanged && !sourceChanged) {
            if (currentHash == null) {
                System.out.println("  Deleted (current): " + filename);
                return null;
            }
            System.out.println("  Merged (current): " + filename);
            return currentHash;
        }

        if (!currentChanged && sourceChanged) {
            if (sourceHash == null) {
                System.out.println("  Deleted (source): " + filename);
                return null;
            }
            System.out.println("  Merged (source):  " + filename);
            return sourceHash;
        }

        if (equals(currentHash, sourceHash)) {
            System.out.println("  Merged (identical change): " + filename);
            return currentHash;
        }

        System.err.println("  CONFLICT: " + filename
                + " modified differently in both branches.");
        return null;
    }

    private boolean equals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private void restoreWorkingDirectory(Map<String, String> snapshot) {
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            try {
                Blob blob = fileDAO.findByHash(entry.getValue());
                Files.writeString(Path.of(entry.getKey()), blob.getContent());
            } catch (Exception e) {
                System.err.println("Restore failed: " + entry.getKey());
            }
        }
    }

    private void writeConflictFile(String filename, String currentHash, String sourceHash) {
        try {
            String currentContent = getContent(currentHash);
            String sourceContent = getContent(sourceHash);

            String merged =
                    "<<<<<<< CURRENT\n" +
                            currentContent +
                            "\n=======\n" +
                            sourceContent +
                            "\n>>>>>>> SOURCE\n";

            Files.writeString(Path.of(filename), merged);

            System.err.println("Conflict written: " + filename);

        } catch (Exception e) {
            System.err.println("Conflict write failed: " + filename);
        }
    }

    private String getContent(String hash) throws SQLException {
        if (hash == null) return "";
        Blob blob = fileDAO.findByHash(hash);
        return blob != null ? blob.getContent() : "";
    }
}