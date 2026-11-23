package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/**
 * ZPOPMIN, ZPOPMAX, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZMSCORE and
 * ZRANDMEMBER. Expected replies follow Redis 7.0 (RESP2).
 */
public class ZsetCommandsTest {

    private CommandHandler h;

    private Object run(String line) {
        return h.execute(Arrays.asList(line.split(" ")));
    }


    private void assertError(String expected, String line) {
        Object r = run(line);
        assertTrue(line + " -> " + r, r instanceof Resp.Error);
        assertEquals(expected, ((Resp.Error)r).message);
    }


    private static List<Object> list(Object... xs) {
        return Arrays.asList(xs);
    }


    @Before
    public void setUp() {
        h = new CommandHandler();
        run("ZADD z 1 a 2 b 3 c 4 d 5 e");
    }


    @Test
    public void popMinAndMax() {
        assertEquals(list("a", "1"), run("ZPOPMIN z"));
        assertEquals(list("e", "5", "d", "4"), run("ZPOPMAX z 2"));
        assertEquals(list(), run("ZPOPMIN z 0"));
        assertEquals(list("b", "2", "c", "3"), run("ZPOPMIN z 10"));
        assertEquals(0L, run("EXISTS z")); // emptied key is deleted
        assertEquals(list(), run("ZPOPMIN z"));
        assertEquals(list(), run("ZPOPMAX missing 3"));
    }


    @Test
    public void popErrors() {
        assertError("ERR value is out of range, must be positive",
            "ZPOPMIN z -1");
        assertError("ERR value is out of range, must be positive",
            "ZPOPMIN z x");
        assertError("ERR syntax error", "ZPOPMAX z 1 2");
        assertError("ERR wrong number of arguments for 'zpopmin' command",
            "ZPOPMIN");
        run("SET s v");
        assertError("ERR value is out of range, must be positive",
            "ZPOPMIN s -1");
        assertTrue(run("ZPOPMIN s") instanceof Resp.Error);
        assertEquals(5L, run("ZCARD z"));
    }


    @Test
    public void removeRangeByRank() {
        assertEquals(2L, run("ZREMRANGEBYRANK z 0 1"));
        assertEquals(list("c", "d", "e"), run("ZRANGE z 0 -1"));
        assertEquals(1L, run("ZREMRANGEBYRANK z -1 -1"));
        assertEquals(0L, run("ZREMRANGEBYRANK z 5 10"));
        assertEquals(0L, run("ZREMRANGEBYRANK z 1 0"));
        assertEquals(2L, run("ZREMRANGEBYRANK z -100 100"));
        assertEquals(0L, run("EXISTS z"));
        assertEquals(0L, run("ZREMRANGEBYRANK z 0 -1"));
        assertError("ERR value is not an integer or out of range",
            "ZREMRANGEBYRANK z a 1");
    }


    @Test
    public void removeRangeByScore() {
        assertEquals(2L, run("ZREMRANGEBYSCORE z (1 3"));
        assertEquals(list("a", "d", "e"), run("ZRANGE z 0 -1"));
        assertEquals(0L, run("ZREMRANGEBYSCORE z 10 +inf"));
        assertEquals(3L, run("ZREMRANGEBYSCORE z -inf +inf"));
        assertEquals(0L, run("EXISTS z"));
        assertError("ERR min or max is not a float",
            "ZREMRANGEBYSCORE z x 1");
        assertError("ERR min or max is not a float", "ZCOUNT z 1 (y");
        assertError("ERR min or max is not a float", "ZRANGEBYSCORE z a b");
        assertError("ERR value is not an integer or out of range",
            "ZRANGE z 0 x");
    }


    @Test
    public void mscore() {
        assertEquals(list("1", null, "5"), run("ZMSCORE z a nope e"));
        assertEquals(list(null, null), run("ZMSCORE missing a b"));
        assertError("ERR wrong number of arguments for 'zmscore' command",
            "ZMSCORE z");
        run("SET s v");
        assertTrue(run("ZMSCORE s a") instanceof Resp.Error);
    }


