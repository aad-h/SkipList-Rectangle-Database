package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.Test;

/**
 * Checks the span bookkeeping against a TreeSet model under random workloads.
 */
public class RankedSkipListTest {

    /** (score, member) pair for the reference model. */
    private static final class E {
        final double s;
        final String m;

        E(double s, String m) {
            this.s = s;
            this.m = m;
        }
    }

    private static final Comparator<E> ORDER = Comparator.<E>comparingDouble(
        e -> e.s).thenComparing(e -> e.m);

    @Test
    public void emptyList() {
        RankedSkipList l = new RankedSkipList(new Random(1));
        assertEquals(0, l.size());
        assertNull(l.byRank(1));
        assertEquals(0, l.rank(1, "a"));
        assertFalse(l.delete(1, "a"));
        assertNull(l.first());
        assertNull(l.last());
    }


    @Test
    public void tiesOrderByMember() {
        RankedSkipList l = new RankedSkipList(new Random(1));
        l.insert(1, "b");
        l.insert(1, "a");
        l.insert(0, "z");
        assertEquals(List.of("z", "a", "b"), l.members());
        assertEquals(2, l.rank(1, "a"));
        assertEquals("b", l.byRank(3).member());
    }


    @Test
    public void randomOpsMatchTreeSet() {
        Random r = new Random(42);
        RankedSkipList l = new RankedSkipList(new Random(7));
        TreeSet<E> model = new TreeSet<>(ORDER);
        for (int op = 0; op < 20_000; op++) {
            double s = r.nextInt(200);
            String m = "m" + r.nextInt(300);
            E e = new E(s, m);
            if (r.nextInt(3) == 0) {
                assertEquals(model.remove(e), l.delete(s, m));
            }
            else if (!model.contains(e)) {
                model.add(e);
                l.insert(s, m);
            }
            assertEquals(model.size(), l.size());

            if (op % 500 == 0 && !model.isEmpty()) {
                List<E> sorted = new ArrayList<>(model);
                for (int i = 0; i < sorted.size(); i++) {
                    E x = sorted.get(i);
                    assertEquals("rank of " + x.m, i + 1, l.rank(x.s, x.m));
                    assertEquals(x.m, l.byRank(i + 1).member());
                }
                assertEquals(sorted.get(sorted.size() - 1).m, l.last()
                    .member());
                // backward links walk the list in reverse
                int i = sorted.size() - 1;
                for (RankedSkipList.Node n = l.last(); n != null; n = n
                    .prev()) {
                    assertEquals(sorted.get(i--).m, n.member());
                }
                assertEquals(-1, i);
            }
        }
    }


    @Test
    public void scoreBoundsLookup() {
        RankedSkipList l = new RankedSkipList(new Random(3));
        for (int i = 0; i < 10; i++) {
            l.insert(i, "m" + i);
        }
        assertEquals("m3", l.firstAtLeast(3, false).member());
        assertEquals("m4", l.firstAtLeast(3, true).member());
        assertEquals("m7", l.lastAtMost(7, false).member());
        assertEquals("m6", l.lastAtMost(7, true).member());
        assertNull(l.firstAtLeast(10, false));
        assertNull(l.lastAtMost(0, true));
        assertTrue(l.randomLevel() <= RankedSkipList.MAX_LEVEL);
    }
}
