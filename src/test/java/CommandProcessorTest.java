import testsupport.TestCase;

/**
 * This class tests the CommandProcessor class.
 * Test each possible command on its bounds,
 * if applicable to ensure they work properly.
 * Also test passing improper command to ensure
 * all class functionalities work as intended.
 * 
 * @author <aadi hiranandani, sreyas kode
 * @version 2025-02-08
 */
public class CommandProcessorTest extends TestCase {

    private CommandProcessor cp;

    /**
     * The setUp() method will be called automatically before
     * each test and reset whatever the test modified. For this
     * test class, only a new database object is needed, so
     * create a database here for use in each test case.
     */
    public void setUp() {
        cp = new CommandProcessor();
    }


    // ----------------------------------------------------------
    /**
     * this method tests all the functions of the rectangle and skip list
     */

    public void testProcessor() {
        systemOut().clearHistory();
        cp.processor("djklncwl");
        assertEquals("Unrecognized command.\n", systemOut().getHistory());

        systemOut().clearHistory();
        cp.processor("insert r1 1 1 10 10");
        assertEquals("Rectangle inserted: (r1, 1, 1, 10, 10)\n", systemOut()
            .getHistory());
        systemOut().clearHistory();
        cp.processor("insert r1 -1 -1 10 10");
        assertEquals("Rectangle rejected: (r1, -1, -1, 10, 10)\n", systemOut()
            .getHistory());

        systemOut().clearHistory();
        cp.processor("remove r1");
        assertEquals("Rectangle removed: (r1, 1, 1, 10, 10)\n", systemOut()
            .getHistory());
        systemOut().clearHistory();
        cp.processor("remove r2");
        assertEquals("Rectangle not removed: r2\n", systemOut().getHistory());

        cp.processor("insert r1 1 1 10 10");
        systemOut().clearHistory();
        cp.processor("remove 1 1 10 10");
        assertEquals("Rectangle removed: (r1, 1, 1, 10, 10)\n", systemOut()
            .getHistory());
        systemOut().clearHistory();
        cp.processor("remove -1 -1 -10 10");
        assertEquals("Rectangle rejected: (-1, -1, -10, 10)\n", systemOut()
            .getHistory());
        systemOut().clearHistory();
        cp.processor("remove 1 1 20 10");
        assertEquals("Rectangle not found: (1, 1, 20, 10)\n", systemOut()
            .getHistory());
        systemOut().clearHistory();
        cp.processor("remove 1 1 20 10 10");
        assertEquals("Invalid command: remove 1 1 20 10 10 (expected 4 "
            + "argument(s), got 5)\n", systemOut().getHistory());

        cp.processor("insert r1 1 1 10 10");
        cp.processor("insert r2 1 1 10 10");
        cp.processor("insert r3 15 15 10 10");
        systemOut().clearHistory();
        cp.processor("regionsearch 1 1 10 10");
        assertEquals("Rectangles intersecting region (1, 1, "
            + "10, 10):\n(r1, 1, 1, 10, 10)\n" + "(r2, 1, 1, 10, 10)\n",
            systemOut().getHistory());
        systemOut().clearHistory();
        cp.processor("regionsearch 1 1 0 10");
        assertEquals("Rectangle rejected: (1, 1, 0, 10)\n", systemOut()
            .getHistory());

        systemOut().clearHistory();
        cp.processor("intersections");
        assertEquals(
            "Intersection pairs:\n(r1, 1, 1, 10, 10) | (r2, 1, 1, 10, 10)\n",
            systemOut().getHistory());
        systemOut().clearHistory();
        cp.processor("search r1");
        assertEquals("Rectangles Found:\n(r1, 1, 1, 10, 10)\n", systemOut()
            .getHistory());
    }


    /**
     * Malformed lines are reported and processing continues.
     */
    public void testMalformedCommands() {
        CommandProcessor cp = new CommandProcessor();
        cp.processor("insert r1 1 2");
        assertEquals("Invalid command: insert r1 1 2 (expected 5 argument(s), "
            + "got 3)\n", systemOut().getHistory());
        systemOut().clearHistory();
        cp.processor("regionsearch 1 x 3 4");
        assertEquals("Invalid command: regionsearch 1 x 3 4 (not an integer: "
            + "x)\n", systemOut().getHistory());
        systemOut().clearHistory();
        cp.processor("search");
        assertTrue(systemOut().getHistory().startsWith("Invalid command"));
        systemOut().clearHistory();
        cp.processor("insert ok 1 1 2 2");
        assertEquals("Rectangle inserted: (ok, 1, 1, 2, 2)\n", systemOut()
            .getHistory());
    }
}