    @Test
    public void randMemberSingle() {
        Set<Object> members = new HashSet<>(list("a", "b", "c", "d", "e"));
        for (int i = 0; i < 50; i++) {
            assertTrue(members.contains(run("ZRANDMEMBER z")));
        }
        assertNull(run("ZRANDMEMBER missing"));
        assertEquals(list(), run("ZRANDMEMBER missing 3"));
        assertEquals(list(), run("ZRANDMEMBER z 0"));
    }


    @Test
    public void randMemberPositiveCountIsDistinct() {
        for (int c = 1; c <= 7; c++) {
            @SuppressWarnings("unchecked")
            List<Object> r = (List<Object>)run("ZRANDMEMBER z " + c);
            assertEquals(Math.min(c, 5), r.size());
            assertEquals(r.size(), new HashSet<>(r).size());
        }
        // sparse sampling path (count * 3 <= size)
        for (int i = 0; i < 100; i++) {
            run("ZADD big " + i + " m" + i);
        }
        @SuppressWarnings("unchecked")
        List<Object> r = (List<Object>)run("ZRANDMEMBER big 10 WITHSCORES");
        assertEquals(20, r.size());
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < r.size(); i += 2) {
            assertTrue(seen.add(r.get(i)));
            assertEquals("m" + r.get(i + 1), r.get(i));
        }
    }


    @Test
    public void randMemberNegativeCountRepeats() {
        @SuppressWarnings("unchecked")
        List<Object> r = (List<Object>)run("ZRANDMEMBER z -20 withscores");
        assertEquals(40, r.size());
        run("ZADD one 7 x");
        assertEquals(list("x", "x", "x"), run("ZRANDMEMBER one -3"));
        assertError("ERR syntax error", "ZRANDMEMBER z 2 nope");
        assertError("ERR syntax error", "ZRANDMEMBER z 2 WITHSCORES x");
        assertError("ERR value is not an integer or out of range",
            "ZRANDMEMBER z x");
        assertError("ERR value is out of range",
            "ZRANDMEMBER z -9223372036854775807 WITHSCORES");
        assertError("ERR value is out of range, value must between "
            + "-9223372036854775807 and 9223372036854775807",
            "ZRANDMEMBER z -9223372036854775808");
    }


    @Test
    public void writesArePropagatedDeterministically() {
        h.setPropagation(true);
        run("ZPOPMIN z 2");
        run("ZPOPMIN missing");
        run("ZREMRANGEBYSCORE z 100 200"); // removes nothing
        run("ZREMRANGEBYRANK z 0 0");
        run("ZRANDMEMBER z");
        run("ZMSCORE z a");
        List<List<String>> aof = h.drainPropagated();
        assertEquals(Arrays.asList(Arrays.asList("ZREM", "z", "a", "b"),
            Arrays.asList("ZREMRANGEBYRANK", "z", "0", "0")), aof);
        assertTrue(CommandHandler.isWriteCommand("zpopmax"));
        assertTrue(!CommandHandler.isWriteCommand("zrandmember"));
    }


    @Test
    public void popMatchesSortedOrderRandomized() {
        java.util.Random rng = new java.util.Random(42);
        java.util.TreeMap<String, Double> ref = new java.util.TreeMap<>();
        SortedSet s = new SortedSet(rng);
        for (int i = 0; i < 500; i++) {
            String m = "m" + rng.nextInt(200);
            double sc = rng.nextInt(50);
            s.add(m, sc);
            ref.put(m, sc);
        }
        List<String> expected = new ArrayList<>(ref.keySet());
        expected.sort((x, y) -> ref.get(x).equals(ref.get(y)) ? x.compareTo(y)
            : Double.compare(ref.get(x), ref.get(y)));
        List<String> popped = new ArrayList<>();
        while (s.size() > 0) {
            boolean max = rng.nextBoolean();
            for (SortedSet.Entry e : s.pop(1 + rng.nextInt(5), max)) {
                assertEquals(max ? expected.remove(expected.size() - 1)
                    : expected.remove(0), e.member);
                popped.add(e.member);
            }
        }
        assertTrue(expected.isEmpty());
        assertEquals(ref.size(), popped.size());
    }
}
