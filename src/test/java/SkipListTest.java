import java.util.ArrayList;
import java.util.Iterator;

import testsupport.TestCase;

/**
 * This class tests the methods of SkipList class
 * 
 * @author CS Staff
 * 
 * @version 2024-01-22
 */

public class SkipListTest extends TestCase {

    private SkipList<String, Rectangle> sl;

    /**
     * initializes a SkipList to be used for testing
     */
    public void setUp() {

        sl = new SkipList<String, Rectangle>();
    }


    // ----------------------------------------------------------
    /**
     * testing the search method
     */
    public void testSearch() {
        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        KVPair<String, Rectangle> y = new KVPair<>("1", new Rectangle(5, 6, 7,
            8));
        KVPair<String, Rectangle> z = new KVPair<>("2", new Rectangle(9, 10, 11,
            12));

        // add to the skip list
        sl.insert(x);
        sl.insert(y);
        sl.insert(z);

        ArrayList<KVPair<String, Rectangle>> trial = new ArrayList<>();
        trial.add(x);
        trial.add(y);
        assertEquals(trial, sl.search("1"));
        // duplicates

        ArrayList<KVPair<String, Rectangle>> trial1 = new ArrayList<>();
        trial1.add(z);
        assertEquals(trial1, sl.search("2"));

        ArrayList<KVPair<String, Rectangle>> trial2 = new ArrayList<>();
        assertEquals(trial2, sl.search("3"));

        sl = new SkipList<>();
        ArrayList<KVPair<String, Rectangle>> trial3 = new ArrayList<>();
        assertEquals(trial3, sl.search("1"));

        // make sure that null is empty array list
        assertEquals(new ArrayList<>(), sl.search(null));
    }


    // ----------------------------------------------------------
    /**
     * this test the Insert method
     */

    public void testInsert() {
        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        KVPair<String, Rectangle> y = new KVPair<>("1", new Rectangle(5, 6, 7,
            8));
        KVPair<String, Rectangle> z = new KVPair<>("4", new Rectangle(9, 10, 11,
            12));

        // insert first pair
        sl.insert(x);
        ArrayList<KVPair<String, Rectangle>> trial = new ArrayList<>();
        trial.add(x);
        assertEquals(trial, sl.search("1")); // make sure it insert
        assertEquals(1, sl.size()); // check size

        sl.insert(y);
        trial.add(y);
        assertEquals(trial, sl.search("1")); // see duplicates
        assertEquals(2, sl.size()); // check size

        sl.insert(z);
        ArrayList<KVPair<String, Rectangle>> trial1 = new ArrayList<>();
        trial1.add(z);
        assertEquals(trial1, sl.search("4")); // new separate key
        assertEquals(3, sl.size());

        KVPair<String, Rectangle> w = new KVPair<>("2", new Rectangle(13, 14,
            15, 16));
        KVPair<String, Rectangle> v = new KVPair<>("3", new Rectangle(17, 18,
            19, 20));

        sl.insert(w);
        sl.insert(v);
        assertEquals(5, sl.size());

        // add more duplicates to see what happens
        KVPair<String, Rectangle> y2 = new KVPair<>("1", new Rectangle(21, 22,
            23, 24));
        sl.insert(y2);
        trial.add(y2);
        assertEquals(trial, sl.search("1")); // duplicates
        assertEquals(6, sl.size());

        sl = new SkipList<>();
        // add to list
        KVPair<String, Rectangle> a = new KVPair<>("3", new Rectangle(15, 16,
            17, 18));
        sl.insert(a);
        ArrayList<KVPair<String, Rectangle>> trial2 = new ArrayList<>();
        trial2.add(a);
        assertEquals(trial2, sl.search("3"));
        // add to empty list
        assertEquals(1, sl.size());
    }


    // ----------------------------------------------------------
    /**
     * tests the remove method
     */

