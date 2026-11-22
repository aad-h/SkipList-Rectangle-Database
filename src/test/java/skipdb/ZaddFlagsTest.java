package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * ZADD NX / XX / GT / LT / CH / INCR. Expected replies were checked against
 * Redis 7.0.
 */
public class ZaddFlagsTest {

    private CommandHandler h;

    private Object run(String line) {
        return h.execute(Arrays.asList(line.split(" ")));
    }


    private void assertError(String expected, String line) {
        Object r = run(line);
        assertTrue(line + " -> " + r, r instanceof Resp.Error);
        assertEquals(expected, ((Resp.Error)r).message);
    }


    @Before
    public void setUp() {
        h = new CommandHandler();
        run("ZADD z 10 a 20 b");
    }


    @Test
    public void nxOnlyAddsNewMembers() {
        assertEquals(1L, run("ZADD z NX 99 a 30 c"));
        assertEquals("10", run("ZSCORE z a"));
        assertEquals("30", run("ZSCORE z c"));
    }


    @Test
    public void xxOnlyUpdatesExistingMembers() {
        assertEquals(0L, run("ZADD z XX 11 a 30 c"));
        assertEquals("11", run("ZSCORE z a"));
        assertNull(run("ZSCORE z c"));
        assertEquals(1L, run("ZADD z XX CH 12 a 30 c"));
    }


    @Test
    public void xxNeverCreatesTheKey() {
        assertEquals(0L, run("ZADD fresh XX 1 a"));
        assertEquals(0L, run("EXISTS fresh"));
        assertNull(run("ZADD fresh XX INCR 1 a"));
        assertEquals(0L, run("EXISTS fresh"));
    }


    @Test
    public void gtAndLtRestrictUpdatesButAlwaysAdd() {
        assertEquals(2L, run("ZADD z GT CH 5 a 25 b 1 c"));
        assertEquals("10", run("ZSCORE z a"));
        assertEquals("25", run("ZSCORE z b"));
        assertEquals("1", run("ZSCORE z c"));
        assertEquals(1L, run("ZADD z LT CH 5 a 99 b"));
        assertEquals("5", run("ZSCORE z a"));
        assertEquals("25", run("ZSCORE z b"));
        // GT with XX: update only if greater, never add
        assertEquals(1L, run("ZADD z GT XX CH 6 a 0 b 7 d"));
        assertEquals("6", run("ZSCORE z a"));
        assertNull(run("ZSCORE z d"));
    }


    @Test
    public void chCountsChangedNotMerelyTouched() {
        assertEquals(0L, run("ZADD z CH 10 a 20 b"));
        assertEquals(2L, run("ZADD z CH 11 a 30 c"));
        assertEquals(1L, run("ZADD z 12 a 40 d")); // without CH: added only
    }


    @Test
    public void incrActsLikeZincrby() {
        assertEquals("15.5", run("ZADD z INCR 5.5 a"));
        assertEquals("3", run("ZADD z INCR 3 new"));
        assertEquals("20", run("ZADD z INCR 0 b")); // unchanged still replies
        assertNull(run("ZADD z NX INCR 1 a"));
        assertEquals("1", run("ZADD z NX INCR 1 other"));
        assertNull(run("ZADD z XX INCR 1 missing"));
        assertNull(run("ZSCORE z missing"));
    }


    @Test
    public void incrWithGtLtComparesTheResult() {
        assertNull(run("ZADD z GT INCR -1 a"));
        assertEquals("10", run("ZSCORE z a"));
        assertEquals("11", run("ZADD z GT INCR 1 a"));
        assertNull(run("ZADD z LT INCR 1 a"));
        assertEquals("9", run("ZADD z LT INCR -2 a"));
        assertNull(run("ZADD z GT INCR 0 a")); // equal is not greater
    }


    @Test
    public void incrNanIsAnError() {
        run("ZADD z +inf top");
        assertError("ERR resulting score is not a number (NaN)",
            "ZADD z INCR -inf top");
        assertEquals("inf", run("ZSCORE z top"));
    }


    @Test
    public void optionsAreCaseInsensitive() {
        assertEquals(1L, run("ZADD z xx Ch 11 a"));
        assertEquals("12", run("ZADD z iNcR 1 a"));
    }


    @Test
    public void incompatibleOptionsAndSyntaxErrors() {
        assertError("ERR XX and NX options at the same time are not compatible",
            "ZADD z NX XX 1 a");
        assertError("ERR GT, LT, and/or NX options at the same time are not "
            + "compatible", "ZADD z GT LT 1 a");
        assertError("ERR GT, LT, and/or NX options at the same time are not "
            + "compatible", "ZADD z NX GT 1 a");
        assertError("ERR GT, LT, and/or NX options at the same time are not "
            + "compatible", "ZADD z LT NX 1 a");
        assertError("ERR INCR option supports a single increment-element pair",
            "ZADD z INCR 1 a 2 b");
        assertError("ERR syntax error", "ZADD z NX CH 1");
        assertError("ERR syntax error", "ZADD z NX XX");
        assertError("ERR value is not a valid float", "ZADD z 1 a NX 2"); // flags go first
        // option errors win over bad scores; bad scores change nothing
        assertError("ERR XX and NX options at the same time are not compatible",
            "ZADD z NX XX x a");
        assertError("ERR value is not a valid float", "ZADD z CH 1 a x b");
        assertEquals("10", run("ZSCORE z a"));
        assertError("ERR value is not a valid float", "ZADD fresh NX nan a");
        assertEquals(0L, run("EXISTS fresh"));
    }


    @Test
    public void flagNamesCanBeMembersAfterTheFirstScore() {
        assertEquals(2L, run("ZADD z 1 nx 2 incr"));
        assertEquals("1", run("ZSCORE z nx"));
    }


    @Test
    public void wrongTypeBeatsXx() {
        run("SET s v");
        assertError("WRONGTYPE Operation against a key holding the wrong kind "
            + "of value", "ZADD s XX 1 a");
    }


    @Test
    public void onlyEffectiveZaddsArePropagated() {
        h = new CommandHandler();
        h.setPropagation(true);
        run("ZADD z 1 a");
        run("ZADD z NX 5 a");
        run("ZADD z XX 1 a");
        run("ZADD nokey XX 1 a");
        run("ZADD z GT 0 a");
        run("ZADD z GT INCR 2 a");
        List<List<String>> log = h.drainPropagated();
        assertEquals(2, log.size());
        assertEquals(Arrays.asList("ZADD", "z", "1", "a"), log.get(0));
        assertEquals(Arrays.asList("ZADD", "z", "GT", "INCR", "2", "a"), log
            .get(1));

        // replaying the log rebuilds the same state
        CommandHandler replay = new CommandHandler();
        for (List<String> c : log) {
            replay.execute(c);
        }
        assertEquals("3", replay.execute(Arrays.asList("ZSCORE", "z", "a")));
    }
}
