package gitlite.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * ContentHasher — generates unique fingerprints for file content and commits.
 *
 * Why MD5?
 * Real Git uses SHA-1 (moving to SHA-256). We use MD5 because:
 *   1. It is available in every JVM with no external dependency
 *   2. It produces a 32-character hex string — short enough to display
 *   3. Collision resistance is sufficient for a local VCS with no security threat model
 *
 * In an interview: "I used MD5 for simplicity. In production I would
 * use SHA-256 which is what modern Git uses, because MD5 has known
 * collision vulnerabilities that could theoretically be exploited."
 * That answer shows you know the tradeoff — which is what matters.
 *
 * Three hash types:
 *   hashBlob    → unique key for a file (filename + content combined)
 *   hashCommit  → unique key for a commit (all metadata combined)
 *   hash        → raw MD5 of any string (used internally)
 */
public class ContentHasher {

    // Hash a file blob — combines filename + content so that the same
    // content in two differently named files produces different hashes
    public static String hashBlob(String filename, String content) {
        return hash(filename + "::" + content);
    }

    // Hash a commit — combines all commit metadata into one unique fingerprint
    // Even if two commits have the same message, different timestamps make them unique
    public static String hashCommit(String message, String author,
                                    String timestamp, String parent1) {
        String parent = parent1 != null ? parent1 : "root";
        return hash(message + author + timestamp + parent);
    }

    // Raw MD5 hash of any string — returns 32-character lowercase hex string
    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed available in all JVMs — this will never happen
            throw new RuntimeException("MD5 not available", e);
        }
    }

    // Convert raw bytes to readable hex string
    // e.g. byte 0xFF -> "ff", byte 0x0A -> "0a"
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}