import student.TestCase;

/**
 * This class tests the methods of Rectangle1 class
 * which serves as the entry point of the command line
 * program.
 * 
 * @author Patrick Sullivan
 * 
 * @version 2024.1
 */
public class SkipListProjectTest extends TestCase {

    /**
     * Tests the main method when the file name is valid.
     */
    public void testMain() {

        // Test with existing sample file that has mixed content
        String[] args = new String[] { "SampleInput.txt" };
        systemOut().clearHistory();
        SkipListProject.main(args);

        // Test with invalid file
        String[] invalidArgs = new String[] { "nonexistentfile.txt" };
        systemOut().clearHistory();
        SkipListProject.main(invalidArgs);
        String output = systemOut().getHistory();
        assertTrue(output.contains("Invalid file"));

    }
}
