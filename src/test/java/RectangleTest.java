import testsupport.TestCase;

/**
 * This class tests the methods of Rectangle class,
 * ensuring that they work as they should.
 * 
 * @author aadi hiranandani, sreyas kode
 * @version 2025-02-08
 */
public class RectangleTest extends TestCase {

    private Rectangle r1;
    private Rectangle r2;

    /**
     * initializes a rectangle object to be used for the tests.
     */
    public void setUp() {
        r1 = new Rectangle(1, 1, 10, 10);
        r2 = new Rectangle(1, 1, 10, 10);
    }


    // ----------------------------------------------------------
    /**
     * test the get x-coordinate method
     */
    public void testGetXCoordinate() {
        assertEquals(1, r1.getxCoordinate());
        assertEquals(1, r2.getxCoordinate());
    }


    // ----------------------------------------------------------
    /**
     * tests the get y-coordinate method
     */
    public void testGetYCoordinate() {
        assertEquals(1, r1.getyCoordinate());
    }


    // ----------------------------------------------------------
    /**
     * tests getWidth method
     */
    public void testGetWidth() {
        assertEquals(10, r1.getWidth());
    }


    // ----------------------------------------------------------
    /**
     * tests getHeight method
     */
    public void testGetHeight() {
        assertEquals(10, r1.getHeight());
    }


    // ----------------------------------------------------------
    /**
     * tests the intersect method
     */
    public void testIntesect() {
        r2 = new Rectangle(1, 1, 10, 10);
        assertTrue(r1.intersect(r2));
        assertTrue(r2.intersect(r1));

        r2 = new Rectangle(2, 2, 10, 10);
        assertTrue(r1.intersect(r2));
        assertTrue(r2.intersect(r1));

        r2 = new Rectangle(5, 5, 10, 10);
        assertTrue(r1.intersect(r2));
        assertTrue(r2.intersect(r1));

        r2 = new Rectangle(15, 15, 10, 10);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(12, 1, 10, 10);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(1, 12, 10, 10);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(1, 11, 5, 5);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(11, 1, 5, 5);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(1, -5, 10, 5);
        assertFalse(r1.intersect(r2));
        assertFalse(r2.intersect(r1));

        r2 = new Rectangle(1, 10, 10, 5);
        assertTrue(r1.intersect(r2));
        assertTrue(r2.intersect(r1));

    }


    // ----------------------------------------------------------
    /**
     * tests the equal method
     */
    public void testEquals() {

        // test equal rectangles
        Rectangle rect1 = new Rectangle(1, 2, 3, 4);
        Rectangle rect2 = new Rectangle(1, 2, 3, 4);
        assertTrue(rect1.equals(rect2));

        // tests unequal rectangles
        Rectangle rect3 = new Rectangle(2, 2, 3, 4);
        assertFalse(rect1.equals(rect3));

        // tests null comparison
        assertFalse(rect1.equals(null));

        // tests same object
        assertTrue(rect1.equals(rect1));

        // tests different object type
        String notRect = "not a rectangle";
        assertFalse(rect1.equals(notRect));

        // tests with multiple dimensions
        Rectangle rect4 = new Rectangle(1, 2, 4, 4); // different width
        Rectangle rect5 = new Rectangle(1, 2, 3, 5); // different height
        assertFalse(rect1.equals(rect4));
        assertFalse(rect1.equals(rect5));

        Rectangle rectY1 = new Rectangle(1, 2, 3, 4);
        Rectangle rectY2 = new Rectangle(1, 999, 3, 4); // Same x/width/height,
                                                        // different y
        assertFalse(rectY1.equals(rectY2));
    }


    // ----------------------------------------------------------
    /**
     * test the isInvalid method
     */
    public void testIsInvalid() {

        // for negative values
        Rectangle negValHeight = new Rectangle(2, 8, 5, -1);
        Rectangle negValWidth = new Rectangle(2, 4, -2, 4);
        assertTrue(negValHeight.isInvalid());
        assertTrue(negValWidth.isInvalid());

        // when it zero dimensions
        Rectangle zeroH = new Rectangle(2, 8, 5, 0);
        Rectangle zeroW = new Rectangle(2, 4, 0, 4);
        assertTrue(zeroH.isInvalid());
        assertTrue(zeroW.isInvalid());

        // testing out of bounds
        Rectangle outBoundsX = new Rectangle(1022, 8, 10, 0);
        Rectangle outBoundsY = new Rectangle(2, 1022, 10, 10);
        assertTrue(outBoundsX.isInvalid());
        assertTrue(outBoundsY.isInvalid());

        // testing negative coordinates
        Rectangle negX = new Rectangle(-1, 8, 5, 2);
        Rectangle negY = new Rectangle(2, -4, 5, 5);
        assertTrue(negX.isInvalid());
        assertTrue(negY.isInvalid());

        // a valid rectangle
        Rectangle valid = new Rectangle(2, 8, 5, 1);
        assertFalse(valid.isInvalid());

        // test x + width exactly at 1024 - works
        Rectangle exactXBoundary = new Rectangle(0, 0, 1024, 1);
        assertFalse(exactXBoundary.isInvalid());

        // test x + width over 1024 - doesnt work
        Rectangle xOverflow = new Rectangle(1, 0, 1024, 1);
        assertTrue(xOverflow.isInvalid());

        // test y + height exactly at 1024 - works
        Rectangle exactYBoundary = new Rectangle(0, 0, 1, 1024);
        assertFalse(exactYBoundary.isInvalid());

        // test y + height over 1024 - doesnt works
        Rectangle yOverflow = new Rectangle(0, 1, 1, 1024);
        assertTrue(yOverflow.isInvalid());

        // test edge case - works
        Rectangle xEdgeValid = new Rectangle(1023, 0, 1, 1);
        assertFalse(xEdgeValid.isInvalid());

        // test edge case - works
        Rectangle yEdgeValid = new Rectangle(0, 1023, 1, 1);
        assertFalse(yEdgeValid.isInvalid());

        // test x = 1024 - doesnt work
        Rectangle xAtMaxPlusOne = new Rectangle(1024, 0, 1, 1);
        assertTrue(xAtMaxPlusOne.isInvalid());

        // test y = 1024 - doesnt work
        Rectangle yAtMaxPlusOne = new Rectangle(0, 1024, 1, 1);
        assertTrue(yAtMaxPlusOne.isInvalid());

        // test valid x but invalid y
        Rectangle mixedInvalid1 = new Rectangle(500, 1024, 500, 3);
        assertTrue(mixedInvalid1.isInvalid());

        // test invalid x but valid y
        Rectangle mixedInvalid2 = new Rectangle(1024, 500, 3, 500);
        assertTrue(mixedInvalid2.isInvalid());
    }
}
