package gitlite;

import gitlite.cli.CommandParser;
import gitlite.core.Repository;

/**
 * Main — entry point for Git-Lite.
 *
 * Usage:
 *   Run configuration in IntelliJ:
 *     Main class : gitlite.Main
 *     Program arguments : init
 *                         add Main.java
 *                         commit -m "first commit"
 *                         log
 *                         status
 *                         branch feature/login
 *                         checkout feature/login
 *                         diff
 *                         merge main
 *                         graph
 *
 * How to set program arguments in IntelliJ:
 *   Run -> Edit Configurations -> Program arguments field
 *   Type the command there e.g: commit -m "first commit"
 */
public class Main {

    public static void main(String[] args) {
        Repository repo   = new Repository();
        CommandParser parser = new CommandParser(repo);

        try {
            parser.parse(args);
        } finally {
            repo.close();
        }
    }
}