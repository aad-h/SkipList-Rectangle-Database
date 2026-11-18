package skipdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A Redis-style sorted set: a hash map for O(1) member -> score lookup plus a
 * {@link RankedSkipList} for ordered and rank-based queries.
 */
public final class SortedSet {

    /** A member and its score, as returned by range queries. */
    public static final class Entry {
        public final String member;
        public final double score;

        Entry(String member, double score) {
            this.member = member;
            this.score = score;
        }


        @Override
        public String toString() {
            return member + "=" + score;
        }
    }

    /** A score bound like Redis's "5", "(5", "-inf", "+inf". */
    public static final class Bound {
        public final double value;
        public final boolean exclusive;

        public Bound(double value, boolean exclusive) {
            this.value = value;
            this.exclusive = exclusive;
        }


        /**
         * @throws NumberFormatException
         *             if the text is not a valid bound
         */
        public static Bound parse(String s) {
            boolean ex = s.startsWith("(");
            double v = parseScore(ex ? s.substring(1) : s);
            return new Bound(v, ex);
        }
    }

    private final Map<String, Double> scores = new HashMap<>();
    private final RankedSkipList list;

    public SortedSet() {
        this(new Random());
    }


    public SortedSet(Random rng) {
        list = new RankedSkipList(rng);
    }


    /** Parses a score the way Redis does, accepting inf/-inf/+inf. */
    public static double parseScore(String s) {
        String t = s.toLowerCase();
        if (t.equals("inf") || t.equals("+inf")) {
            return Double.POSITIVE_INFINITY;
        }
        if (t.equals("-inf")) {
            return Double.NEGATIVE_INFINITY;
        }
        double d = Double.parseDouble(s);
        if (Double.isNaN(d)) {
            throw new NumberFormatException("NaN");
        }
        return d;
    }


    public int size() {
        return scores.size();
    }


    /**
     * Adds a member or updates its score.
     *
     * @return true if the member is new
     */
    public boolean add(String member, double score) {
        Double old = scores.put(member, score);
        if (old != null) {
            if (old == score) {
                return false;
            }
            list.delete(old, member);
        }
        list.insert(score, member);
        return old == null;
    }


    /** @return the new score */
    public double incrBy(String member, double delta) {
        Double old = scores.get(member);
        double next = (old == null ? 0 : old) + delta;
        if (Double.isNaN(next)) {
            throw new IllegalArgumentException("resulting score is not a number");
        }
        add(member, next);
        return next;
    }


    /** @return the score, or null if absent */
    public Double score(String member) {
        return scores.get(member);
    }


    /** @return true if the member was present */
    public boolean remove(String member) {
        Double old = scores.remove(member);
        if (old == null) {
            return false;
        }
        list.delete(old, member);
        return true;
    }


    /**
     * @return 0-based rank (ascending, or descending if reverse), or -1
     */
    public int rank(String member, boolean reverse) {
        Double s = scores.get(member);
        if (s == null) {
            return -1;
        }
        int r = list.rank(s, member); // 1-based ascending
        return reverse ? size() - r : r - 1;
    }


    /**
     * Elements by rank, with Redis semantics: inclusive, negative indexes count
     * from the end, out-of-range is clamped.
     */
    public List<Entry> range(long start, long stop, boolean reverse) {
        int n = size();
        if (start < 0) {
            start += n;
        }
        if (stop < 0) {
            stop += n;
        }
        start = Math.max(start, 0);
        stop = Math.min(stop, n - 1L);
        List<Entry> out = new ArrayList<>();
        if (n == 0 || start > stop) {
            return out;
        }
        int count = (int)(stop - start + 1);
        RankedSkipList.Node node = reverse
            ? list.byRank((int)(n - start))
            : list.byRank((int)(start + 1));
        for (int i = 0; i < count && node != null; i++) {
            out.add(new Entry(node.member(), node.score()));
            node = reverse ? node.prev() : node.next();
        }
        return out;
    }


    /**
     * Elements with min <= score <= max (bounds may be exclusive).
     *
     * @param offset
     *            entries to skip
     * @param limit
     *            max entries, or -1 for all
     */
    public List<Entry> rangeByScore(Bound min, Bound max, long offset,
        long limit) {
        List<Entry> out = new ArrayList<>();
        RankedSkipList.Node node = list.firstAtLeast(min.value, min.exclusive);
        for (; node != null && offset > 0 && withinMax(node.score(), max);
            offset--) {
            node = node.next();
        }
        while (node != null && withinMax(node.score(), max) && (limit < 0
            || out.size() < limit)) {
            out.add(new Entry(node.member(), node.score()));
            node = node.next();
        }
        return out;
    }


    /** @return number of elements with score in [min, max] */
    public int count(Bound min, Bound max) {
        RankedSkipList.Node lo = list.firstAtLeast(min.value, min.exclusive);
        RankedSkipList.Node hi = list.lastAtMost(max.value, max.exclusive);
        if (lo == null || hi == null) {
            return 0;
        }
        int rLo = list.rank(lo.score(), lo.member());
        int rHi = list.rank(hi.score(), hi.member());
        return Math.max(0, rHi - rLo + 1);
    }


    private static boolean withinMax(double score, Bound max) {
        return max.exclusive ? score < max.value : score <= max.value;
    }


    RankedSkipList skipList() {
        return list;
    }
}
