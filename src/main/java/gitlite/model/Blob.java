package gitlite.model;

/**
 * Blob — represents a stored file snapshot.
 * "Blob" is the term real Git uses for file content objects.
 *
 * The hash is the primary key — if two files have identical content,
 * they share the same blob (deduplication). This is how Git saves space.
 *
 * filename is stored alongside content so we know what to restore
 * during checkout without needing to look it up separately.
 */
public class Blob {

    private String hash;      // MD5 fingerprint of (filename + content) — the unique key
    private String filename;  // original filename this blob was created from
    private String content;   // full text content of the file at time of staging

    public Blob(String hash, String filename, String content) {
        this.hash = hash;
        this.filename = filename;
        this.content = content;
    }

    public String getHash()     { return hash; }
    public String getFilename() { return filename; }
    public String getContent()  { return content; }

    // Short hash for display — same 8-char style used in git-lite output
    public String getShortHash() {
        return hash.length() >= 8 ? hash.substring(0, 8) : hash;
    }

    @Override
    public String toString() {
        return "Blob[" + getShortHash() + " -> " + filename + "]";
    }
}