package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Key expiry: TTL commands, SET options, lazy and active expiry, AOF form. */
public class ExpiryTest {

    private static final long T0 = 1_700_000_000_000L;

    private long now;
    private CommandHandler h;

    private Object run(String line) {
        return Resp.toResp2(h.execute(cmd(line)));
    }


    private static List<String> cmd(String line) {
        return Arrays.asList(line.split(" "));
    }


    private static void assertError(String prefix, Object reply) {
        assertTrue(String.valueOf(reply), reply instanceof Resp.Error
            && ((Resp.Error)reply).message.startsWith(prefix));
    }


    @Before
    public void setUp() {
        now = T0;
        h = new CommandHandler(() -> now);
    }


    // ---- EXPIRE / TTL / PERSIST ----

    @Test
    public void expireTtlAndLazyDeletion() {
        assertEquals(-2L, run("TTL k"));
        assertEquals(0L, run("EXPIRE k 10"));
        run("SET k v");
        assertEquals(-1L, run("TTL k"));
        assertEquals(-1L, run("PTTL k"));
        assertEquals(1L, run("EXPIRE k 10"));
        assertEquals(10L, run("TTL k"));
        assertEquals(10_000L, run("PTTL k"));
        now += 4_600; // 5400ms left rounds to 5s, like Redis
        assertEquals(5L, run("TTL k"));
        assertEquals(5_400L, run("PTTL k"));
        now += 5_400; // exactly at the deadline: still alive
        assertEquals("v", run("GET k"));
        assertEquals(0L, run("PTTL k"));
        now += 1;
        assertNull(run("GET k"));
        assertEquals(-2L, run("TTL k"));
        assertEquals(0L, run("EXISTS k"));
        assertEquals(0L, run("DBSIZE"));
        assertEquals(0, h.volatileKeys());
    }


    @Test
    public void persistAndOverwritesClearTheTtl() {
        run("SET k v");
        assertEquals(0L, run("PERSIST k"));
        assertEquals(0L, run("PERSIST nokey"));
        run("EXPIRE k 100");
        assertEquals(1L, run("PERSIST k"));
        assertEquals(-1L, run("TTL k"));

        run("EXPIRE k 100");
        run("SET k v2"); // plain SET drops the TTL
        assertEquals(-1L, run("TTL k"));
        run("EXPIRE k 100");
        run("SET k v3 KEEPTTL");
        assertEquals(100L, run("TTL k"));
        run("DEL k");
        run("SET k v4");
        assertEquals(-1L, run("TTL k"));
        assertError("ERR wrong number", run("PERSIST"));
        assertError("ERR wrong number", run("TTL a b"));
    }


    @Test
    public void expireInThePastDeletesImmediately() {
        run("SET a 1");
        run("SET b 1");
        assertEquals(1L, run("EXPIRE a -5"));
        assertEquals(1L, run("PEXPIREAT b " + (T0 - 1)));
        assertEquals(0L, run("DBSIZE"));
    }


    @Test
    public void absoluteFormsAndExpireTime() {
        run("SET k v");
        assertEquals(-1L, run("EXPIRETIME k"));
        assertEquals(-2L, run("PEXPIRETIME nokey"));
        assertEquals(1L, run("EXPIREAT k " + (T0 / 1000 + 60)));
        assertEquals(60L, run("TTL k"));
        assertEquals(T0 / 1000 + 60, run("EXPIRETIME k"));
        assertEquals(1L, run("PEXPIREAT k " + (T0 + 1_234)));
        assertEquals(T0 + 1_234, run("PEXPIRETIME k"));
        assertEquals(1_234L, run("PTTL k"));
        assertEquals(1L, run("PEXPIRE k 250"));
        assertEquals(250L, run("PTTL k"));
        assertEquals(0L, run("TTL k")); // 250ms rounds down
    }


