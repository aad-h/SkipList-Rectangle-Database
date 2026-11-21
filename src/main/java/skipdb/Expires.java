package skipdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The set of keys with a time to live, mapping each to its absolute expiry
 * time in Unix milliseconds (Redis's {@code expires} dict). Besides O(1)
 * get/set/remove it supports O(1) uniform random sampling, which the active
 * expiry sweep needs: keys live in a dense array, and removal swaps the last
 * key into the hole.
 */
final class Expires {

    private final Map<String, Integer> slot = new HashMap<>();
    private final List<String> keys = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();

    /** @return the expiry time of {@code key}, or null if it has no TTL */
    Long get(String key) {
        Integer i = slot.get(key);
        return i == null ? null : times.get(i);
    }


    /** Sets (or replaces) the absolute expiry time of {@code key}. */
    void set(String key, long whenMs) {
        Integer i = slot.get(key);
        if (i != null) {
            times.set(i, whenMs);
            return;
        }
        slot.put(key, keys.size());
        keys.add(key);
        times.add(whenMs);
    }


    /** Removes the TTL of {@code key}; @return true if it had one. */
    boolean remove(String key) {
        Integer i = slot.remove(key);
        if (i == null) {
            return false;
        }
        int last = keys.size() - 1;
        if (i != last) {
            String moved = keys.get(last);
            keys.set(i, moved);
            times.set(i, times.get(last));
            slot.put(moved, i);
        }
        keys.remove(last);
        times.remove(last);
        return true;
    }


    /** @return a uniformly random key with a TTL; the set must be non-empty */
    String randomKey(Random rnd) {
        return keys.get(rnd.nextInt(keys.size()));
    }


    /** @return a copy of the keys with a TTL */
    List<String> keys() {
        return new ArrayList<>(keys);
    }


    int size() {
        return keys.size();
    }


    void clear() {
        slot.clear();
        keys.clear();
        times.clear();
    }
}
