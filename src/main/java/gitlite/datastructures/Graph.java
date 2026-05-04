package gitlite.datastructures;

import gitlite.model.Commit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Custom Directed Graph — the commit DAG (Directed Acyclic Graph).
 *
 * Why custom here?
 * Java has no built-in DAG. More importantly, this graph needs
 * commit-aware operations: LCA search for merge base, ancestor
 * reachability check, and branch-filtered traversal. None of these
 * exist in any Java collection — we must build them ourselves.
 *
 * This is the strongest justification for a custom DS in the project.
 * In an interview: "Java has no DAG. I needed commit-aware traversal
 * and LCA search for merge — so I built one."
 *
 * Structure:
 *   adjacencyList : commitId -> list of parent commitIds (edges point TO ancestors)
 *   commitStore   : commitId -> Commit object (the node data)
 *
 * Note: We use Java's HashMap and HashSet here intentionally.
 * The custom work is the graph algorithms, not the storage containers.
 * Using built-ins for storage is the correct engineering decision.
 */
public class Graph {

    // commitId -> list of parent commitIds
    // Edge direction: child -> parent (following history backwards)
    private Map<String, LinkedList<String>> adjacencyList;

    // commitId -> full Commit object
    private Map<String, Commit> commitStore;

    public Graph() {
        this.adjacencyList = new HashMap<>();
        this.commitStore   = new HashMap<>();
    }

    // ======================== GRAPH CONSTRUCTION ========================

    // Add a commit node — O(1)
    public void addCommit(Commit commit) {
        commitStore.put(commit.getId(), commit);
        adjacencyList.putIfAbsent(commit.getId(), new LinkedList<>());
    }

    // Add a directed edge: child -> parent
    // Meaning: "this commit was built on top of parentId"
    public void addParentEdge(String childId, String parentId) {
        adjacencyList.putIfAbsent(childId, new LinkedList<>());
        adjacencyList.get(childId).addLast(parentId);
    }

    // ======================== LOOKUP ========================

    public Commit getCommit(String commitId) {
        return commitStore.get(commitId);
    }

    public boolean hasCommit(String commitId) {
        return commitStore.containsKey(commitId);
    }

    public LinkedList<String> getParents(String commitId) {
        return adjacencyList.get(commitId);
    }

    public int totalCommits() {
        return commitStore.size();
    }

    // ======================== GRAPH ALGORITHMS ========================

    /**
     * Find the Lowest Common Ancestor (LCA) of two commits.
     * This is the merge base — the point where two branches diverged.
     *
     * Algorithm:
     *   Step 1: BFS from commitA, collect ALL its ancestors into a Set.
     *   Step 2: BFS from commitB, return the FIRST node also in that Set.
     *
     * Why BFS and not DFS?
     * BFS finds the CLOSEST common ancestor first (level by level).
     * DFS might find a deeper ancestor before a shallower one.
     * Real Git uses a more sophisticated algorithm — this is a clean
     * and correct simplified version that works for our use case.
     *
     * Time complexity: O(V + E) where V = commits, E = parent edges
     */
    public String findLCA(String commitIdA, String commitIdB) {
        // Step 1: collect all ancestors of A (including A itself)
        Set<String> ancestorsOfA = new HashSet<>();
        java.util.Queue<String> queue = new java.util.LinkedList<>();
        queue.add(commitIdA);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            ancestorsOfA.add(current);
            LinkedList<String> parents = getParents(current);
            if (parents != null) {
                LinkedList.Node<String> node = parents.getHead();
                while (node != null) {
                    if (!ancestorsOfA.contains(node.data)) {
                        queue.add(node.data);
                    }
                    node = node.next;
                }
            }
        }

        // Step 2: BFS from B — first node in ancestorsOfA is the LCA
        Set<String> visitedFromB = new HashSet<>();
        queue.add(commitIdB);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (ancestorsOfA.contains(current)) {
                return current; // lowest common ancestor found
            }
            visitedFromB.add(current);
            LinkedList<String> parents = getParents(current);
            if (parents != null) {
                LinkedList.Node<String> node = parents.getHead();
                while (node != null) {
                    if (!visitedFromB.contains(node.data)) {
                        queue.add(node.data);
                    }
                    node = node.next;
                }
            }
        }

        return null; // no common ancestor — completely unrelated histories
    }

    /**
     * Check if ancestorId is reachable from descendantId.
     * Used to detect if a branch is strictly ahead of another (fast-forward check).
     *
     * Time complexity: O(V + E)
     */
    public boolean isAncestor(String ancestorId, String descendantId) {
        if (ancestorId.equals(descendantId)) return true;

        Set<String> visited = new HashSet<>();
        java.util.Queue<String> queue = new java.util.LinkedList<>();
        queue.add(descendantId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(ancestorId)) return true;
            visited.add(current);

            LinkedList<String> parents = getParents(current);
            if (parents != null) {
                LinkedList.Node<String> node = parents.getHead();
                while (node != null) {
                    if (!visited.contains(node.data)) {
                        queue.add(node.data);
                    }
                    node = node.next;
                }
            }
        }
        return false;
    }

    /**
     * Print the full commit graph — used by "git-lite log --graph"
     * Shows each commit and its parent pointers.
     */
    public void printGraph() {
        System.out.println("=== Commit Graph ===");
        for (Map.Entry<String, Commit> entry : commitStore.entrySet()) {
            Commit commit = entry.getValue();
            System.out.print(commit.getShortId() + " (" + commit.getBranchName() + ") -> parents: ");
            LinkedList<String> parents = adjacencyList.get(commit.getId());
            if (parents == null || parents.isEmpty()) {
                System.out.println("(root)");
            } else {
                System.out.println(parents);
            }
        }
    }
}