    @Test
    public void expireOptionsFollowRedisRules() {
        run("SET k v");
        assertEquals(0L, run("EXPIRE k 100 XX")); // no TTL yet
        assertEquals(0L, run("EXPIRE k 100 GT")); // no TTL = infinite
        assertEquals(1L, run("EXPIRE k 100 LT"));
        assertEquals(0L, run("EXPIRE k 50 NX"));
        assertEquals(1L, run("EXPIRE k 200 xx"));
        assertEquals(0L, run("EXPIRE k 150 GT"));
        assertEquals(1L, run("EXPIRE k 300 GT"));
        assertEquals(0L, run("EXPIRE k 300 LT"));
        assertEquals(1L, run("EXPIRE k 20 LT"));
        assertEquals(20L, run("TTL k"));
        assertEquals(1L, run("EXPIRE k 40 XX GT"));
        assertEquals(40L, run("TTL k"));

        assertError("ERR NX and XX, GT or LT options at the same time are not compatible",
            run("EXPIRE k 1 NX XX"));
        assertError("ERR NX and XX, GT or LT options at the same time are not compatible",
            run("EXPIRE k 1 NX GT"));
        assertError("ERR GT and LT options at the same time are not compatible",
            run("EXPIRE k 1 GT LT"));
        assertError("ERR Unsupported option FOO", run("EXPIRE k 1 FOO"));
        assertError("ERR value is not an integer or out of range", run(
            "EXPIRE k 1.5"));
        assertError("ERR invalid expire time in 'expire' command", run(
            "EXPIRE k 9223372036854775807"));
        assertError("ERR invalid expire time in 'pexpire' command", run(
            "PEXPIRE k 9223372036854775807"));
        assertError("ERR wrong number", run("EXPIRE k"));
        assertEquals(40L, run("TTL k")); // errors changed nothing
    }


    // ---- SET options ----

    @Test
    public void setWithExpiry() {
        assertEquals(Resp.OK, run("SET a v EX 10"));
        assertEquals(10_000L, run("PTTL a"));
        assertEquals(Resp.OK, run("SET b v px 1500"));
        assertEquals(1_500L, run("PTTL b"));
        assertEquals(Resp.OK, run("SET c v EXAT " + (T0 / 1000 + 30)));
        assertEquals(30L, run("TTL c"));
        assertEquals(Resp.OK, run("SET d v PXAT " + (T0 + 99)));
        assertEquals(99L, run("PTTL d"));
        assertEquals(Resp.OK, run("SET e v PXAT " + (T0 - 1)));
        assertEquals(0L, run("EXISTS e")); // already in the past
        now += 1_500;
        assertEquals("v", run("GET b")); // expired only once past the deadline
        now += 1;
        assertNull(run("GET b"));
        assertEquals("v", run("GET a"));
    }


    @Test
    public void setOptionErrors() {
        String bad = "ERR invalid expire time in 'set' command";
        assertError(bad, run("SET k v EX 0"));
        assertError(bad, run("SET k v PX -1"));
        assertError(bad, run("SET k v EX 9223372036854775807"));
        assertError(bad, run("SET k v PX 9223372036854775807"));
        assertError("ERR value is not an integer or out of range", run(
            "SET k v EX ten"));
        assertError("ERR syntax error", run("SET k v EX"));
        assertError("ERR syntax error", run("SET k v EX 1 PX 1"));
        assertError("ERR syntax error", run("SET k v KEEPTTL EX 1"));
        assertError("ERR syntax error", run("SET k v NX XX"));
        assertError("ERR syntax error", run("SET k v BOGUS"));
        assertError("ERR wrong number", run("SET k"));
        assertEquals(0L, run("EXISTS k"));
    }


    @Test
    public void setNxXxAndGet() {
        assertNull(run("SET k v1 XX"));
        assertEquals(0L, run("EXISTS k"));
        assertEquals(Resp.OK, run("SET k v1 NX"));
        assertNull(run("SET k v2 NX"));
        assertEquals("v1", run("GET k"));
        assertEquals(Resp.OK, run("SET k v2 XX"));
        assertEquals("v2", run("SET k v3 GET"));
        assertNull(run("SET fresh v GET"));
        assertEquals("v3", run("SET k v4 NX GET")); // not set, old value back
        assertEquals("v3", run("GET k"));
        run("ZADD z 1 a");
        assertError("WRONGTYPE", run("SET z v GET"));
        assertEquals("zset", ((Resp.Simple)run("TYPE z")).text);
        // NX succeeds once the old key has expired: a simple lock
        assertEquals(Resp.OK, run("SET lock me NX PX 100"));
        assertNull(run("SET lock you NX PX 100"));
        now += 101;
        assertEquals(Resp.OK, run("SET lock you NX PX 100"));
        assertEquals("you", run("GET lock"));
    }


    // ---- sorted sets and other commands ----

