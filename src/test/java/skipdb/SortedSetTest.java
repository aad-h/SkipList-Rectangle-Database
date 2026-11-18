package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

public class SortedSetTest {

    private SortedSet z;

    @Before
    public void setUp() {
        z = new SortedSet(new Random(1));
        z.add("alice", 50);
        z.add("bob", 30);
        z.add("carol", 70);
        z.add("dave", 30);
    }


    private static List<String> names(List<SortedSet.Entry> es) {
        return es.stream().map(e -> e.member).collect(Collectors.toList());
    }


    @Test
    public void addReportsNewAndUpdatesScore() {
        assertFalse(z.add("alice", 50));
        assertFalse(z.add("alice", 90)); // update, not new
        assertTrue(z.add("erin", 10));
        assertEquals(90.0, z.score("alice"), 0);
        assertEquals(5, z.size());
        assertEquals(List.of("erin", "bob", "dave", "carol", "alice"), names(z
            .range(0, -1, false)));
    }


    @Test
    public void ranks() {
        assertEquals(0, z.rank("bob", false));
        assertEquals(1, z.rank("dave", false));
        assertEquals(0, z.rank("carol", true));
        assertEquals(-1, z.rank("nobody", false));
    }


    @Test
    public void rangeUsesRedisIndexing() {
        assertEquals(List.of("carol", "alice"), names(z.range(0, 1, true)));
        assertEquals(List.of("alice", "carol"), names(z.range(-2, -1, false)));
        assertEquals(List.of("bob", "dave", "alice", "carol"), names(z.range(
            -100, 100, false)));
        assertTrue(z.range(3, 1, false).isEmpty());
        assertTrue(new SortedSet().range(0, -1, false).isEmpty());
    }


    @Test
    public void rangeByScoreAndCount() {
        SortedSet.Bound lo = SortedSet.Bound.parse("30");
        SortedSet.Bound hi = SortedSet.Bound.parse("(70");
        assertEquals(List.of("bob", "dave", "alice"), names(z.rangeByScore(lo,
            hi, 0, -1)));
        assertEquals(List.of("dave"), names(z.rangeByScore(lo, hi, 1, 1)));
        assertEquals(3, z.count(lo, hi));
        assertEquals(4, z.count(SortedSet.Bound.parse("-inf"), SortedSet.Bound
            .parse("+inf")));
        assertEquals(0, z.count(SortedSet.Bound.parse("(70"), SortedSet.Bound
            .parse("+inf")));
    }


    @Test
    public void incrAndRemove() {
        assertEquals(55.0, z.incrBy("alice", 5), 0);
        assertEquals(2.5, z.incrBy("new", 2.5), 0);
        assertTrue(z.remove("bob"));
        assertFalse(z.remove("bob"));
        assertNull(z.score("bob"));
        assertEquals(List.of("new", "dave", "alice", "carol"), names(z.range(0,
            -1, false)));
    }


    @Test(expected = NumberFormatException.class)
    public void rejectsNaN() {
        SortedSet.parseScore("nan");
    }
}