    public void testRemove() {

        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        KVPair<String, Rectangle> y = new KVPair<>("2", new Rectangle(5, 6, 7,
            8));
        KVPair<String, Rectangle> z = new KVPair<>("3", new Rectangle(9, 10, 11,
            12));

        // test remove when empty
        assertNull(sl.remove("1"));
        assertEquals(0, sl.size());

        // test single insertion and removal
        sl.insert(x);
        assertEquals(1, sl.size());
        KVPair<String, Rectangle> removed = sl.remove("1");
        assertEquals(x, removed);
        assertEquals(0, sl.size());

        // test multiple insertions and removals
        sl.insert(x);
        sl.insert(y);
        sl.insert(z);
        assertEquals(3, sl.size());

        removed = sl.remove("2"); // delete the middle
        assertEquals(y, removed);
        assertEquals(2, sl.size());

        // test duplicate keys
        sl = new SkipList<>();
        KVPair<String, Rectangle> dup1 = new KVPair<>("5", new Rectangle(17, 18,
            19, 20));
        KVPair<String, Rectangle> dup2 = new KVPair<>("5", new Rectangle(21, 22,
            23, 24));
        sl.insert(dup1);
        sl.insert(dup2);

        removed = sl.remove("5");
        assertEquals(1, sl.size());
        assertTrue(removed.equals(dup1) || removed.equals(dup2));
        ArrayList<KVPair<String, Rectangle>> remainingList = sl.search("5");
        assertEquals(1, remainingList.size());

        // test removing the last element
        sl = new SkipList<>();
        KVPair<String, Rectangle> a = new KVPair<>("3", new Rectangle(13, 14,
            15, 16));
        sl.insert(a);
        removed = sl.remove("3");
        assertEquals(a, removed);
        assertEquals(0, sl.size());

        sl = new SkipList<>();
        KVPair<String, Rectangle> w1 = new KVPair<>("1", new Rectangle(1, 1, 1,
            1));
        KVPair<String, Rectangle> w2 = new KVPair<>("2", new Rectangle(2, 2, 2,
            2));
        KVPair<String, Rectangle> w3 = new KVPair<>("3", new Rectangle(3, 3, 3,
            3));
        KVPair<String, Rectangle> w4 = new KVPair<>("4", new Rectangle(4, 4, 4,
            4));

        sl.insert(w1);
        sl.insert(w2); // reverse order
        sl.insert(w3);
        sl.insert(w4);

        // highest first
        removed = sl.remove("4");
        assertEquals(w4, removed);
        assertEquals(3, sl.size());

        // Remove remaining nodes
        removed = sl.remove("1");
        assertEquals(w1, removed);
        removed = sl.remove("2");
        assertEquals(w2, removed);
        removed = sl.remove("3");
        assertEquals(w3, removed);
        assertEquals(0, sl.size());

        // Test non-existent key
        assertNull(sl.remove("nonexistent"));
        assertEquals(0, sl.size());

        // Test null key
        assertNull(sl.remove(null));
        assertEquals(0, sl.size());

        // Test removing all elements and verify level reset
        sl.insert(new KVPair<>("4", new Rectangle(1, 2, 3, 4)));
        sl.insert(new KVPair<>("5", new Rectangle(5, 6, 7, 8)));
        sl.insert(new KVPair<>("6", new Rectangle(9, 10, 11, 12)));
        assertEquals(3, sl.size());

        sl.remove("4");
        sl.remove("5");
        sl.remove("6");
        assertEquals(0, sl.size());
        assertNull(sl.remove("4")); // nake sure empty

        sl.insert(new KVPair<>("end", new Rectangle(1, 2, 3, 4)));
        KVPair<String, Rectangle> lastRemoved = sl.remove("end");
        assertNotNull(lastRemoved);
        assertEquals(0, sl.size());

        // testing duplicates when removuing
        sl = new SkipList<>();
        KVPair<String, Rectangle> dupKey1 = new KVPair<>("dup", new Rectangle(1,
            1, 1, 1));
        KVPair<String, Rectangle> dupKey2 = new KVPair<>("dup", new Rectangle(2,
            2, 2, 2));
        sl.insert(dupKey1);
        sl.insert(dupKey2);

        removed = sl.remove("dup");
        assertTrue(removed.equals(dupKey1) || removed.equals(dupKey2));
        assertEquals(1, sl.size());

        // remove next duplicate
        removed = sl.remove("dup");
        assertTrue(removed.equals(dupKey1) || removed.equals(dupKey2));
        assertEquals(0, sl.size());
    }


