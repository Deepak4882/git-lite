package gitlite.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Commit — the core object of the entire system.
 * Every "git-lite commit" creates one of these.
 * parent1 = the commit this was built on top of (null for the very first commit)
 * parent2 = only set during a merge (a commit can have two parents)
 * snapshot = the full state of all tracked files at the time of this commit
 *            maps filename -> blob hash (not content, just the hash pointer)
 */
public class Commit {

    private String id;           // unique MD5 hash identifying this commit
    private String message;      // the commit message written by the user
    private String author;       // who made this commit
    private String timestamp;    // when it was made (formatted string)
    private String parent1;      // direct parent commit ID (null for root commit)
    private String parent2;      // second parent — only used in merge commits
    private String branchName;   // which branch this commit belongs to
    private Map<String, String> snapshot; // filename -> blobHash at commit time

    public Commit(String id, String message, String author,
                  String timestamp, String parent1, String parent2,
                  String branchName, Map<String, String> snapshot) {
        this.id = id;
        this.message = message;
        this.author = author;
        this.timestamp = timestamp;
        this.parent1 = parent1;
        this.parent2 = parent2;
        this.branchName = branchName;
        this.snapshot = snapshot != null ? snapshot : new HashMap<>();
    }

    // Convenience constructor for non-merge commits (no parent2)
    public Commit(String id, String message, String author,
                  String timestamp, String parent1,
                  String branchName, Map<String, String> snapshot) {
        this(id, message, author, timestamp, parent1, null, branchName, snapshot);
    }

    public String getId()          { return id; }
    public String getMessage()     { return message; }
    public String getAuthor()      { return author; }
    public String getTimestamp()   { return timestamp; }
    public String getParent1()     { return parent1; }
    public String getParent2()     { return parent2; }
    public String getBranchName()  { return branchName; }
    public Map<String, String> getSnapshot() { return snapshot; }

    public boolean isMergeCommit() {
        return parent2 != null;
    }

    // Short 7-char ID — same style as real Git
    public String getShortId() {
        return id.length() >= 7 ? id.substring(0, 7) : id;
    }

    @Override
    public String toString() {
        String mergeInfo = isMergeCommit() ? " [merge]" : "";
        return "commit " + getShortId() + mergeInfo + "\n"
                + "Author: " + author + "\n"
                + "Date:   " + timestamp + "\n"
                + "Branch: " + branchName + "\n"
                + "\n    " + message + "\n";
    }
}