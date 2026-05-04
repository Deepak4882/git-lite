package gitlite.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DatabaseManager — owns the SQLite connection and schema initialization only.
 * Nothing else. All actual SQL queries live in the DAO classes.
 *
 * Responsibility separation:
 *   DatabaseManager  → connection lifecycle + table creation
 *   CommitDAO        → all commit-related SQL
 *   FileDAO          → all blob-related SQL
 *   BranchDAO        → all branch-related SQL
 *   StagingDAO       → all staging-related SQL
 *
 * The database file is stored inside .gitlite/ folder in the working directory.
 * This mirrors how real Git stores everything inside .git/
 *
 * DBMS concepts demonstrated here:
 *   - DDL (CREATE TABLE IF NOT EXISTS)
 *   - Primary keys and foreign key design
 *   - Junction table (commit_files) for many-to-many relationship
 */
public class DatabaseManager {

    private static final String DB_PATH = ".gitlite/repo.db";
    private Connection connection;

    // Open connection and initialize all tables
    public void initialize() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        createSchema();
    }

    private void createSchema() throws SQLException {
        Statement stmt = connection.createStatement();

        // commits table — one row per commit
        // parent1 is null only for the root commit
        // parent2 is non-null only for merge commits
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS commits (
                commit_id   TEXT PRIMARY KEY,
                message     TEXT NOT NULL,
                author      TEXT NOT NULL,
                timestamp   TEXT NOT NULL,
                parent1     TEXT,
                parent2     TEXT,
                branch_name TEXT NOT NULL
            )
        """);

        // files (blobs) — content-addressable storage
        // hash is the primary key: identical content = same row (deduplication)
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS files (
                hash     TEXT PRIMARY KEY,
                filename TEXT NOT NULL,
                content  TEXT NOT NULL
            )
        """);

        // commit_files — junction table linking commits to their file snapshots
        // One commit can reference many files; one file hash can appear in many commits
        // This is the many-to-many relationship between commits and blobs
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS commit_files (
                commit_id TEXT NOT NULL,
                filename  TEXT NOT NULL,
                hash      TEXT NOT NULL,
                PRIMARY KEY (commit_id, filename)
            )
        """);

        // branches — each branch is just a name + pointer to latest commit
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS branches (
                name           TEXT PRIMARY KEY,
                head_commit_id TEXT,
                parent_branch  TEXT
            )
        """);

        // staging — temporary area cleared after every commit
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS staging (
                filename TEXT PRIMARY KEY,
                hash     TEXT NOT NULL
            )
        """);

        // head — single row table tracking current branch (HEAD pointer)
        // Only ever has one row with id = 'HEAD'
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS head (
                id             TEXT PRIMARY KEY,
                current_branch TEXT NOT NULL
            )
        """);

        stmt.close();
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}