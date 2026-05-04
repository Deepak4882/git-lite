package gitlite.datastructures;

/**
 * Custom Singly Linked List — hand-built, no java.util.LinkedList used.
 *
 * Why custom here?
 * The commit history chain IS a linked list by nature — each commit
 * node holds a pointer to its parent. Building this ourselves means
 * we can expose the raw Node structure, which the Graph uses directly
 * to traverse the commit chain without copying data into another collection.
 *
 * Used in Git-Lite for:
 *   1. CommitService  — maintain the ordered commit chain per branch
 *   2. Graph          — adjacency list entries (list of parent commit IDs)
 *   3. Repository     — git log traversal from HEAD to root
 *
 * Head = most recently added element (most recent commit).
 * Tail = oldest element (root commit).
 * Traversal: HEAD -> parent -> parent -> ... -> root (null)
 */
public class LinkedList<T> {

    // Node is public so Graph can traverse without copying
    public static class Node<T> {
        public T data;
        public Node<T> next;

        public Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node<T> head;  // most recently added element
    private int size;

    public LinkedList() {
        this.head = null;
        this.size = 0;
    }

    // Add to front — O(1)
    // For commits: most recent commit becomes the new head
    public void addFirst(T data) {
        Node<T> newNode = new Node<>(data);
        newNode.next = head;
        head = newNode;
        size++;
    }

    // Add to end — O(n)
    // Used when rebuilding history from DB (preserves chronological order)
    public void addLast(T data) {
        Node<T> newNode = new Node<>(data);
        if (head == null) {
            head = newNode;
        } else {
            Node<T> current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
        size++;
    }

    // Remove from front — O(1)
    public T removeFirst() {
        if (isEmpty()) {
            throw new RuntimeException("Cannot remove from an empty list");
        }
        T data = head.data;
        head = head.next;
        size--;
        return data;
    }

    // Peek at front without removing — O(1)
    public T peekFirst() {
        if (isEmpty()) return null;
        return head.data;
    }

    // Check if a value exists — O(n)
    public boolean contains(T data) {
        Node<T> current = head;
        while (current != null) {
            if (current.data.equals(data)) return true;
            current = current.next;
        }
        return false;
    }

    // Expose raw head node — used by Graph for traversal
    public Node<T> getHead() { return head; }

    public int size()        { return size; }
    public boolean isEmpty() { return size == 0; }

    public void clear() {
        head = null;
        size = 0;
    }

    @Override
    public String toString() {
        if (isEmpty()) return "[ empty ]";
        StringBuilder sb = new StringBuilder();
        Node<T> current = head;
        while (current != null) {
            sb.append(current.data);
            if (current.next != null) sb.append(" -> ");
            current = current.next;
        }
        return sb.toString();
    }
}