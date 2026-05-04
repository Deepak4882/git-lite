package gitlite.model;

/**
 * Branch — a named pointer to a commit.
 * This is the correct mental model for what a branch actually is in Git.
 *
 * A branch does NOT store history. It stores exactly one thing:
 * the ID of the most recent commit on that branch (the HEAD commit).
 * History is reconstructed by following parent pointers in the commit chain.
 *
 * parentBranch = which branch was active when this branch was created.
 * Used to display the branch tree and for merge base calculation.
 */
public class Branch {

    private String name;           // branch name e.g. "main", "feature/login"
    private String headCommitId;   // ID of the latest commit on this branch (null if no commits yet)
    private String parentBranch;   // which branch this was created from (null for "main")

    public Branch(String name, String headCommitId, String parentBranch) {
        this.name = name;
        this.headCommitId = headCommitId;
        this.parentBranch = parentBranch;
    }

    public String getName()          { return name; }
    public String getHeadCommitId()  { return headCommitId; }
    public String getParentBranch()  { return parentBranch; }

    // Called after every new commit on this branch — advances the pointer forward
    public void updateHead(String newCommitId) {
        this.headCommitId = newCommitId;
    }

    public boolean hasCommits() {
        return headCommitId != null;
    }

    @Override
    public String toString() {
        String head = headCommitId != null
                ? headCommitId.substring(0, Math.min(7, headCommitId.length()))
                : "no commits";
        return name + " -> [" + head + "]";
    }
}