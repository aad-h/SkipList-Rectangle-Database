package skipdb.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Micro-benchmark of {@link skipdb.RankedSkipList} against {@code TreeMap} and
 * {@code ConcurrentSkipListMap} for the four operations a sorted set lives on:
 * insert ({@code ZADD}), rank ({@code ZRANK}), range by rank
 * ({@code ZRANGE start start+9}) and range by score
 * ({@code ZRANGEBYSCORE min +inf LIMIT 0 10}).
 *
 * <p>For each size the same seeded data set (distinct members, integer scores
 * with ties) is loaded into every structure. A trial builds the structure from
 * empty (timing every insert) and then runs a fixed batch of random queries
 * for each read operation. Untimed warmup trials come first so the JIT has
 * compiled the hot paths; recorded trials then rotate the order of the
 * structures to spread out drift. Results are nanoseconds per operation, with
 * the median over trials reported. Each read batch stops early once it has
 * run for {@code --budget-ms}: the JDK maps answer rank queries in O(rank),
 * so 1,000 of them on a million entries would otherwise take minutes. The CSV
 * records how many operations each figure is based on.
 *
 * <pre>
 * java -cp target/classes skipdb.bench.Bench --sizes 10000,100000,1000000 \
 *     --trials 5 --warmup 2 --queries 1000 --budget-ms 500 --csv bench.csv
 * </pre>
 */
public final class Bench {

    /** Operations measured, in output order. */
    public static final List<String> OPS = List.of("insert", "rank", "range",
        "rangebyscore");

    /** Members returned per range query (ZRANGE start start+9). */
    public static final int RANGE_COUNT = 10;

    /** Defeats dead-code elimination of query results. */
    static volatile long sink;

    /** Benchmark settings; see {@link #parse(String[])}. */
    public static final class Config {
        public int[] sizes = { 10_000, 100_000, 1_000_000 };
        public int trials = 5;
        public int warmup = 2;
        public int queries = 1000;
        public int budgetMs = 500;
        public long seed = 42;
        public String csv;
        public List<String> impls = List.of("skiplist", "treemap", "cslm");
    }

    /** One measurement: one operation on one structure in one trial. */
    public static final class Result {
        public final String impl;
        public final String op;
        public final int size;
        public final int trial;
        /** Operations timed (fewer than --queries if the budget ran out). */
        public final int ops;
        public final double nsPerOp;

        Result(
            String impl,
            String op,
            int size,
            int trial,
            int ops,
            double nsPerOp) {
            this.impl = impl;
            this.op = op;
            this.size = size;
            this.trial = trial;
            this.ops = ops;
            this.nsPerOp = nsPerOp;
        }
    }

    private Bench() {
    }