    @Test
    public void sortedSetsExpireAndKeepTtlOnUpdate() {
        run("ZADD z 1 a 2 b");
        run("PEXPIRE z 1000");
        run("ZADD z 3 c");
        run("ZINCRBY z 1 a");
        assertEquals(1_000L, run("PTTL z")); // writes keep the TTL
        run("ZADD keys 1 x");
        run("SET s v PX 10");
        now += 500;
        assertEquals(3L, run("ZCARD z"));
        assertEquals(2L, run("ZRANK z c"));
        now += 501;
        assertEquals(0L, run("ZCARD z"));
        assertEquals(List.of(), run("ZRANGE z 0 -1"));
        assertEquals("none", ((Resp.Simple)run("TYPE z")).text);
        assertEquals(List.of("keys"), run("KEYS *")); // skips expired s
        assertEquals(1L, run("ZADD z 9 fresh"));
        assertEquals(-1L, run("TTL z")); // a brand new key
        run("EXPIRE z 100");
        run("ZREM z fresh"); // emptied set is deleted with its TTL
        assertEquals(-2L, run("TTL z"));
        assertEquals(0L, run("EXISTS z s"));
    }


    @Test
    public void zremOfLastMemberDropsTheTtl() {
        run("ZADD z 1 a");
        run("EXPIRE z 100");
        assertEquals(1, h.volatileKeys());
        run("ZREM z a");
        assertEquals(0, h.volatileKeys());
        run("SET k v EX 5");
        run("FLUSHALL");
        assertEquals(0, h.volatileKeys());
        run("SET k v");
        assertEquals(-1L, run("TTL k"));
    }


    @Test
    public void infoReportsExpires() {
        run("SET a v EX 1");
        run("SET b v");
        assertTrue(((String)run("INFO")).contains("db0:keys=2,expires=1"));
        now += 1_001;
        run("GET a");
        assertTrue(((String)run("INFO")).contains("expired_keys:1"));
    }


    // ---- active expiry ----

    @Test
    public void activeCycleReclaimsKeysNobodyTouches() {
        for (int i = 0; i < 1000; i++) {
            run("SET tmp" + i + " v PX 100");
        }
        for (int i = 0; i < 10; i++) {
            run("SET live" + i + " v EX 3600");
            run("SET perm" + i + " v");
        }
        assertEquals(0, h.activeExpireCycle()); // nothing due yet
        now += 101;
        int deleted = 0;
        for (int i = 0; i < 1000 && h.volatileKeys() > 10; i++) {
            deleted += h.activeExpireCycle();
        }
        // stops once a sample is mostly unexpired, so a few may linger
        assertTrue(String.valueOf(h.volatileKeys()), h.volatileKeys() < 60);
        assertEquals(1000 - (h.volatileKeys() - 10), deleted);
        assertTrue((long)run("DBSIZE") < 80);
        for (int i = 0; i < 10; i++) {
            assertEquals("v", run("GET live" + i));
            assertEquals("v", run("GET perm" + i));
        }
    }


    @Test
    public void oneCycleClearsAFullyExpiredKeyspace() {
        for (int i = 0; i < 500; i++) {
            run("SET k" + i + " v PX 5");
        }
        now += 6;
        assertEquals(500, h.activeExpireCycle());
        assertEquals(0L, run("DBSIZE"));
    }


