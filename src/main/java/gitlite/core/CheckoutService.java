package gitlite.core;

import gitlite.db.BranchDAO;
import gitlite.db.CommitDAO;
import gitlite.db.FileDAO;
import gitlite.model.Blob;
import gitlite.model.Branch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * CheckoutService — handles switching between branches.
 *
 * What happens during a checkout:
 *   1. Verify the target branch exists
 *   2. Verify no uncommitted staged files (would be lost on switch)
 *   3. Find the HEAD commit of the target branch
 *   4. Restore all files from that commit's snapshot to disk
 *   5. Update the HEAD pointer in DB to point to the new branch
 *   6. Update the in-memory currentBranch reference
 *
 * Step 4 is the critical one — this is how Git "time travel" works.
 * When you checkout an old branch, your actual files on disk change
 * to match exactly what they looked like at that branch's last commit.
 */
public class CheckoutService {

    private final BranchDAO branchDAO;
    private final CommitDAO commitDAO;
    private final FileDAO fileDAO;
    private final Connection connection;

    public CheckoutService(BranchDAO branchDAO, CommitDAO commitDAO,
                           FileDAO fileDAO, Connection connection) {
        this.branchDAO  = branchDAO;
        this.commitDAO  = commitDAO;
        this.fileDAO    = fileDAO;
        this.connection = connection;
    }

    /**
     * Switch to a different branch.
     *
     * @param targetBranchName  the branch to switch to
     * @param currentBranch     the currently active branch
     * @param stagingArea       current staging area (must be empty to proceed)
     * @return                  the Branch object of the newly checked-out branch,
     *                          or null if checkout failed
     */
    public Branch checkout(String targetBranchName,
                           Branch currentBranch,
                           StagingArea stagingArea) throws SQLException {

        // Cannot switch branches with uncommitted staged files
        // Those files would be lost or would contaminate the target branch
        if (!stagingArea.isEmpty()) {
            System.err.println("Error: you have staged changes not yet committed.");
            System.err.println("Commit or unstage them before switching branches.");
            return null;
        }

        // Target branch must exist
        Branch targetBranch = branchDAO.findByName(targetBranchName);
        if (targetBranch == null) {
            System.err.println("Error: branch '" + targetBranchName + "' does not exist.");
            System.err.println("Create it first with: git-lite branch " + targetBranchName);
            return null;
        }

        // Already on this branch — nothing to do
        if (targetBranchName.equals(currentBranch.getName())) {
            System.out.println("Already on branch '" + targetBranchName + "'.");
            return currentBranch;
        }

        // Restore files from the target branch's HEAD commit
        if (targetBranch.getHeadCommitId() != null) {
            restoreFilesFromCommit(targetBranch.getHeadCommitId());
        } else {
            System.out.println("Note: branch '" + targetBranchName
                    + "' has no commits yet.");
        }

        // Update HEAD table in DB to remember current branch across sessions
        updateHeadInDB(targetBranchName);

        System.out.println("Switched to branch '" + targetBranchName + "'.");
        if (targetBranch.getHeadCommitId() != null) {
            System.out.println("HEAD is now at ["
                    + targetBranch.getHeadCommitId()
                    .substring(0, Math.min(7, targetBranch.getHeadCommitId().length()))
                    + "]");
        }

        return targetBranch;
    }

    /**
     * Restore all files from a specific commit's snapshot to disk.
     * This physically writes file content back to the working directory.
     *
     * This is the core mechanism of checkout — it is what makes
     * your files actually change when you switch branches.
     */
    public void restoreFilesFromCommit(String commitId) throws SQLException {
        List<Blob> blobs = fileDAO.findByCommit(commitId);

        for (Blob blob : blobs) {
            try {
                Path filePath = Path.of(blob.getFilename());

                // Create parent directories if needed (e.g. src/Main.java)
                if (filePath.getParent() != null) {
                    Files.createDirectories(filePath.getParent());
                }

                // Write file content to disk
                Files.writeString(filePath, blob.getContent());
                System.out.println("  Restored: " + blob.getFilename());

            } catch (IOException e) {
                System.err.println("Error restoring " + blob.getFilename()
                        + ": " + e.getMessage());
            }
        }
    }

    // Update the HEAD table — persists which branch is currently active
    private void updateHeadInDB(String branchName) throws SQLException {
        String sql = "INSERT OR REPLACE INTO head (id, current_branch) VALUES ('HEAD', ?)";
        PreparedStatement pst = connection.prepareStatement(sql);
        pst.setString(1, branchName);
        pst.executeUpdate();
        pst.close();
    }

    // Read current branch from HEAD table — called on startup
    public String loadCurrentBranch() throws SQLException {
        String sql = "SELECT current_branch FROM head WHERE id = 'HEAD'";
        ResultSet rs = connection.createStatement().executeQuery(sql);
        if (rs.next()) {
            return rs.getString("current_branch");
        }
        rs.close();
        return "main"; // default for a fresh repository
    }
}