package gitlite.datastructures;

/**
 * Custom Stack (LIFO) — hand-built, no java.util.Stack used.
 *
 * Why custom here?
 * Java's built-in Stack extends Vector which is thread-synchronized and
 * carries legacy baggage. More importantly, for an interview you want to
 * show you understand the underlying mechanism.
 *
 * Used in Git-Lite for:
 *   1. DiffService  — push lines of a file, compare against previous snapshot
 *   2. Repository   — revert stack, undo the most recent commit
 *
 * Why Stack fits diff?
 * When computing a diff, we process lines top-to-bottom and need to
 * track what changed. A stack lets us push each line and pop to compare —
 * LIFO matches the "most recent change first" nature of a diff output.
 */
public class Stack<T> {

    // Internal node — Stack is backed by a linked structure, not an array
    private static class Node<T> {
        T data;
        Node<T> next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node<T> top;  // points to the most recently pushed element
    private int size;

    public Stack() {
        this.top = null;
        this.size = 0;
    }

    // Push to top — O(1)
    public void push(T data) {
        Node<T> newNode = new Node<>(data);
        newNode.next = top;
        top = newNode;
        size++;
    }

    // Pop from top — O(1). Removes and returns the top element.
    public T pop() {
        if (isEmpty()) {
            throw new RuntimeException("Stack underflow: cannot pop from an empty stack");
        }
        T data = top.data;
        top = top.next;
        size--;
        return data;
    }

    // Peek at top without removing — O(1)
    public T peek() {
        if (isEmpty()) {
            throw new RuntimeException("Stack is empty: nothing to peek at");
        }
        return top.data;
    }

    public boolean isEmpty() { return size == 0; }
    public int size()        { return size; }

    public void clear() {
        top = null;
        size = 0;
    }

    @Override
    public String toString() {
        if (isEmpty()) return "Stack [ empty ]";
        StringBuilder sb = new StringBuilder("Stack (top -> bottom): ");
        Node<T> current = top;
        while (current != null) {
            sb.append(current.data);
            if (current.next != null) sb.append(" -> ");
            current = current.next;
        }
        return sb.toString();
    }
}