    @Test
    public void expiresIndexSamplesUniformlyAndSwapsOnRemove() {
        Expires e = new Expires();
        for (int i = 0; i < 5; i++) {
            e.set("k" + i, i);
        }
        e.set("k2", 42);
        assertEquals(5, e.size());
        assertEquals(Long.valueOf(42), e.get("k2"));
        assertTrue(e.remove("k0"));
        assertFalse(e.remove("k0"));
        assertNull(e.get("k0"));
        assertEquals(Long.valueOf(4), e.get("k4")); // swapped into slot 0
        Set<String> seen = new HashSet<>();
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            seen.add(e.randomKey(rnd));
        }
        assertEquals(Set.of("k1", "k2", "k3", "k4"), seen);
        e.clear();
        assertEquals(0, e.size());
    }


    // ---- persistence form ----

    @Test
    public void propagationUsesAbsoluteTimesAndExplicitDeletes() {
        assertEquals(List.of(), h.drainPropagated()); // off by default
        run("SET a v EX 10");
        assertEquals(List.of(), h.drainPropagated());

        h.setPropagation(true);
        run("SET k v EX 10");
        run("SET k v GET NX PX 5"); // not applied: nothing to log
        run("SET j v KEEPTTL");
        run("EXPIRE k 5");
        run("EXPIRE k 1 NX"); // not applied
        run("EXPIRE nokey 5");
        run("PERSIST nokey");
        run("PERSIST k");
        run("EXPIRE k 1 FOO"); // error
        run("GET k");
        assertEquals(List.of(
            List.of("SET", "k", "v", "PXAT", "" + (T0 + 10_000)),
            List.of("SET", "j", "v", "KEEPTTL"),
            List.of("PEXPIREAT", "k", "" + (T0 + 5_000)),
            cmd("PERSIST k")), h.drainPropagated());

        run("PEXPIRE k 10");
        run("EXPIRE j -1"); // deleted now, so logged as DEL
        assertEquals(List.of(List.of("PEXPIREAT", "k", "" + (T0 + 10)), cmd(
            "DEL j")), h.drainPropagated());
        now += 11;
        run("ZADD k 1 m"); // k expired: logged as DEL before the ZADD
        assertEquals(List.of(cmd("DEL k"), cmd("ZADD k 1 m")), h
            .drainPropagated());
        run("SET a2 v PX 1");
        h.drainPropagated();
        now += 2;
        assertEquals(1, h.activeExpireCycle());
        assertEquals(List.of(cmd("DEL a2")), h.drainPropagated());
    }


    @Test
    public void loadingNeverExpires() {
        h.setLoading(true);
        run("SET k v PXAT " + (T0 - 1000));
        run("PEXPIREAT k " + (T0 - 500));
        assertEquals("v", run("GET k"));
        assertEquals(0, h.activeExpireCycle());
        h.setLoading(false);
        assertNull(run("GET k"));
    }


    @Test
    public void rewriteEmitsAbsoluteExpiryAndSkipsExpiredKeys() {
        run("SET s v PX 5000");
        run("ZADD z 1 a");
        run("PEXPIRE z 2000");
        run("SET gone v PX 10");
        run("SET plain v");
        now += 11;
        assertEquals(List.of(cmd("SET plain v"), cmd("SET s v"), List.of(
            "PEXPIREAT", "s", "" + (T0 + 5000)), cmd("ZADD z 1 a"), List.of(
                "PEXPIREAT", "z", "" + (T0 + 2000))), h.rewriteCommands());
    }


    /**
     * The keyspace rebuilt from the AOF must match what clients saw, however
     * much later it is replayed: a write that only succeeded because a key
     * had expired must still succeed on replay.
     */
    @Test
    public void aofReplayIsIndependentOfWhenItRuns() throws IOException {
        Path dir = Files.createTempDirectory("skipdb-expiry");
        Path file = dir.resolve("appendonly.aof");
        try {
            Aof aof = new Aof(file, Aof.Fsync.NO);
            aof.load(h);
            h.setPropagation(true);
            String[] script = {"SET t str PX 1000", "ZADD z 1 a",
                "PEXPIRE z 1000", "+500", "ZADD z 2 b", "+600", "ZADD t 1 x",
                "ZADD z 3 c", "SET keep v EX 3600", "SET short v EX 60"};
            for (String line : script) {
                if (line.startsWith("+")) {
                    now += Long.parseLong(line.substring(1));
                }
                else {
                    assertFalse(line, run(line) instanceof Resp.Error);
                    aof.appendTransaction(h.drainPropagated());
                }
            }
            aof.close();
            for (long later : new long[] {0, 30_000, 120_000}) {
                long replayAt = now + later;
                CommandHandler copy = new CommandHandler(() -> replayAt);
                Aof again = new Aof(file, Aof.Fsync.NO);
                again.load(copy);
                again.close();
                assertEquals(List.of("x"), copy.execute(cmd("ZRANGE t 0 -1")));
                assertEquals(List.of("c"), copy.execute(cmd("ZRANGE z 0 -1")));
                assertEquals(-1L, copy.execute(cmd("TTL z")));
                assertEquals("v", copy.execute(cmd("GET keep")));
                assertEquals(later < 60_000 ? "v" : null, copy.execute(cmd(
                    "GET short")));
            }
        }
        finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
