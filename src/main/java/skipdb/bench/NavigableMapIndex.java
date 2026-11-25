package skipdb.bench;

import java.util.Iterator;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * {@link SortedIndex} over a JDK {@link NavigableMap} keyed by
 * (score, member). Point and score-range operations are O(log n), but the JDK
 * maps keep no subtree sizes or spans, so rank ({@code headMap(k).size()}) and
 * select-by-rank (iterating to the offset) cost O(rank).
 */
public final class NavigableMapIndex implements SortedIndex {

    /** A (score, member) key ordered like a sorted set. */
    static final class Key implements Comparable<Key> {
        final double score;
        final String member;

        Key(double score, String member) {
            this.score = score;
            this.member = member;
        }


        @Override
        public int compareTo(Key o) {
            if (score < o.score) {
                return -1;
            }
            if (score > o.score) {
                return 1;
            }
            return member.compareTo(o.member);
        }
    }

    private final String name;
    private final NavigableMap<Key, Boolean> map;

    private NavigableMapIndex(String name, NavigableMap<Key, Boolean> map) {
        this.name = name;
        this.map = map;
    }


    /** @return an index backed by a red-black {@link TreeMap} */
    public static NavigableMapIndex treeMap() {
        return new NavigableMapIndex("TreeMap", new TreeMap<>());
    }


    /** @return an index backed by a {@link ConcurrentSkipListMap} */
    public static NavigableMapIndex concurrentSkipListMap() {
        return new NavigableMapIndex("ConcurrentSkipListMap",
            new ConcurrentSkipListMap<>());
    }


    @Override
    public String name() {
        return name;
    }


    @Override
    public void insert(double score, String member) {
        map.put(new Key(score, member), Boolean.TRUE);
    }


    @Override
    public int size() {
        return map.size();
    }


    @Override
    public int rank(double score, String member) {
        Key k = new Key(score, member);
        return map.containsKey(k) ? map.headMap(k, false).size() : -1;
    }


    @Override
    public int range(int start, String[] out) {
        if (start < 0 || start >= map.size()) {
            return 0;
        }
        Iterator<Key> it = map.keySet().iterator();
        for (int i = 0; i < start; i++) {
            it.next();
        }
        int n = 0;
        while (n < out.length && it.hasNext()) {
            out[n++] = it.next().member;
        }
        return n;
    }


    @Override
    public int rangeByScore(double min, String[] out) {
        // "" sorts before every member, so this is the first key with score
        // >= min
        Iterator<Key> it = map.tailMap(new Key(min, ""), true).keySet()
            .iterator();
        int n = 0;
        while (n < out.length && it.hasNext()) {
            out[n++] = it.next().member;
        }
        return n;
    }
}
