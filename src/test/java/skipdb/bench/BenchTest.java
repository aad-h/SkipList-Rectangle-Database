package skipdb.bench;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The three adapters must answer every query identically (otherwise the
 * benchmark compares different work), and the harness must parse flags,
 * aggregate trials and format output correctly.
 */
public class BenchTest {

    private static List<SortedIndex> all() {
        return List.of(new SkipListIndex(new Random(7)), NavigableMapIndex
            .treeMap(), NavigableMapIndex.concurrentSkipListMap());
    }


    @Test
    public void adaptersMatchSortedModel() {
        Random r = new Random(3);
        int n = 2000;
        double[] scores = new double[n];
        String[] members = new String[n];
        Integer[] order = new Integer[n];
        List<SortedIndex> idxs = all();
        for (int i = 0; i < n; i++) {
            scores[i] = r.nextInt(300) - 150; // heavy ties, negatives
            members[i] = "m" + r.nextInt(1_000_000) + "_" + i;
            order[i] = i;
            for (SortedIndex idx : idxs) {
                idx.insert(scores[i], members[i]);
            }
        }
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> scores[i])
            .thenComparing(i -> members[i]));

        for (SortedIndex idx : idxs) {
            String who = idx.name();
            assertEquals(who, n, idx.size());
            for (int k = 0; k < n; k++) {
                int i = order[k];
                assertEquals(who, k, idx.rank(scores[i], members[i]));
            }
            assertEquals(who, -1, idx.rank(0, "absent"));
            String[] out = new String[Bench.RANGE_COUNT];
            for (int start : new int[] { 0, 1, 777, n - 10, n - 3, n - 1, n,
                n + 5, -1 }) {
                int got = idx.range(start, out);
                int want = (start < 0 || start >= n) ? 0
                    : Math.min(out.length, n - start);
                assertEquals(who + " start " + start, want, got);
                for (int j = 0; j < got; j++) {
                    assertEquals(who, members[order[start + j]], out[j]);
                }
            }
            for (double min : new double[] { -1000, -150, -3, 0, 0.5, 149,
                150, 1000 }) {
                int first = 0;
                while (first < n && scores[order[first]] < min) {
                    first++;
                }
                int got = idx.rangeByScore(min, out);
                assertEquals(who + " min " + min, Math.min(out.length, n
                    - first), got);
                for (int j = 0; j < got; j++) {
                    assertEquals(who, members[order[first + j]], out[j]);
                }
            }
        }
    }


    @Test
    public void emptyIndexes() {
        String[] out = new String[3];
        for (SortedIndex idx : all()) {
            assertEquals(0, idx.size());
            assertEquals(-1, idx.rank(1, "a"));
            assertEquals(0, idx.range(0, out));
            assertEquals(0, idx.rangeByScore(Double.NEGATIVE_INFINITY, out));
        }
    }


    @Test
    public void parseDefaultsAndFlags() {
        Bench.Config d = Bench.parse(new String[0]);
        assertArrayEquals(new int[] { 10_000, 100_000, 1_000_000 }, d.sizes);
        assertEquals(5, d.trials);
        assertEquals(2, d.warmup);
        assertEquals(1000, d.queries);
        assertEquals(500, d.budgetMs);
        assertEquals(List.of("skiplist", "treemap", "cslm"), d.impls);

        Bench.Config c = Bench.parse(new String[] { "--sizes",
            "1_000, 50", "--trials", "3", "--warmup", "0", "--queries", "7",
            "--budget-ms", "3", "--seed", "-9", "--impls", "TreeMap,skiplist",
            "--csv", "x.csv" });
        assertArrayEquals(new int[] { 1000, 50 }, c.sizes);
        assertEquals(3, c.trials);
        assertEquals(0, c.warmup);
        assertEquals(7, c.queries);
        assertEquals(3, c.budgetMs);
        assertEquals(-9, c.seed);
        assertEquals(List.of("treemap", "skiplist"), c.impls);
        assertEquals("x.csv", c.csv);
    }


    @Test
    public void parseRejectsBadInput() {
        String[][] bad = { { "--trials" }, { "--trials", "0" }, { "--warmup",
            "-1" }, { "--sizes", "10,x" }, { "--sizes", "0" }, { "--seed",
                "1.5" }, { "--impls", "hashmap" }, { "--bogus", "1" } };
        for (String[] args : bad) {
            try {
                Bench.parse(args);
                fail("accepted " + Arrays.toString(args));
            }
            catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }


    @Test
    public void runRecordsEveryTrialAndFormats() throws Exception {
        Bench.Config c = Bench.parse(new String[] { "--sizes", "200,500",
            "--trials", "3", "--warmup", "1", "--queries", "50" });
        List<Bench.Result> rs = Bench.run(c, null);
        // 2 sizes x 3 trials x 3 structures x 4 ops; warmup not recorded
        assertEquals(2 * 3 * 3 * 4, rs.size());
        for (Bench.Result r : rs) {
            assertTrue(r.nsPerOp > 0);
            // 50 tiny queries never exhaust a 500 ms budget
            assertEquals(r.op.equals("insert") ? r.size : 50, r.ops);
            assertTrue(r.trial >= 1 && r.trial <= 3);
            assertTrue(Bench.OPS.contains(r.op));
        }

        StringBuilder csv = new StringBuilder();
        Bench.writeCsv(rs, csv);
        String[] lines = csv.toString().split("\n");
        assertEquals("impl,op,size,trial,ops,ns_per_op", lines[0]);
        assertEquals(rs.size() + 1, lines.length);
        assertTrue(lines[1], lines[1].matches(
            "RankedSkipList,insert,200,1,200,\\d+\\.\\d"));

        String[] table = Bench.table(rs).split("\n");
        assertEquals("| op | n | RankedSkipList | TreeMap |"
            + " ConcurrentSkipListMap |", table[0]);
        assertEquals("|---|--:|--:|--:|--:|", table[1]);
        assertEquals(2 + 4 * 2, table.length);
        assertTrue(table[2], table[2].startsWith("| insert | 200 | "));
        assertTrue(table[9], table[9].startsWith("| rangebyscore | 500 | "));
    }


    @Test
    public void budgetCutsReadBatchesShort() {
        Bench.Workload w = new Bench.Workload(300, 100, 1);
        double[] ns = new double[4];
        int[] ops = new int[4];
        // an already-spent budget stops each read batch at the first clock
        // check; inserts are never cut short
        Bench.trial(NavigableMapIndex.treeMap(), w, 0, ns, ops);
        assertArrayEquals(new int[] { 300, Bench.CLOCK_EVERY,
            Bench.CLOCK_EVERY, Bench.CLOCK_EVERY }, ops);
        Bench.trial(new SkipListIndex(new Random(1)), w, Long.MAX_VALUE / 2,
            ns, ops);
        assertArrayEquals(new int[] { 300, 100, 100, 100 }, ops);
        for (double v : ns) {
            assertTrue(v > 0);
        }
    }


    @Test
    public void tableUsesMedianAndUnits() {
        List<Bench.Result> rs = new ArrayList<>();
        double[] ns = { 900, 50, 120 }; // median 120, not mean
        for (int t = 0; t < ns.length; t++) {
            rs.add(new Bench.Result("A", "rank", 1_000_000, t + 1, 10,
                ns[t]));
            rs.add(new Bench.Result("B", "rank", 1_000_000, t + 1, 10,
                ns[t] * 1000));
        }
        String[] table = Bench.table(rs).split("\n");
        assertEquals("| rank | 1,000,000 | 120 ns | 120.0 us |", table[2]);
        assertEquals(120.0, Bench.median(ns), 0);
        assertEquals(2.5, Bench.median(new double[] { 4, 1, 2, 3 }), 0);
        assertEquals("1.50 ms", Bench.format(1_500_000));
        assertEquals("9999 ns", Bench.format(9999));
    }
}
