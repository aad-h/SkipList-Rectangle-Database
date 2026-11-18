
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * This class implements SkipList data structure and contains an inner SkipNode
 * class which the SkipList will make an array of to store data.
 * 
 * @author CS Staff
 * 
 * @version 2024-01-22
 * @param <K>
 *            Key
 * @param <V>
 *            Value
 */
public class SkipList<K extends Comparable<? super K>, V>
    implements Iterable<KVPair<K, V>> {
    // cap on node height; 2^32 entries is far beyond any realistic list
    private static final int MAX_LEVEL = 32;

    private SkipNode head; // First element (Sentinel Node)
    private int size; // number of entries in the Skip List
    private int level;
    private Random rng;

    /**
     * Initializes an empty SkipList with a default random source.
     */
    public SkipList() {
        this(new Random());
    }


    /**
     * Initializes an empty SkipList that draws node levels from the given
     * random source (pass a seeded or scripted Random for deterministic tests).
     *
     * @param rng
     *            source of coin flips for randomLevel()
     */
    public SkipList(Random rng) {
        head = new SkipNode(null, 0);
        size = 0;
        this.rng = rng;
        level = -1;
    }


    /**
     * returns a random level (using geometric distribution), minimum of 1
     * 
     * @return random level int from geometric distribution
     */
    public int randomLevel() {
        int lev = 1;
        while (lev < MAX_LEVEL && rng.nextBoolean()) {
            lev++;
        }
        return lev;
    }


    /**
     * Searches for the KVPair using the key which is a Comparable object.
     * 
     * @param key
     *            key to be searched for
     * @return ArrayList of KVPairs with the key
     */
    public ArrayList<KVPair<K, V>> search(K key) {
        SkipNode x = head;
        for (int i = head.level; i >= 0; i--) {
            while ((x.forward[i] != null) && (x.forward[i].element().getKey()
                .compareTo(key) < 0)) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        ArrayList<KVPair<K, V>> value = new ArrayList<>();
        while ((x != null) && (x.element().getKey().compareTo(key) == 0)) {
            value.add(x.element());
            x = x.forward[0];

        }

        return value;
    }


    /**
     * @return the size of the SkipList
     */
    public int size() {
        return size;
    }


    /**
     * Inserts the KVPair in the SkipList at its appropriate spot as designated
     * by its lexicoragraphical order.
     * 
     * @param it
     *            the KVPair to be inserted
     */
    @SuppressWarnings("unchecked")
    public void insert(KVPair<K, V> it) {
        int newLevel = randomLevel();
        if (newLevel > level) {
            adjustHead(newLevel);
            level = newLevel;
        }
        SkipNode[] update = (SkipList<K, V>.SkipNode[])Array.newInstance(
            SkipNode.class, newLevel + 1);

        SkipNode x = head;
        for (int i = newLevel; i >= 0; i--) {
            while ((x.forward[i] != null) && (x.forward[i].element().getKey()
                .compareTo(it.getKey()) < 0)) {
                x = x.forward[i];
            }
            update[i] = x;

        }
        x = x.forward[0];
        while (x != null && x.element().getKey().compareTo(it.getKey()) == 0) {
            update[0] = x;
            x = x.forward[0];
        }

        x = new SkipNode(it, newLevel);
        for (int i = 0; i <= newLevel; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
        }
        size++;
    }


    /**
     * Increases the number of levels in head so that no element has more
     * indices than the head.
     * 
     * @param newLevel
     *            the number of levels to be added to head
     */
    public void adjustHead(int newLevel) {
        SkipNode temp = head;
        head = new SkipNode(null, newLevel);
        for (int i = 0; i <= level; i++) {
            head.forward[i] = temp.forward[i];
        }
        level = newLevel;
    }


    /**
     * Removes the first KVPair with the given key.
     *
     * @param key
     *            the key of the pair to be removed
     * @return the removed pair, or null if no pair has that key
     */
    public KVPair<K, V> remove(K key) {
        SkipNode x = head;
        for (int i = level; i >= 0; i--) {
            while (x.forward[i] != null && x.forward[i].element().getKey()
                .compareTo(key) < 0) {
                x = x.forward[i];
            }
        }
        SkipNode target = x.forward[0];
        if (target == null || target.element().getKey().compareTo(key) != 0) {
            return null;
        }
        unlink(target);
        return target.element();
    }


    /**
     * Removes the first KVPair (in key order) whose value equals val. Unlike
     * removing by key, this removes that exact node, so it is correct even
     * when several pairs share the same key.
     *
     * @param val
     *            the value of the KVPair to be removed
     * @return the removed pair, or null if no pair has that value
     */
    public KVPair<K, V> removeByValue(V val) {
        SkipNode x = head.forward[0];
        while (x != null && !x.element().getValue().equals(val)) {
            x = x.forward[0];
        }
        if (x == null) {
            return null;
        }
        unlink(x);
        return x.element();
    }


    /**
     * Splices a specific node out of every level it appears on, then shrinks
     * the list's level if the top levels became empty.
     *
     * @param target
     *            a node currently in the list
     */
    private void unlink(SkipNode target) {
        K key = target.element().getKey();
        SkipNode x = head;
        for (int i = level; i >= 0; i--) {
            // advance past all keys strictly smaller than the target's key
            while (x.forward[i] != null && x.forward[i].element().getKey()
                .compareTo(key) < 0) {
                x = x.forward[i];
            }
            // among equal keys, walk to the node just before target
            SkipNode p = x;
            while (p.forward[i] != null && p.forward[i] != target
                && p.forward[i].element().getKey().compareTo(key) == 0) {
                p = p.forward[i];
            }
            if (p.forward[i] == target) {
                p.forward[i] = target.forward[i];
            }
        }
        while (level > 0 && head.forward[level] == null) {
            level--;
        }
        size--;
    }


    /**
     * Prints out the SkipList in a human readable format to the console.
     */
    public void dump() {
        System.out.println("SkipList dump:");
        SkipNode current = head;
        while (current != null) {
            int depth = current.forward.length;
            KVPair<K, V> value = current.element();
            System.out.println("Node with depth " + depth + ", Value "
                + value);
            current = current.forward[0];
        }
        System.out.println("SkipList size is: " + size);

    }

    /**
     * This class implements a SkipNode for the SkipList data structure.
     * 
     * @author CS Staff
     * 
     * @version 2016-01-30
     */
    private class SkipNode {

        // the KVPair to hold
        private KVPair<K, V> pair;
        // An array of pointers to subsequent nodes
        private SkipNode[] forward;
        // the level of the node
        private int level;

        /**
         * Initializes the fields with the required KVPair and the number of
         * levels from the random level method in the SkipList.
         * 
         * @param tempPair
         *            the KVPair to be inserted
         * @param level
         *            the number of levels that the SkipNode should have
         */
        @SuppressWarnings("unchecked")
        public SkipNode(KVPair<K, V> tempPair, int level) {
            pair = tempPair;
            forward = (SkipNode[])Array.newInstance(SkipNode.class, level + 1);
            this.level = level;
        }


        /**
         * Returns the KVPair stored in the SkipList.
         * 
         * @return the KVPair
         */
        public KVPair<K, V> element() {
            return pair;
        }

    }


    private class SkipListIterator implements Iterator<KVPair<K, V>> {
        private SkipNode current;

        public SkipListIterator() {
            current = head;
        }


        @Override
        public boolean hasNext() {
            return current.forward[0] != null;
        }


        @Override
        public KVPair<K, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            KVPair<K, V> elem = current.forward[0].element();
            current = current.forward[0];
            return elem;
        }

    }

    @Override
    public Iterator<KVPair<K, V>> iterator() {
        return new SkipListIterator();
    }

}