    /**
     * Parses command-line flags.
     *
     * @throws IllegalArgumentException
     *             on an unknown flag, a missing value or a bad number
     */
    public static Config parse(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for "
                    + flag);
            }
            String v = args[++i];
            switch (flag) {
                case "--sizes":
                    c.sizes = Arrays.stream(v.split(",")).mapToInt(
                        s -> positive(flag, s.trim().replace("_", "")))
                        .toArray();
                    break;
                case "--trials":
                    c.trials = positive(flag, v);
                    break;
                case "--warmup":
                    c.warmup = nonNegative(flag, v);
                    break;
                case "--queries":
                    c.queries = positive(flag, v);
                    break;
                case "--budget-ms":
                    c.budgetMs = positive(flag, v);
                    break;
                case "--seed":
                    try {
                        c.seed = Long.parseLong(v);
                    }
                    catch (NumberFormatException e) {
                        throw new IllegalArgumentException("bad value for "
                            + flag + ": " + v);
                    }
                    break;
                case "--csv":
                    c.csv = v;
                    break;
                case "--impls":
                    List<String> impls = new ArrayList<>();
                    for (String s : v.split(",")) {
                        String name = s.trim().toLowerCase(Locale.ROOT);
                        factory(name, new Random(0)); // validates
                        impls.add(name);
                    }
                    c.impls = impls;
                    break;
                default:
                    throw new IllegalArgumentException("unknown flag: "
                        + flag);
            }
        }
        return c;
    }


    private static int positive(String flag, String v) {
        int n = nonNegative(flag, v);
        if (n == 0) {
            throw new IllegalArgumentException(flag + " must be positive");
        }
        return n;
    }


    private static int nonNegative(String flag, String v) {
        try {
            int n = Integer.parseInt(v);
            if (n < 0) {
                throw new IllegalArgumentException(flag
                    + " must not be negative");
            }
            return n;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad value for " + flag + ": "
                + v);
        }
    }


    /**
     * @param name
     *            {@code skiplist}, {@code treemap} or {@code cslm}
     * @return a supplier of fresh, empty indexes of that kind
     */
    public static Supplier<SortedIndex> factory(String name, Random rng) {
        switch (name) {
            case "skiplist":
                return () -> new SkipListIndex(rng);
            case "treemap":
                return NavigableMapIndex::treeMap;
            case "cslm":
                return NavigableMapIndex::concurrentSkipListMap;
            default:
                throw new IllegalArgumentException("unknown impl: " + name
                    + " (expected skiplist, treemap or cslm)");
        }
    }


    /** The seeded data set and query mix for one size. */
    static final class Workload {
        final double[] scores;
        final String[] members;
        final int[] rankTargets;
        final int[] rangeStarts;
        final double[] scoreMins;

        Workload(int n, int queries, long seed) {
            Random r = new Random(seed ^ n);
            scores = new double[n];
            members = new String[n];
            for (int i = 0; i < n; i++) {
                scores[i] = r.nextInt(n); // many ties, like a leaderboard
                members[i] = "member:" + i;
            }
            rankTargets = new int[queries];
            rangeStarts = new int[queries];
            scoreMins = new double[queries];
            for (int q = 0; q < queries; q++) {
                rankTargets[q] = r.nextInt(n);
                rangeStarts[q] = r.nextInt(n);
                scoreMins[q] = r.nextInt(n);
            }
        }
    }


    /** Query batches check the clock once per this many queries. */
    static final int CLOCK_EVERY = 8;

    /**
     * Runs one trial on a fresh index. Every insert is timed; each read batch
     * runs its queries in order until all are done or the budget has passed
     * (checked every {@link #CLOCK_EVERY} queries), so an O(n) operation at a
     * large size times a prefix of the batch instead of running for minutes.
     *
     * @param budgetNanos
     *            time limit per read batch
     * @param ns
     *            receives ns/op for each of {@link #OPS}, in order
     * @param ops
     *            receives the number of operations timed for each
     */
    static void trial(
        SortedIndex idx,
        Workload w,
        long budgetNanos,
        double[] ns,
        int[] ops) {
        int n = w.scores.length;
        int q = w.rankTargets.length;
        String[] out = new String[RANGE_COUNT];
        long acc = 0;

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            idx.insert(w.scores[i], w.members[i]);
        }
        ns[0] = (double)(System.nanoTime() - t0) / n;
        ops[0] = n;

        t0 = System.nanoTime();
        long deadline = t0 + budgetNanos;
        int i = 0;
        while (i < q) {
            int j = w.rankTargets[i++];
            acc += idx.rank(w.scores[j], w.members[j]);
            if (i % CLOCK_EVERY == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        ns[1] = (double)(System.nanoTime() - t0) / i;
        ops[1] = i;

        t0 = System.nanoTime();
        deadline = t0 + budgetNanos;
        i = 0;
        while (i < q) {
            acc += idx.range(w.rangeStarts[i++], out);
            if (i % CLOCK_EVERY == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        ns[2] = (double)(System.nanoTime() - t0) / i;
        ops[2] = i;

        t0 = System.nanoTime();
        deadline = t0 + budgetNanos;
        i = 0;
        while (i < q) {
            acc += idx.rangeByScore(w.scoreMins[i++], out);
            if (i % CLOCK_EVERY == 0 && System.nanoTime() > deadline) {
                break;
            }
        }
        ns[3] = (double)(System.nanoTime() - t0) / i;
        ops[3] = i;

        sink += acc + idx.size();
    }


    /**
     * Runs the whole benchmark.
     *
     * @param progress
     *            receives one line per size, or null for silence
     * @return every recorded (non-warmup) measurement
     */
    public static List<Result> run(Config c, PrintStream progress) {
        List<Result> results = new ArrayList<>();
        Random levels = new Random(c.seed);
        List<Supplier<SortedIndex>> makers = new ArrayList<>();
        for (String name : c.impls) {
            makers.add(factory(name, levels));
        }
        for (int n : c.sizes) {
            if (progress != null) {
                progress.printf("size %,d: %d warmup + %d trials%n", n,
                    c.warmup, c.trials);
            }
            Workload w = new Workload(n, c.queries, c.seed);
            int m = makers.size();
            long budget = c.budgetMs * 1_000_000L;
            for (int t = 0; t < c.warmup + c.trials; t++) {
                String[] names = new String[m];
                double[][] ns = new double[m][OPS.size()];
                int[][] ops = new int[m][OPS.size()];
                for (int k = 0; k < m; k++) {
                    // rotate who goes first so no structure always runs on a
                    // cold cache or right after another one's garbage
                    int j = (k + t) % m;
                    System.gc();
                    SortedIndex idx = makers.get(j).get();
                    names[j] = idx.name();
                    trial(idx, w, budget, ns[j], ops[j]);
                }
                if (t < c.warmup) {
                    continue;
                }
                for (int j = 0; j < m; j++) { // record in --impls order
                    for (int o = 0; o < OPS.size(); o++) {
                        results.add(new Result(names[j], OPS.get(o), n, t
                            - c.warmup + 1, ops[j][o], ns[j][o]));
                    }
                }
            }
        }
        return results;
    }


    /** @return the median of the values (mean of the middle two if even) */
    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int m = s.length / 2;
        return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2;
    }


    /** Writes every measurement as CSV with a header row. */
    public static void writeCsv(List<Result> results, Appendable out)
        throws IOException {
        out.append("impl,op,size,trial,ops,ns_per_op\n");
        for (Result r : results) {
            out.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.1f%n",
                r.impl, r.op, r.size, r.trial, r.ops, r.nsPerOp));
        }
    }


    /**
     * @return a Markdown table of median ns/op: one row per (op, size), one
     *         column per structure, in first-seen order
     */
    public static String table(List<Result> results) {
        Map<String, Map<String, List<Double>>> rows = new LinkedHashMap<>();
        List<String> impls = new ArrayList<>();
        for (String op : OPS) {
            for (Result r : results) {
                if (!r.op.equals(op)) {
                    continue;
                }
                if (!impls.contains(r.impl)) {
                    impls.add(r.impl);
                }
                String key = String.format(Locale.ROOT, "%s | %,d", r.op,
                    r.size);
                rows.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(r.impl, k -> new ArrayList<>()).add(
                        r.nsPerOp);
            }
        }
        StringBuilder sb = new StringBuilder("| op | n |");
        StringBuilder rule = new StringBuilder("|---|--:|");
        for (String impl : impls) {
            sb.append(' ').append(impl).append(" |");
            rule.append("--:|");
        }
        sb.append('\n').append(rule).append('\n');
        for (Map.Entry<String, Map<String, List<Double>>> row : rows
            .entrySet()) {
            sb.append("| ").append(row.getKey()).append(" |");
            for (String impl : impls) {
                List<Double> v = row.getValue().get(impl);
                sb.append(' ').append(v == null ? "-" : format(median(v
                    .stream().mapToDouble(Double::doubleValue).toArray())))
                    .append(" |");
            }
            sb.append('\n');
        }
        return sb.toString();
    }


    /** Formats ns/op with sensible precision and a unit. */
    static String format(double ns) {
        if (ns >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2f ms", ns / 1_000_000);
        }
        if (ns >= 10_000) {
            return String.format(Locale.ROOT, "%.1f us", ns / 1_000);
        }
        return String.format(Locale.ROOT, "%.0f ns", ns);
    }


    /**
     * Entry point; prints a Markdown table of median ns/op to stdout and, with
     * {@code --csv path}, every trial to a CSV file.
     */
    public static void main(String[] args) throws IOException {
        Config c;
        try {
            c = parse(args);
        }
        catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("usage: Bench [--sizes 10000,100000,1000000]"
                + " [--trials 5] [--warmup 2] [--queries 1000]"
                + " [--budget-ms 500] [--seed 42]"
                + " [--impls skiplist,treemap,cslm] [--csv out.csv]");
            System.exit(1);
            return;
        }
        System.err.printf("Java %s, %d CPUs, max heap %d MB%n", System
            .getProperty("java.version"), Runtime.getRuntime()
                .availableProcessors(), Runtime.getRuntime().maxMemory()
                    >> 20);
        List<Result> results = run(c, System.err);
        if (c.csv != null) {
            try (Writer w = Files.newBufferedWriter(Paths.get(c.csv),
                StandardCharsets.UTF_8)) {
                writeCsv(results, w);
            }
            System.err.println("wrote " + results.size() + " rows to "
                + c.csv);
        }
        System.out.print(table(results));
    }
}
