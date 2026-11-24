package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CommandHandlerTest {

    private CommandHandler h;

    private Object run(String line) {
        return Resp.toResp2(h.execute(Arrays.asList(line.split(" "))));
    }


    private static boolean isError(Object o, String prefix) {
        return o instanceof Resp.Error && ((Resp.Error)o).message.startsWith(
            prefix);
    }


    @Before
    public void setUp() {
        h = new CommandHandler();
        run("ZADD board 100 alice 85 bob 92 carol 85 dave");
    }


    @Test
    public void leaderboardQueries() {
        assertEquals(List.of("alice", "carol", "dave", "bob"), run(
            "ZREVRANGE board 0 -1"));
        assertEquals(List.of("alice", "100", "carol", "92"), run(
            "ZREVRANGE board 0 1 WITHSCORES"));
        assertEquals(0L, run("ZREVRANK board alice"));
        assertEquals(1L, run("ZRANK board dave"));
        assertNull(run("ZRANK board nobody"));
        assertEquals("92", run("ZSCORE board carol"));
        assertEquals(4L, run("ZCARD board"));
    }


    @Test
    public void zaddCountsOnlyNewMembers() {
        assertEquals(1L, run("ZADD board 1 erin 101 alice"));
        assertEquals("101", run("ZSCORE board alice"));
        assertEquals("102.5", run("ZINCRBY board 1.5 alice"));
        assertTrue(isError(run("ZADD board 1"), "ERR wrong number"));
        assertTrue(isError(run("ZADD board 1 a 2"), "ERR syntax"));
        assertTrue(isError(run("ZADD board x zed"), "ERR value is not a valid float"));
        assertNull(run("ZSCORE board zed")); // bad ZADD changed nothing
    }


    @Test
    public void scoreRanges() {
        assertEquals(List.of("bob", "dave", "carol"), run(
            "ZRANGEBYSCORE board 85 (100"));
        assertEquals(List.of("dave", "85"), run(
            "ZRANGEBYSCORE board -inf +inf WITHSCORES LIMIT 1 1"));
        assertEquals(3L, run("ZCOUNT board 85 (100"));
        assertEquals(2L, run("ZCOUNT board (85 +inf"));
        assertTrue(isError(run("ZRANGEBYSCORE board 1 2 BOGUS"), "ERR syntax"));
    }


    @Test
    public void zremDeletesEmptyKey() {
        assertEquals(2L, run("ZREM board alice bob ghost"));
        assertEquals(2L, run("ZREM board carol dave"));
        assertEquals(0L, run("EXISTS board"));
        assertEquals(new Resp.Simple("none"), run("TYPE board"));
    }


    @Test
    public void stringsKeysAndTypes() {
        assertEquals(Resp.OK, run("SET greeting hello"));
        assertEquals("hello", run("GET greeting"));
        assertNull(run("GET missing"));
        assertEquals(new Resp.Simple("zset"), run("TYPE board"));
        assertTrue(isError(run("GET board"), "WRONGTYPE"));
        assertTrue(isError(run("ZADD greeting 1 x"), "WRONGTYPE"));
        assertEquals(List.of("greeting"), run("KEYS gr*"));
        assertEquals(2L, run("DBSIZE"));
        assertEquals(2L, run("DEL greeting board nope"));
        assertEquals(new Resp.Simple("PONG"), run("PING"));
        assertEquals("hi", run("ECHO hi"));
        assertTrue(isError(run("NOPE"), "ERR unknown command"));
    }


    @Test
    public void scoreFormatting() {
        assertEquals("3", CommandHandler.fmt(3.0));
        assertEquals("-2.25", CommandHandler.fmt(-2.25));
        assertEquals("inf", CommandHandler.fmt(Double.POSITIVE_INFINITY));
    }
}
