package testsupport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * A Random whose nextBoolean() returns a fixed script, then falls back to
 * false. Lets tests control skip list node heights exactly.
 */
public class ScriptedRandom extends Random {
    private static final long serialVersionUID = 1L;
    private final Deque<Boolean> script = new ArrayDeque<>();

    /**
     * @param booleans
     *            values returned by successive nextBoolean() calls
     */
    public ScriptedRandom(boolean... booleans) {
        super(0);
        for (boolean b : booleans) {
            script.add(b);
        }
    }


    @Override
    public boolean nextBoolean() {
        return script.isEmpty() ? false : script.poll();
    }
}
