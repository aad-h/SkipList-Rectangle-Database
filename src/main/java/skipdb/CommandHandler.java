package skipdb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Executes Redis commands against an in-memory keyspace. Each key holds either
 * a String or a {@link SortedSet}. Not thread-safe: the server serializes
 * calls, like Redis's single-threaded command execution.
 *
 * <p>Keys may carry a time to live ({@code EXPIRE}, {@code SET ... EX}, ...).
 * Like Redis, expired keys are removed two ways: lazily, when a command
 * touches one, and actively, by {@link #activeExpireCycle()} sampling random
 * keys with a TTL. Time comes from an injectable millisecond clock.
 *
 * <p>For persistence the handler reports, through {@link #drainPropagated()},
 * the commands that reproduce each change deterministically: relative expiry
 * times become absolute {@code PEXPIREAT}/{@code PXAT}, and every key that
 * expires becomes an explicit {@code DEL}, exactly as Redis feeds its AOF.
 * While {@link #setLoading loading} an AOF, keys never expire (they are
 * reclaimed once loading ends), so replay is independent of the wall clock.
 */
public final class CommandHandler {

    private final Map<String, Object> db = new HashMap<>();
    private final Expires expires = new Expires();
    private final LongSupplier clock;
    private final Random rnd = new Random();
    private boolean loading;
    private boolean propagate;
    private final List<List<String>> propagated = new ArrayList<>();
    /** AOF form of the running command; null = as sent, NONE = nothing */
    private List<String> rewritten;
    private long expiredKeys;

    private static final List<String> NONE = Collections.emptyList();

    /** Keys sampled per round of the active expiry cycle (Redis: 20). */
    static final int EXPIRE_SAMPLE = 20;

    /** Wall-clock budget of one active expiry cycle (Redis: 25% of 100ms). */
    private static final long EXPIRE_BUDGET_NANOS = 25_000_000L;

    /** A handler using the system clock. */
    public CommandHandler() {
        this(System::currentTimeMillis);
    }


    /**
     * @param clock
     *            the current time in Unix milliseconds; tests pass a fake
     */
    public CommandHandler(LongSupplier clock) {
        this.clock = clock;
    }


    /**
     * While loading is on, keys never expire and expiry times in the past are
     * stored rather than deleting the key, so replaying an AOF gives the same
     * keyspace whenever it runs. Expired keys are reclaimed after loading.
     */
    public void setLoading(boolean loading) {
        this.loading = loading;
    }


    /**
     * Turns on recording of the commands to persist (off by default so a
     * handler without an AOF accumulates nothing).
     */
    public void setPropagation(boolean on) {
        propagate = on;
        if (!on) {
            propagated.clear();
        }
    }


    /**
     * @return the commands to append to the AOF since the last call, in
     *         order: successful writes in their deterministic form, plus a
     *         {@code DEL} for every key that expired (lazily or actively)
     */
    public List<List<String>> drainPropagated() {
        if (propagated.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<String>> out = new ArrayList<>(propagated);
        propagated.clear();
        return out;
    }

    /** Reply errors, raised inside commands and turned into -ERR replies. */
    static final class CommandError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CommandError(String msg) {
            super(msg);
        }
    }

    /** Commands that can modify the keyspace and so are written to the AOF. */
    private static final Set<String> WRITE_COMMANDS = Set.of("set", "del",
        "flushall", "flushdb", "zadd", "zincrby", "zrem", "expire", "pexpire",
        "expireat", "pexpireat", "persist");

    /**
     * @param name
     *            a command name, any case
     * @return true if the command may modify data (and must be persisted)
     */
    public static boolean isWriteCommand(String name) {
        return WRITE_COMMANDS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Members per ZADD in a rewritten AOF, like Redis's AOF_REWRITE_ITEMS_PER_CMD. */
    static final int REWRITE_ITEMS_PER_CMD = 64;

    /**
     * Builds the minimal command set that recreates the current keyspace, for
     * an AOF rewrite: one {@code SET} per string and one {@code ZADD} per
     * {@value #REWRITE_ITEMS_PER_CMD} sorted-set members, followed by a
     * {@code PEXPIREAT} for keys with a TTL. Already expired keys are left out.
     * Keys are emitted in sorted order so the output is deterministic. Scores
     * are written so that they parse back to the identical double (including
     * {@code -0}).
     *
     * @return the commands, safe to use after the keyspace changes further
     */
    public List<List<String>> rewriteCommands() {
        List<String> keys = new ArrayList<>(db.keySet());
        Collections.sort(keys);
        List<List<String>> out = new ArrayList<>();
        for (String k : keys) {
            if (isExpired(k)) {
                continue;
            }
            Object v = db.get(k);
            Long when = expires.get(k);
            if (v instanceof String) {
                out.add(Arrays.asList("SET", k, (String)v));
            }
            else {
                rewriteZset(out, k, (SortedSet)v);
            }
            if (when != null) {
                out.add(Arrays.asList("PEXPIREAT", k, Long.toString(when)));
            }
        }
        return out;
    }


    private static void rewriteZset(List<List<String>> out, String k,
        SortedSet z) {
        List<SortedSet.Entry> es = z.range(0, -1, false);
        for (int i = 0; i < es.size(); i += REWRITE_ITEMS_PER_CMD) {
            int end = Math.min(es.size(), i + REWRITE_ITEMS_PER_CMD);
            List<String> cmd = new ArrayList<>(2 + 2 * (end - i));
            cmd.add("ZADD");
            cmd.add(k);
            for (SortedSet.Entry e : es.subList(i, end)) {
                cmd.add(exactScore(e.score));
                cmd.add(e.member);
            }
            out.add(cmd);
        }
    }


    /** Like {@link #fmt} but keeps the sign of negative zero. */
    private static String exactScore(double d) {
        if (d == 0 && 1 / d < 0) {
            return "-0";
        }
        return fmt(d);
    }

    private static final String WRONGTYPE =
        "WRONGTYPE Operation against a key holding the wrong kind of value";

    /**
     * @param args
     *            command name followed by its arguments
     * @return a reply value encodable by {@link Resp#write}
     */
    public Object execute(List<String> args) {
        if (args.isEmpty()) {
            return new Resp.Error("ERR empty command");
        }
        String cmd = args.get(0).toLowerCase(Locale.ROOT);
        rewritten = null;
        Object reply;
        try {
            reply = dispatch(cmd, args);
        }
        catch (CommandError e) {
            return new Resp.Error(e.getMessage());
        }
        catch (NumberFormatException e) {
            return new Resp.Error(cmd.startsWith("z")
                ? "ERR value is not a valid float"
                : "ERR value is not an integer or out of range");
        }
        if (propagate && WRITE_COMMANDS.contains(cmd) && rewritten != NONE) {
            propagated.add(rewritten != null ? rewritten : args);
        }
        return reply;
    }


    /**
     * One run of Redis's active expiry: repeatedly samples
     * {@value #EXPIRE_SAMPLE} random keys with a TTL and deletes the expired
     * ones, going again while more than a quarter of a sample had expired and
     * the 25ms budget is not used up. Called about ten times a second.
     *
     * @return the number of keys deleted
     */
    public int activeExpireCycle() {
        if (loading) {
            return 0;
        }
        long start = System.nanoTime();
        int deleted = 0;
        while (expires.size() > 0) {
            int sample = Math.min(EXPIRE_SAMPLE, expires.size());
            int hit = 0;
            if (sample == expires.size()) {
                // few enough to check them all (random picks could miss one)
                for (String k : expires.keys()) {
                    hit += expireIfNeeded(k) ? 1 : 0;
                }
                deleted += hit;
                break;
            }
            for (int i = 0; i < sample; i++) {
                hit += expireIfNeeded(expires.randomKey(rnd)) ? 1 : 0;
            }
            deleted += hit;
            if (hit * 4 <= sample || System.nanoTime()
                - start > EXPIRE_BUDGET_NANOS) {
                break;
            }
        }
        return deleted;
    }


    /** @return the number of keys that currently have a TTL */
    public int volatileKeys() {
        return expires.size();
    }


    private long now() {
        return clock.getAsLong();
    }


    /** True if {@code key} has a TTL that has passed (never while loading). */
    private boolean isExpired(String key) {
        if (loading) {
            return false;
        }
        Long when = expires.get(key);
        return when != null && now() > when;
    }


    /** Deletes {@code key} if it has expired; @return true if it did. */
    private boolean expireIfNeeded(String key) {
        if (!isExpired(key)) {
            return false;
        }
        db.remove(key);
        expires.remove(key);
        expiredKeys++;
        if (propagate) {
            propagated.add(Arrays.asList("DEL", key));
        }
        return true;
    }


    /** The value of {@code key}, or null if absent or expired (lazy expiry). */
    private Object lookup(String key) {
        Object v = db.get(key);
        if (v != null && expireIfNeeded(key)) {
            return null;
        }
        return v;
    }


    private boolean removeKey(String key) {
        expires.remove(key);
        return db.remove(key) != null;
    }


    private Object dispatch(String cmd, List<String> a) {
        switch (cmd) {
            // ---- connection / server ----
            case "ping":
                arity(cmd, a, 1, 2);
                return a.size() == 2 ? a.get(1) : new Resp.Simple("PONG");
            case "echo":
                arity(cmd, a, 2, 2);
                return a.get(1);
            case "command":
                return new ArrayList<>(); // redis-cli probes this on connect
            case "hello": {
                // client handshake (redis-py, Jedis, Lettuce); only RESP2 here
                if (a.size() > 1 && !a.get(1).equals("2")) {
                    throw new CommandError(
                        "NOPROTO sorry, this protocol version is not supported");
                }
                return java.util.Arrays.asList("server", "skipdb", "version",
                    "0.1.0", "proto", 2L, "id", 1L, "mode", "standalone",
                    "role", "master", "modules", new ArrayList<>());
            }
            case "client":
            case "select":
                // CLIENT SETNAME/SETINFO and SELECT 0 are sent by clients on
                // connect; there is a single database, so accept and ignore
                return Resp.OK;
            case "info":
                return "# Server\r\nredis_version:7.0.0-skipdb\r\nskipdb_version:0.1.0\r\n"
                    + "# Stats\r\nexpired_keys:" + expiredKeys + "\r\n"
                    + "# Keyspace\r\ndb0:keys=" + db.size() + ",expires="
                    + expires.size() + "\r\n";
            case "dbsize":
                arity(cmd, a, 1, 1);
                return (long)db.size();
            case "flushall":
            case "flushdb":
                db.clear();
                expires.clear();
                return Resp.OK;

            // ---- keys / strings ----
            case "set":
                arity(cmd, a, 3, -1);
                return set(a);
            case "get": {
                arity(cmd, a, 2, 2);
                Object v = lookup(a.get(1));
                if (v != null && !(v instanceof String)) {
                    throw new CommandError(WRONGTYPE);
                }
                return v;
            }
            case "del": {
                arity(cmd, a, 2, -1);
                long n = 0;
                for (String k : a.subList(1, a.size())) {
                    n += lookup(k) != null && removeKey(k) ? 1 : 0;
                }
                return n;
            }
            case "exists": {
                arity(cmd, a, 2, -1);
                long n = 0;
                for (String k : a.subList(1, a.size())) {
                    n += lookup(k) != null ? 1 : 0;
                }
                return n;
            }
            case "type": {
                arity(cmd, a, 2, 2);
                Object v = lookup(a.get(1));
                return new Resp.Simple(v == null ? "none"
                    : v instanceof SortedSet ? "zset" : "string");
            }
            case "keys": {
                arity(cmd, a, 2, 2);
                Pattern p = globToRegex(a.get(1));
                List<Object> out = new ArrayList<>();
                for (String k : db.keySet()) {
                    if (p.matcher(k).matches() && !isExpired(k)) {
                        out.add(k);
                    }
                }
                return out;
            }

            // ---- expiry ----
            case "expire":
            case "pexpire":
            case "expireat":
            case "pexpireat":
                arity(cmd, a, 3, -1);
                return expire(cmd, a);
            case "ttl":
            case "pttl":
            case "expiretime":
            case "pexpiretime": {
                arity(cmd, a, 2, 2);
                if (lookup(a.get(1)) == null) {
                    return -2L;
                }
                Long when = expires.get(a.get(1));
                if (when == null) {
                    return -1L;
                }
                long t = cmd.endsWith("time") ? when : Math.max(0, when - now());
                return cmd.startsWith("p") ? t : (t + 500) / 1000;
            }
            case "persist": {
                arity(cmd, a, 2, 2);
                if (lookup(a.get(1)) == null || !expires.remove(a.get(1))) {
                    rewritten = NONE;
                    return 0L;
                }
                return 1L;
            }

            // ---- sorted sets ----
            case "zadd": {
                arity(cmd, a, 4, -1);
                if ((a.size() - 2) % 2 != 0) {
                    throw new CommandError("ERR syntax error");
                }
                // parse everything first so a bad score changes nothing
                List<Double> scores = new ArrayList<>();
                for (int i = 2; i < a.size(); i += 2) {
                    scores.add(SortedSet.parseScore(a.get(i)));
                }
                SortedSet z = zsetOrCreate(a.get(1));
                long added = 0;
                for (int i = 2; i < a.size(); i += 2) {
                    added += z.add(a.get(i + 1), scores.get((i - 2) / 2)) ? 1 : 0;
                }
                return added;
            }
            case "zincrby": {
                arity(cmd, a, 4, 4);
                double d = SortedSet.parseScore(a.get(2));
                SortedSet z = zsetOrCreate(a.get(1));
                try {
                    return fmt(z.incrBy(a.get(3), d));
                }
                catch (IllegalArgumentException e) {
                    throw new CommandError("ERR resulting score is not a number (NaN)");
                }
            }
            case "zscore": {
                arity(cmd, a, 3, 3);
                SortedSet z = zset(a.get(1));
                Double s = z == null ? null : z.score(a.get(2));
                return s == null ? null : fmt(s);
            }
            case "zcard": {
                arity(cmd, a, 2, 2);
                SortedSet z = zset(a.get(1));
                return (long)(z == null ? 0 : z.size());
            }
            case "zrem": {
                arity(cmd, a, 3, -1);
                SortedSet z = zset(a.get(1));
                long n = 0;
                if (z != null) {
                    for (String m : a.subList(2, a.size())) {
                        n += z.remove(m) ? 1 : 0;
                    }
                    if (z.size() == 0) {
                        removeKey(a.get(1));
                    }
                }
                return n;
            }
            case "zrank":
            case "zrevrank": {
                arity(cmd, a, 3, 3);
                SortedSet z = zset(a.get(1));
                int r = z == null ? -1 : z.rank(a.get(2), cmd.equals("zrevrank"));
                return r < 0 ? null : (Object)(long)r;
            }
            case "zrange":
            case "zrevrange": {
                arity(cmd, a, 4, 5);
                boolean withScores = flag(a, 4, "withscores");
                long start = Long.parseLong(a.get(2));
                long stop = Long.parseLong(a.get(3));
                SortedSet z = zset(a.get(1));
                return z == null ? new ArrayList<>()
                    : entries(z.range(start, stop, cmd.equals("zrevrange")),
                        withScores);
            }
            case "zrangebyscore": {
                arity(cmd, a, 4, 8);
                SortedSet.Bound min = SortedSet.Bound.parse(a.get(2));
                SortedSet.Bound max = SortedSet.Bound.parse(a.get(3));
                boolean withScores = false;
                long offset = 0;
                long limit = -1;
                for (int i = 4; i < a.size(); i++) {
                    String opt = a.get(i).toLowerCase(Locale.ROOT);
                    if (opt.equals("withscores")) {
                        withScores = true;
                    }
                    else if (opt.equals("limit") && i + 2 < a.size()) {
                        offset = Long.parseLong(a.get(++i));
                        limit = Long.parseLong(a.get(++i));
                    }
                    else {
                        throw new CommandError("ERR syntax error");
                    }
                }
                if (offset < 0) {
                    return new ArrayList<>();
                }
                SortedSet z = zset(a.get(1));
                return z == null ? new ArrayList<>()
                    : entries(z.rangeByScore(min, max, offset, limit),
                        withScores);
            }
            case "zcount": {
                arity(cmd, a, 4, 4);
                SortedSet.Bound min = SortedSet.Bound.parse(a.get(2));
                SortedSet.Bound max = SortedSet.Bound.parse(a.get(3));
                SortedSet z = zset(a.get(1));
                return (long)(z == null ? 0 : z.count(min, max));
            }
            default:
                throw new CommandError("ERR unknown command '" + cmd + "'");
        }
    }


    private static void arity(String cmd, List<String> a, int min, int max) {
        if (a.size() < min || (max >= 0 && a.size() > max)) {
            throw new CommandError("ERR wrong number of arguments for '" + cmd
                + "' command");
        }
    }


    private static boolean flag(List<String> a, int i, String name) {
        if (a.size() <= i) {
            return false;
        }
        if (!a.get(i).equalsIgnoreCase(name)) {
            throw new CommandError("ERR syntax error");
        }
        return true;
    }


    /** SET key value [NX|XX] [GET] [EX s|PX ms|EXAT s|PXAT ms|KEEPTTL] */
    private Object set(List<String> a) {
        String key = a.get(1);
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        String unit = null; // ex, px, exat or pxat
        String amount = null;
        for (int i = 3; i < a.size(); i++) {
            String opt = a.get(i).toLowerCase(Locale.ROOT);
            switch (opt) {
                case "nx":
                    nx = true;
                    break;
                case "xx":
                    xx = true;
                    break;
                case "get":
                    get = true;
                    break;
                case "keepttl":
                    keepTtl = true;
                    break;
                case "ex":
                case "px":
                case "exat":
                case "pxat":
                    if (unit != null || i + 1 >= a.size()) {
                        throw new CommandError("ERR syntax error");
                    }
                    unit = opt;
                    amount = a.get(++i);
                    break;
                default:
                    throw new CommandError("ERR syntax error");
            }
        }
        if ((nx && xx) || (keepTtl && unit != null)) {
            throw new CommandError("ERR syntax error");
        }
        Long when = null;
        if (unit != null) {
            long n = Long.parseLong(amount);
            boolean secs = unit.startsWith("e");
            if (n <= 0 || (secs && n > Long.MAX_VALUE / 1000)) {
                throw new CommandError(
                    "ERR invalid expire time in 'set' command");
            }
            long ms = secs ? n * 1000 : n;
            if (!unit.endsWith("at")) {
                if (ms > Long.MAX_VALUE - now()) {
                    throw new CommandError(
                        "ERR invalid expire time in 'set' command");
                }
                ms += now();
            }
            when = ms;
        }
        Object old = lookup(key);
        if (get && old != null && !(old instanceof String)) {
            throw new CommandError(WRONGTYPE);
        }
        if ((nx && old != null) || (xx && old == null)) {
            rewritten = NONE;
            return get ? old : null;
        }
        db.put(key, a.get(2));
        List<String> aof = new ArrayList<>(Arrays.asList("SET", key, a.get(2)));
        if (when != null) {
            if (when <= now() && !loading) {
                removeKey(key); // already in the past, like Redis 7
                rewritten = Arrays.asList("DEL", key);
                return get ? old : Resp.OK;
            }
            expires.set(key, when);
            aof.add("PXAT");
            aof.add(Long.toString(when));
        }
        else if (keepTtl) {
            aof.add("KEEPTTL");
        }
        else {
            expires.remove(key);
        }
        rewritten = aof;
        return get ? old : Resp.OK;
    }


    /** EXPIRE / PEXPIRE / EXPIREAT / PEXPIREAT key time [NX|XX|GT|LT] */
    private Object expire(String cmd, List<String> a) {
        long n = Long.parseLong(a.get(2));
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        for (String opt : a.subList(3, a.size())) {
            switch (opt.toLowerCase(Locale.ROOT)) {
                case "nx":
                    nx = true;
                    break;
                case "xx":
                    xx = true;
                    break;
                case "gt":
                    gt = true;
                    break;
                case "lt":
                    lt = true;
                    break;
                default:
                    throw new CommandError("ERR Unsupported option " + opt);
            }
        }
        if (nx && (xx || gt || lt)) {
            throw new CommandError(
                "ERR NX and XX, GT or LT options at the same time are not compatible");
        }
        if (gt && lt) {
            throw new CommandError(
                "ERR GT and LT options at the same time are not compatible");
        }
        long when;
        try {
            when = cmd.startsWith("p") ? n : Math.multiplyExact(n, 1000L);
            if (!cmd.endsWith("at")) {
                when = Math.addExact(when, now());
            }
        }
        catch (ArithmeticException e) {
            throw new CommandError("ERR invalid expire time in '" + cmd
                + "' command");
        }
        String key = a.get(1);
        rewritten = NONE;
        if (lookup(key) == null) {
            return 0L;
        }
        Long cur = expires.get(key);
        // a key without a TTL counts as an infinite TTL for GT and LT
        if ((nx && cur != null) || (xx && cur == null) || (gt && (cur == null
            || when <= cur)) || (lt && cur != null && when >= cur)) {
            return 0L;
        }
        if (when <= now() && !loading) {
            removeKey(key);
            rewritten = Arrays.asList("DEL", key);
            return 1L;
        }
        expires.set(key, when);
        rewritten = Arrays.asList("PEXPIREAT", key, Long.toString(when));
        return 1L;
    }


    private SortedSet zset(String key) {
        Object v = lookup(key);
        if (v != null && !(v instanceof SortedSet)) {
            throw new CommandError(WRONGTYPE);
        }
        return (SortedSet)v;
    }


    private SortedSet zsetOrCreate(String key) {
        SortedSet z = zset(key);
        if (z == null) {
            z = new SortedSet();
            db.put(key, z);
        }
        return z;
    }


    private static List<Object> entries(List<SortedSet.Entry> es,
        boolean withScores) {
        List<Object> out = new ArrayList<>(es.size() * (withScores ? 2 : 1));
        for (SortedSet.Entry e : es) {
            out.add(e.member);
            if (withScores) {
                out.add(fmt(e.score));
            }
        }
        return out;
    }


    /** Formats a score like Redis: integers without ".0", inf as "inf". */
    static String fmt(double d) {
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e17) {
            return Long.toString((long)d);
        }
        return Double.toString(d);
    }


    /** Converts a Redis glob (* ? [abc]) to a regex. */
    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*':
                    re.append(".*");
                    break;
                case '?':
                    re.append('.');
                    break;
                case '[':
                case ']':
                    re.append(c);
                    break;
                default:
                    re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        try {
            return Pattern.compile(re.toString(), Pattern.DOTALL);
        }
        catch (java.util.regex.PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(glob));
        }
    }
}
