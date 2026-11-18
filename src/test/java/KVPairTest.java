import testsupport.TestCase;

/**
 * This class tests the KVPair class so that the member methods work properly
 * and that the expected behavior occurs.
 * 
 * @author CS Staff
 * 
 * @version 2024.1
 */
public class KVPairTest extends TestCase {

    private KVPair<String, Integer> pair1;
    private KVPair<String, Integer> pair2;
    private KVPair<String, Integer> pair3;

    /**
     * initializes KVPair objects for testing
     */
    public void setUp() {
        pair1 = new KVPair<>("k1", 1);
        pair2 = new KVPair<>("k2", 2);
        pair3 = new KVPair<>("k2", 3);
    }


    /**
     * tests the getKey method
     */
    public void testGetKey() {
        assertEquals("k1", pair1.getKey());
        assertEquals("k2", pair2.getKey());
        assertEquals("k2", pair3.getKey());
    }


    /**
     * tests the getValue method
     */
    public void testGetValue() {
        assertEquals(1, (int)pair1.getValue());
        assertEquals(2, (int)pair2.getValue());
        assertEquals(3, (int)pair3.getValue());
    }


    /**
     * tests the toString method
     */
    public void testToString() {
        assertEquals("(k1, 1)", pair1.toString());
        assertEquals("(k2, 2)", pair2.toString());
        assertEquals("(k2, 3)", pair3.toString());
    }
}
