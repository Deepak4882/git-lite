package gitlite.cli;

import gitlite.core.Repository;

/**
 * CommandParser — reads CLI arguments and routes to the correct Repository method.
 *
 * This is the only class that touches String[] args.
 * Everything else works with typed objects and clean method calls.
 *
 * Supported commands:
 *   git-lite init
 *   git-lite add <file>
 *   git-lite commit -m "<message>"
 *   git-lite log
 *   git-lite status
 *   git-lite branch <name>
 *   git-lite branch --list
 *   git-lite checkout <branch>
 *   git-lite diff
 *   git-lite diff <commitA> <commitB>
 *   git-lite merge <branch>
 *   git-lite graph
 */
public class CommandParser {

    private final Repository repo;

    public CommandParser(Repository repo) {
        this.repo = repo;
    }

    public void parse(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];

        switch (command) {

            case "init" -> {
                repo.init();
            }

            case "add" -> {
                if (args.length < 2) {
                    System.err.println("Usage: git-lite add <filename>");
                    return;
                }
                repo.load();
                repo.add(args[1]);
            }

            case "commit" -> {
                if (args.length < 3 || !args[1].equals("-m")) {
                    System.err.println("Usage: git-lite commit -m \"<message>\"");
                    return;
                }
                // Join all remaining args in case message had spaces
                String message = String.join(" ",
                        java.util.Arrays.copyOfRange(args, 2, args.length));
                repo.load();
                repo.commit(message);
            }

            case "log" -> {
                repo.load();
                repo.log();
            }

            case "status" -> {
                repo.load();
                repo.status();
            }

            case "branch" -> {
                if (args.length < 2) {
                    System.err.println("Usage: git-lite branch <name>");
                    System.err.println("       git-lite branch --list");
                    return;
                }
                repo.load();
                if (args[1].equals("--list")) {
                    repo.listBranches();
                } else {
                    repo.createBranch(args[1]);
                }
            }

            case "checkout" -> {
                if (args.length < 2) {
                    System.err.println("Usage: git-lite checkout <branch>");
                    return;
                }
                repo.load();
                repo.checkout(args[1]);
            }

            case "diff" -> {
                repo.load();
                if (args.length == 3) {
                    // diff between two specific commits
                    repo.diff(args[1], args[2]);
                } else {
                    // diff between last two commits on current branch
                    repo.diffLatest();
                }
            }

            case "merge" -> {
                if (args.length < 2) {
                    System.err.println("Usage: git-lite merge <branch>");
                    return;
                }
                repo.load();
                repo.merge(args[1]);
            }

            case "graph" -> {
                repo.load();
                repo.printGraph();
            }

            default -> {
                System.err.println("Unknown command: '" + command + "'");
                printUsage();
            }
        }
    }

    private void printUsage() {
        System.out.println("""
            Git-Lite — Mini Version Control System
            ═══════════════════════════════════════
            Commands:
              init                       Initialize a new repository
              add <file>                 Stage a file for commit
              commit -m "<message>"      Create a commit
              log                        Show commit history
              status                     Show staged files and HEAD
              branch <name>              Create a new branch
              branch --list              List all branches
              checkout <branch>          Switch to a branch
              diff                       Diff last two commits
              diff <commitA> <commitB>   Diff two specific commits
              merge <branch>             Merge a branch into current
              graph                      Print the full commit graph
            """);
    }
}