package testsupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * JUnit 3-style base class that captures System.out so tests can assert on
 * console output. Replaces the course-provided student.TestCase so the
 * project builds with plain JUnit.
 */
public abstract class TestCase extends junit.framework.TestCase {

    private PrintStream original;
    private final OutputHistory history = new OutputHistory();

    /**
     * Captured console output for the current test.
     */
    public static final class OutputHistory {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /**
         * @return everything printed since the last clear, with \n line endings
         */
        public String getHistory() {
            return buffer.toString().replace("\r\n", "\n");
        }


        /** Forget everything printed so far. */
        public void clearHistory() {
            buffer.reset();
        }
    }

    @Override
    protected void runTest() throws Throwable {
        original = System.out;
        history.clearHistory();
        System.setOut(new PrintStream(history.buffer, true));
        try {
            super.runTest();
        }
        finally {
            System.setOut(original);
        }
    }


    /**
     * @return the captured System.out for this test
     */
    public OutputHistory systemOut() {
        return history;
    }
}
