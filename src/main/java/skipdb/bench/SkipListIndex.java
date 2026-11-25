package skipdb.bench;

import java.util.Random;
import skipdb.RankedSkipList;

/**
 * {@link SortedIndex} over SkipDB's span-augmented {@link RankedSkipList}:
 * rank and select-by-rank are expected O(log n).
 */
public final class SkipListIndex implements SortedIndex {
    private final RankedSkipList list;

    /**
     * @param rng
     *            source of node levels (seeded for reproducible runs)
     */
    public SkipListIndex(Random rng) {
        list = new RankedSkipList(rng);
    }


    @Override
    public String name() {
        return "RankedSkipList";
    }


    @Override
    public void insert(double score, String member) {
        list.insert(score, member);
    }


    @Override
    public int size() {
        return list.size();
    }


    @Override
    public int rank(double score, String member) {
        return list.rank(score, member) - 1;
    }


    @Override
    public int range(int start, String[] out) {
        int n = 0;
        for (RankedSkipList.Node x = list.byRank(start + 1); x != null
            && n < out.length; x = x.next()) {
            out[n++] = x.member();
        }
        return n;
    }


    @Override
    public int rangeByScore(double min, String[] out) {
        int n = 0;
        for (RankedSkipList.Node x = list.firstAtLeast(min, false); x != null
            && n < out.length; x = x.next()) {
            out[n++] = x.member();
        }
        return n;
    }
}
