package skipdb.bench;

/**
 * The operations the benchmark measures, over a set of distinct
 * (score, member) entries ordered by score, then member, exactly like a Redis
 * sorted set. Each implementation adapts one data structure.
 */
public interface SortedIndex {

    /** @return a short name for tables and CSV output */
    String name();


    /**
     * Inserts an entry. Callers never insert the same (score, member) twice.
     */
    void insert(double score, String member);


    /** @return the number of entries */
    int size();


    /**
     * @return the 0-based rank of (score, member), like {@code ZRANK}, or -1
     *         if absent
     */
    int rank(double score, String member);


    /**
     * Copies up to {@code out.length} members starting at 0-based rank
     * {@code start} into {@code out}, like {@code ZRANGE key start stop}.
     *
     * @return how many members were copied
     */
    int range(int start, String[] out);


    /**
     * Copies up to {@code out.length} members whose score is at least
     * {@code min} into {@code out}, in order, like
     * {@code ZRANGEBYSCORE key min +inf LIMIT 0 count}.
     *
     * @return how many members were copied
     */
    int rangeByScore(double min, String[] out);
}
