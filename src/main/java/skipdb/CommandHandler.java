package skipdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Executes Redis commands against an in-memory keyspace. Each key holds either
 * a String or a {@link SortedSet}. Not thread-safe: the server serializes
 * calls, like Redis's single-threaded command execution.
 */
public final class CommandHandler {

    private final Map<String, Object> db = new HashMap<>();

    /** Reply errors, raised inside commands and turned into -ERR replies. */
    static final class CommandError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CommandError(String msg) {
            super(msg);
        }
    }

    /** Commands that can modify the keyspace and so are written to the AOF. */
    private static final Set<String> WRITE_COMMANDS = Set.of("set", "del",
        "flushall", "flushdb", "zadd", "zincrby", "zrem");

    /**
     * @param name
     *            a command name, any case
     * @return true if the command may modify data (and must be persisted)
     */
    public static boolean isWriteCommand(String name) {
        return WRITE_COMMANDS.contains(name.toLowerCase(Locale.ROOT));
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
        try {
            return dispatch(cmd, args);
        }
        catch (CommandError e) {
            return new Resp.Error(e.getMessage());
        }
        catch (NumberFormatException e) {
            return new Resp.Error(cmd.startsWith("z")
                ? "ERR value is not a valid float"
                : "ERR value is not an integer or out of range");
        }
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
                    + "# Keyspace\r\ndb0:keys=" + db.size() + "\r\n";
            case "dbsize":
                arity(cmd, a, 1, 1);
                return (long)db.size();
            case "flushall":
            case "flushdb":
                db.clear();
                return Resp.OK;

            // ---- keys / strings ----
            case "set":
                arity(cmd, a, 3, 3);
                db.put(a.get(1), a.get(2));
                return Resp.OK;
            case "get": {
                arity(cmd, a, 2, 2);
                Object v = db.get(a.get(1));
                if (v != null && !(v instanceof String)) {
                    throw new CommandError(WRONGTYPE);
                }
                return v;
            }
            case "del": {
                arity(cmd, a, 2, -1);
                long n = 0;
                for (String k : a.subList(1, a.size())) {
                    n += db.remove(k) != null ? 1 : 0;
                }
                return n;
            }
            case "exists": {
                arity(cmd, a, 2, -1);
                long n = 0;
                for (String k : a.subList(1, a.size())) {
                    n += db.containsKey(k) ? 1 : 0;
                }
                return n;
            }
            case "type": {
                arity(cmd, a, 2, 2);
                Object v = db.get(a.get(1));
                return new Resp.Simple(v == null ? "none"
                    : v instanceof SortedSet ? "zset" : "string");
            }
            case "keys": {
                arity(cmd, a, 2, 2);
                Pattern p = globToRegex(a.get(1));
                List<Object> out = new ArrayList<>();
                for (String k : db.keySet()) {
                    if (p.matcher(k).matches()) {
                        out.add(k);
                    }
                }
                return out;
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
                        db.remove(a.get(1));
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


    private SortedSet zset(String key) {
        Object v = db.get(key);
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
