
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import student.TestableRandom;

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
    private SkipNode head; // First element (Sentinel Node)
    private int size; // number of entries in the Skip List
    private int level;
    private Random rng;

    /**
     * Initializes the fields head, size and level
     */
    public SkipList() {
        head = new SkipNode(null, 0);
        size = 0;
        this.rng = new TestableRandom();
        level = -1;
    }


    /**
     * returns a random level (using geometric distribution), minimum of 1
     * 
     * @return random level int from geometric distribution
     */
    public int randomLevel() {
        int lev;
        for (lev = 1; rng.nextBoolean(); lev++) {

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
     * Removes the KVPair that is passed in as a parameter and returns true if
     * the pair was valid and false if not.
     * 
     * @param key
     *            the Key of the pair to be removed
     * @return returns the removed pair if the pair was valid and null if not
     */

    @SuppressWarnings("unchecked")
    public KVPair<K, V> remove(K key) {
        SkipNode[] update = (SkipList<K, V>.SkipNode[])Array.newInstance(
            SkipNode.class, level + 1);
        SkipNode x = head;
        for (int i = level; i >= 0; i--) {
            while ((x.forward[i] != null) && (x.forward[i].element().getKey()
                .compareTo(key) < 0)) {

                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if ((x != null) && (x.element().getKey().compareTo(key) == 0)) {
            KVPair<K, V> value = x.element();
            for (int i = 0; i <= level; i++) {
                if (update[i].forward[i] != x) {
                    break;
                }
                update[i].forward[i] = x.forward[i];
            }
            while (level > 0 && head.forward[level] == null) {
                level--;
            }
            size--;
            return value;
        }
        return null;

    }


    /**
     * Removes a KVPair with the specified value.
     * 
     * @param val
     *            the value of the KVPair to be removed
     * @return returns true if the removal was successful
     */
    public KVPair<K, V> removeByValue(V val) {
        SkipNode x = head;

        while (x.forward[0] != null) {
            if (x.forward[0].element().getValue().equals(val)) {
                KVPair<K, V> removed = remove(x.forward[0].element().getKey());

                return removed;

            }
            else {
                x = x.forward[0];
            }

        }
        return null;

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
            String node = "";
            if (value != null) {
                node += "Node with depth " + depth + ", Value " + value;
            }
            else {
                node += "Node with depth " + depth + ", Value " + value;

            }
            System.out.println(node);
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
