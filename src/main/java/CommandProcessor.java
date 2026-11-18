
/**
 * The purpose of this class is to parse a text file into its appropriate, line
 * by line commands for the format specified in the project spec.
 * 
 * @author CS Staff
 * 
 * @version 2024-01-22
 */
public class CommandProcessor {

    // the database object to manipulate the
    // commands that the command processor
    // feeds to it
    private Database data;

    /**
     * The constructor for the command processor requires a database instance to
     * exist, so the only constructor takes a database class object to feed
     * commands to.
     * 
     */
    public CommandProcessor() {
        data = new Database();
    }


    /**
     * This method parses keywords in the line and calls methods in the
     * database as required. Each line command will be specified by one of the
     * keywords to perform the actions.
     * These actions are performed on specified objects and include insert,
     * remove,
     * regionsearch, search, and dump. If the command in the file line is not
     * one of these, an appropriate message will be written in the console. This
     * processor method is called for each line in the file. Note that the
     * methods called will themselves write to the console, this method does
     * not, only calling methods that do.
     * 
     * @param line
     *            a single line from the text file
     */
    public void processor(String line) {
        String[] arr = line.trim().split("\\s+");
        String command = arr[0];
        try {
            switch (command) {
                case "insert":
                    requireArgs(arr, 5);
                    data.insert(new KVPair<String, Rectangle>(arr[1],
                        new Rectangle(toInt(arr[2]), toInt(arr[3]), toInt(
                            arr[4]), toInt(arr[5]))));
                    break;
                case "remove":
                    // remove <name>  or  remove <x> <y> <w> <h>
                    if (arr.length == 2) {
                        data.remove(arr[1]);
                    }
                    else {
                        requireArgs(arr, 4);
                        data.remove(toInt(arr[1]), toInt(arr[2]), toInt(
                            arr[3]), toInt(arr[4]));
                    }
                    break;
                case "regionsearch":
                    requireArgs(arr, 4);
                    data.regionsearch(toInt(arr[1]), toInt(arr[2]), toInt(
                        arr[3]), toInt(arr[4]));
                    break;
                case "intersections":
                    requireArgs(arr, 0);
                    data.intersections();
                    break;
                case "search":
                    requireArgs(arr, 1);
                    data.search(arr[1]);
                    break;
                case "dump":
                    requireArgs(arr, 0);
                    data.dump();
                    break;
                default:
                    System.out.println("Unrecognized command.");
            }
        }
        catch (IllegalArgumentException e) {
            // covers NumberFormatException and wrong argument counts, so one
            // malformed line is reported instead of crashing the whole run
            System.out.println("Invalid command: " + line.trim() + " ("
                + e.getMessage() + ")");
        }
    }


    /**
     * Ensures a command has exactly the expected number of arguments.
     */
    private static void requireArgs(String[] arr, int expected) {
        int actual = arr.length - 1;
        if (actual != expected) {
            throw new IllegalArgumentException("expected " + expected
                + " argument(s), got " + actual);
        }
    }


    /**
     * Parses an integer argument with a readable error message.
     */
    private static int toInt(String s) {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an integer: " + s);
        }
    }

}
