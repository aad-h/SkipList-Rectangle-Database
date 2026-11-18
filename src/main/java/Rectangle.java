
/**
 * This class holds the coordinates and dimensions of a rectangle and methods to
 * check if it intersects or has the same coordinates as an other rectangle.
 * 
 * @author CS Staff
 * 
 * @version 2024-01-22
 */
public class Rectangle {
    // the x coordinate of the rectangle
    private int xCoordinate;
    // the y coordinate of the rectangle
    private int yCoordinate;
    // the distance from the x coordinate the rectangle spans
    private int width;
    // the distance from the y coordinate the rectangle spans
    private int height;

    /**
     * Creates an object with the values to the parameters given in the
     * xCoordinate, yCoordinate, width, height
     * 
     * @param x
     *            x-coordinate of the rectangle
     * @param y
     *            y-coordinate of the rectangle
     * @param w
     *            width of the rectangle
     * @param h
     *            height of the rectangle
     */
    public Rectangle(int x, int y, int w, int h) {
        xCoordinate = x;
        yCoordinate = y;
        width = w;
        height = h;
    }


    /**
     * Getter for the x coordinate
     *
     * @return the x coordinate
     */
    public int getxCoordinate() {
        return xCoordinate;
    }


    /**
     * Getter for the y coordinate
     *
     * @return the y coordinate
     */
    public int getyCoordinate() {
        return yCoordinate;
    }


    /**
     * Getter for the width
     *
     * @return the width
     */
    public int getWidth() {
        return width;
    }


    /**
     * Getter for the height
     *
     * @return the height
     */
    public int getHeight() {
        return height;
    }


    /**
     * Checks if the invoking rectangle intersects with rec.
     * 
     * @param r2
     *            Rectangle parameter
     * @return true if the rectangle intersects with rec, false if not
     */
    public boolean intersect(Rectangle r2) {
        boolean a = this.getxCoordinate() < r2.getxCoordinate() + r2.getWidth();
        boolean b = this.getxCoordinate() + this.getWidth() > r2
            .getxCoordinate();
        boolean c = this.getyCoordinate() < r2.getyCoordinate() + r2
            .getHeight();
        boolean d = this.getyCoordinate() + this.getHeight() > r2
            .getyCoordinate();

        return a && b && c && d;

    }


    /**
     * Checks, if the invoking rectangle has the same coordinates as rec.
     * 
     * @param rec
     *            the rectangle parameter
     * @return true if the rectangle has the same coordinates as rec, false if
     *         not
     */
    @Override
    public boolean equals(Object rec) {
        if (rec == null) {
            return false;
        }
        else if (!(rec instanceof Rectangle)) {
            return false;
        }
        else if (rec == this) {
            return true;
        }
        else if (this.getHeight() == ((Rectangle)rec).getHeight() && this
            .getWidth() == ((Rectangle)rec).getWidth() && this
                .getxCoordinate() == ((Rectangle)rec).getxCoordinate() && this
                    .getyCoordinate() == ((Rectangle)rec).getyCoordinate()) {
            return true;
        }
        else {
            return false;
        }
    }


    /**
     * Hash code consistent with equals(), so Rectangles work in hash-based
     * collections.
     *
     * @return hash of the coordinates and dimensions
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(xCoordinate, yCoordinate, width,
            height);
    }


    /**
     * Outputs a human readable string with information about the rectangle
     * which includes the x and y coordinate and its height and width
     * 
     * @return a human readable string containing information about the
     *         rectangle
     */
    public String toString() {
        return this.getxCoordinate() + ", " + this.getyCoordinate() + ", "
            + this.getWidth() + ", " + this.getHeight();
    }


    /**
     * Checks if the rectangle has invalid parameters
     * 
     * @return true if the rectangle has invalid parameters, false if not
     */
    public boolean isInvalid() {
        if (this.getHeight() <= 0 || this.getWidth() <= 0) {
            return true;
        }
        return this.getxCoordinate() + this.getWidth() > 1024 || this
            .getxCoordinate() < 0 || this.getyCoordinate() + this
                .getHeight() > 1024 || this.getyCoordinate() < 0;
    }
}