    // ----------------------------------------------------------
    /**
     * tests remove by value method
     */
    public void testRemoveByValue() {

        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        KVPair<String, Rectangle> y = new KVPair<>("2", new Rectangle(5, 6, 7,
            8));
        KVPair<String, Rectangle> z = new KVPair<>("3", new Rectangle(1, 2, 3,
            4)); // duplicate

        // empty list
        assertNull(sl.removeByValue(new Rectangle(1, 2, 3, 4)));
        assertEquals(0, sl.size());

        // one element
        sl.insert(x);
        assertEquals(1, sl.size());
        KVPair<String, Rectangle> removed = sl.removeByValue(new Rectangle(1, 2,
            3, 4));
        assertEquals(x, removed);
        assertEquals(0, sl.size());

        // multiple elements
        sl.insert(x);
        sl.insert(y);
        sl.insert(z);
        assertEquals(3, sl.size());

        removed = sl.removeByValue(new Rectangle(1, 2, 3, 4));
        assertTrue(removed.equals(x) || removed.equals(z));
        assertEquals(2, sl.size());

        // value not found
        assertNull(sl.removeByValue(new Rectangle(9, 9, 9, 9)));
        assertEquals(2, sl.size());

        sl = new SkipList<>();
        KVPair<String, Rectangle> a = new KVPair<>("4", new Rectangle(13, 14,
            15, 16));
        sl.insert(a);
        removed = sl.removeByValue(new Rectangle(13, 14, 15, 16));
        assertEquals(a, removed);
        assertEquals(0, sl.size());

    }


    /***
     * Example 1: Test `randomLevel` method with
     * predetermined random values using a scripted Random
     */
    public void testRandomLevelOne() {
        sl = new SkipList<String, Rectangle>(new testsupport.ScriptedRandom(
            false));
        int randomLevelValue = sl.randomLevel();

        // This returns 1 because the first preset
        // random boolean is `false` which breaks
        // the `while condition inside the `randomLevel` method
        int expectedLevelValue = 1;

        // Compare the values
        assertEquals(expectedLevelValue, randomLevelValue);
    }


    // ----------------------------------------------------------
    /**
     * tests the dump method
     */
    public void testDump() {

        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        KVPair<String, Rectangle> y = new KVPair<>("2", new Rectangle(5, 6, 7,
            8));
        KVPair<String, Rectangle> z = new KVPair<>("3", new Rectangle(9, 10, 11,
            12));

        // empty list
        systemOut().clearHistory();
        sl.dump();
        assertEquals("SkipList dump:\nNode with depth 1, Value null"
            + "\nSkipList size is: 0\n", systemOut().getHistory());

        // one element
        sl.insert(x);
        systemOut().clearHistory();
        sl.dump();
        assertTrue(systemOut().getHistory().startsWith("SkipList dump:\n"));
        assertTrue(systemOut().getHistory().contains("Node with depth"));
        assertTrue(systemOut().getHistory().contains(x.toString()));
        assertTrue(systemOut().getHistory().endsWith("SkipList size is: 1\n"));

        // multiple elements
        sl.insert(y);
        sl.insert(z);
        systemOut().clearHistory();
        sl.dump();
        assertTrue(systemOut().getHistory().startsWith("SkipList dump:\n"));
        assertTrue(systemOut().getHistory().contains(x.toString()));
        assertTrue(systemOut().getHistory().contains(y.toString()));
        assertTrue(systemOut().getHistory().contains(z.toString()));
        assertTrue(systemOut().getHistory().endsWith("SkipList size is: 3\n"));
    }


