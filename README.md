# Git-Lite 🔧

A mini version control system built from scratch in Java.  
Implements core Git concepts — commits, branches, merging, diffing — using custom data structures and SQLite persistence.

---

## Why I built this

Most developers use Git daily but very few understand what happens internally.  
This project is my attempt to understand Git from the inside out — by building it.

---

## Architecture
gitlite/
├── cli/          # Command parser — routes user input to services
├── model/        # Core domain objects: Commit, Branch, Blob
├── core/         # Business logic: staging, committing, merging, diffing
├── db/           # DAO layer — SQLite persistence via JDBC
└── datastructures/ # Custom Graph, Stack, LinkedList

---

## Data Structures

| Structure | Where used | Why this structure |
|---|---|---|
| **Custom Graph** (DAG) | Full commit history | No Java built-in DAG exists. Needed BFS-based LCA search for merge base calculation |
| **Custom Stack** | Diff engine + revert | LIFO buffer for line-by-line file comparison between commits |
| **Custom LinkedList** | Commit history chain | Each commit node points to its parent — natural linked structure |
| **Java HashMap** | Staging area, snapshots | O(1) lookup by filename — production-tested, no custom implementation needed |

---

## Database Schema (SQLite)

```sql
commits      — commit_id, message, author, timestamp, parent1, parent2, branch_name
files        — hash, filename, content  (content-addressable, deduplicated)
commit_files — commit_id, filename, hash  (junction table, many-to-many)
branches     — name, head_commit_id, parent_branch
staging      — filename, hash  (cleared after every commit)
head         — current_branch  (single row, persists active branch)
```

---

## Commands

```bash
git-lite init                      # Initialize a new repository
git-lite add <file>                # Stage a file
git-lite commit -m "<message>"     # Create a commit
git-lite log                       # Show commit history
git-lite status                    # Show staged files and HEAD
git-lite branch <name>             # Create a new branch
git-lite branch --list             # List all branches
git-lite checkout <branch>         # Switch branch (restores files on disk)
git-lite diff                      # Diff last two commits
git-lite diff <commitA> <commitB>  # Diff two specific commits
git-lite merge <branch>            # Merge a branch (fast-forward or three-way)
git-lite graph                     # Print the full commit DAG
```

---

## How merge works
main:           A --- B --- D

feature/logout:         C

When merging `feature/logout` into `main`:
1. `Graph.findLCA()` runs BFS from both branch HEADs to find commit `B` (common ancestor)
2. Three-way comparison: each file is compared against LCA, current branch, and source branch
3. If only one branch modified a file — take that version
4. If both modified it differently — flag as conflict
5. A merge commit with two parents (`D`) is created

---

## How diff works

The diff engine uses a custom **Stack** to compare two commit snapshots line by line:
1. Push all lines of the old file onto the Stack (reversed, so first line is on top)
2. Walk through new file lines, popping from Stack for comparison
3. Matching lines → unchanged, mismatches → show `-` old and `+` new
4. Remaining stack entries → deleted lines, remaining new lines → additions

---

## Setup

**Prerequisites:** JDK 21, Maven

```bash
git clone https://github.com/Deepak4882/gitlite.git
cd gitlite
mvn compile
```

Run commands via IntelliJ run configurations or build a JAR:
```bash
mvn package
java -jar target/git-lite-1.0-SNAPSHOT.jar init
```

---

## Tech stack

- **Language:** Java 21
- **Database:** SQLite via JDBC (xerial sqlite-jdbc 3.45.1.0)
- **Build:** Maven
- **IDE:** IntelliJ IDEA