package skipdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A skip list of (score, member) entries ordered by score, then member, where
 * every forward link also stores its <em>span</em>: how many level-0 nodes it
 * jumps over. Summing spans along a search path gives an element's rank, so
 * rank, select-by-rank and range queries are all expected O(log n). This is
 * the same design Redis uses for sorted sets (zskiplist).
 */
public final class RankedSkipList {
    static final int MAX_LEVEL = 32;
    private static final double P = 0.25;

    /** A node in the list. Ranks used by this class are 1-based. */
    public static final class Node {
        final String member;
        final double score;
        final Node[] forward;
        final int[] span;
        Node backward;

        Node(int level, double score, String member) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new int[level];
        }


        public String member() {
            return member;
        }


        public double score() {
            return score;
        }


        /** @return the next node in order, or null */
        public Node next() {
            return forward[0];
        }


        /** @return the previous node in order, or null */
        public Node prev() {
            return backward;
        }
    }

    private final Node header = new Node(MAX_LEVEL, 0, null);
    private final Random rng;
    private Node tail;
    private int level = 1;
    private int length = 0;

    public RankedSkipList() {
        this(new Random());
    }


    public RankedSkipList(Random rng) {
        this.rng = rng;
    }


    public int size() {
        return length;
    }


    int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && rng.nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }


    private static boolean before(Node n, double score, String member) {
        return n.score < score || (n.score == score && n.member.compareTo(
            member) < 0);
    }


    /**
     * Inserts an entry. The caller guarantees (score, member) is not present
     * (SortedSet enforces this via its member map).
     *
     * @return the new node
     */
    public Node insert(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && before(x.forward[i], score,
                member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x;
        }

        int lvl = randomLevel();
        if (lvl > level) {
            for (int i = level; i < lvl; i++) {
                rank[i] = 0;
                update[i] = header;
                header.span[i] = length;
            }
            level = lvl;
        }

        x = new Node(lvl, score, member);
        for (int i = 0; i < lvl; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            // the new node takes over the tail end of the predecessor's span
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = lvl; i < level; i++) {
            update[i].span[i]++; // links above the new node now skip one more
        }

        x.backward = (update[0] == header) ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        }
        else {
            tail = x;
        }
        length++;
        return x;
    }


    /**
     * Removes the entry with exactly this score and member.
     *
     * @return true if it was present
     */
    public boolean delete(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && before(x.forward[i], score,
                member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x == null || x.score != score || !x.member.equals(member)) {
            return false;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            }
            else {
                update[i].span[i]--;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        }
        else {
            tail = x.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
        return true;
    }


    /**
     * @return 1-based rank of (score, member), or 0 if absent
     */
    public int rank(double score, String member) {
        int rank = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && (x.forward[i].score < score
                || (x.forward[i].score == score && x.forward[i].member
                    .compareTo(member) <= 0))) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x != header && x.score == score && x.member.equals(member)) {
                return rank;
            }
        }
        return 0;
    }


    /**
     * @param rank
     *            1-based rank
     * @return the node at that rank, or null if out of range
     */
    public Node byRank(int rank) {
        if (rank < 1 || rank > length) {
            return null;
        }
        int traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= rank) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == rank) {
                return x;
            }
        }
        return null;
    }


    /**
     * @return the first node whose score is within the lower bound, or null
     */
    public Node firstAtLeast(double min, boolean exclusive) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && (exclusive ? x.forward[i].score <= min
                : x.forward[i].score < min)) {
                x = x.forward[i];
            }
        }
        return x.forward[0];
    }


    /**
     * @return the last node whose score is within the upper bound, or null
     */
    public Node lastAtMost(double max, boolean exclusive) {
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && (exclusive ? x.forward[i].score < max
                : x.forward[i].score <= max)) {
                x = x.forward[i];
            }
        }
        return x == header ? null : x;
    }


    public Node first() {
        return header.forward[0];
    }


    public Node last() {
        return tail;
    }


    /** @return all members in order (for debugging and tests) */
    public List<String> members() {
        List<String> out = new ArrayList<>(length);
        for (Node n = first(); n != null; n = n.next()) {
            out.add(n.member);
        }
        return out;
    }
}