    // ----------------------------------------------------------
    /**
     * tests the hasNect method for the iterator
     */
    public void testHasNext() {
        // Test empty list
        Iterator<KVPair<String, Rectangle>> iterator = sl.iterator();
        assertFalse(iterator.hasNext());

        // Test single element
        KVPair<String, Rectangle> x = new KVPair<>("1", new Rectangle(1, 2, 3,
            4));
        sl.insert(x);
        iterator = sl.iterator();
        assertTrue(iterator.hasNext());
        iterator.next();
        assertFalse(iterator.hasNext());

        // Test multiple elements
        KVPair<String, Rectangle> y = new KVPair<>("2", new Rectangle(5, 6, 7,
            8));
        sl.insert(y);
        iterator = sl.iterator();
        assertTrue(iterator.hasNext());
        iterator.next();
        assertTrue(iterator.hasNext());
        iterator.next();
        assertFalse(iterator.hasNext());
    }


    /***
     * Example 2: Test `randomLevel` method with
     * predetermined random values using a scripted Random
     */
    public void testRandomLevelFour() {
        sl = new SkipList<String, Rectangle>(new testsupport.ScriptedRandom(
            true, true, true, false, true, false));
        int randomLevelValue = sl.randomLevel();

        // This returns 4 because the fourth preset
        // random boolean is `false` which breaks
        // the `while condition inside the `randomLevel` method
        int expectedLevelValue = 4;

        // Compare the values
        assertEquals(expectedLevelValue, randomLevelValue);
    }


    // ----------------------------------------------------------
    /**
     * removeByValue must remove the matching rectangle even when another
     * rectangle shares its key (regression: it used to remove the first pair
     * with that key instead).
     */
    public void testRemoveByValueWithDuplicateKeys() {
        Rectangle a = new Rectangle(1, 1, 2, 2);
        Rectangle b = new Rectangle(5, 5, 2, 2);
        sl = new SkipList<String, Rectangle>(new java.util.Random(7));
        sl.insert(new KVPair<>("r", a));
        sl.insert(new KVPair<>("r", b));
        sl.insert(new KVPair<>("s", new Rectangle(9, 9, 1, 1)));

        assertEquals(b, sl.removeByValue(b).getValue());
        assertEquals(2, sl.size());
        ArrayList<KVPair<String, Rectangle>> left = sl.search("r");
        assertEquals(1, left.size());
        assertEquals(a, left.get(0).getValue());
        assertNull(sl.removeByValue(b));
    }


    // ----------------------------------------------------------
    /**
     * Stress test: random inserts/removes stay sorted and sized correctly.
     */
    public void testRandomOperationsKeepListSorted() {
        java.util.Random r = new java.util.Random(42);
        sl = new SkipList<String, Rectangle>(new java.util.Random(1));
        java.util.List<String> model = new java.util.ArrayList<>();
        for (int n = 0; n < 2000; n++) {
            String key = "k" + r.nextInt(50);
            if (r.nextInt(3) == 0) {
                boolean removed = sl.remove(key) != null;
                assertEquals(model.remove(key), removed);
            }
            else {
                sl.insert(new KVPair<>(key, new Rectangle(1, 1, 1, 1)));
                model.add(key);
            }
            assertEquals(model.size(), sl.size());
        }
        java.util.Collections.sort(model);
        java.util.List<String> actual = new java.util.ArrayList<>();
        for (KVPair<String, Rectangle> p : sl) {
            actual.add(p.getKey());
        }
        assertEquals(model, actual);
    }


    // ----------------------------------------------------------
    /**
     * Iterating past the end throws NoSuchElementException, not NPE.
     */
    public void testIteratorPastEnd() {
        Iterator<KVPair<String, Rectangle>> it = sl.iterator();
        assertFalse(it.hasNext());
        try {
            it.next();
            fail("expected NoSuchElementException");
        }
        catch (java.util.NoSuchElementException expected) {
            // expected
        }
    }
}
