package skipdb.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import skipdb.Aof;
import skipdb.SkipDBServer;

/**
 * The leaderboard demo end to end: a real SkipDB server, the demo talking to
 * it over RESP, and HTTP requests against the demo.
 */
public class LeaderboardDemoTest {

    private SkipDBServer server;
    private LeaderboardDemo demo;
    private RespClient redis;

    @Before
    public void setUp() throws IOException {
        server = new SkipDBServer(0, null, Aof.Fsync.EVERYSEC,
            SkipDBServer.Io.NIO).start();
        demo = new LeaderboardDemo("localhost", server.port(), 0,
            LeaderboardDemo.DEFAULT_KEY).start();
        redis = new RespClient("localhost", server.port());
    }


    @After
    public void tearDown() throws IOException {
        redis.close();
        demo.close();
        server.close();
    }


    /** Status code and body of one HTTP request. */
    private static final class Response {
        final int status;
        final String body;
        final String type;

        Response(int status, String body, String type) {
            this.status = status;
            this.body = body;
            this.type = type;
        }
    }


    private Response http(String method, String path) throws IOException {
        URL url = URI.create("http://localhost:" + demo.port() + path).toURL();
        HttpURLConnection c = (HttpURLConnection)url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        int status = c.getResponseCode();
        InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null
            ? ""
            : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        String type = c.getContentType();
        c.disconnect();
        return new Response(status, body, type);
    }


    @Test
    public void servesThePage() throws IOException {
        Response r = http("GET", "/");
        assertEquals(200, r.status);
        assertTrue(r.type.startsWith("text/html"));
        assertTrue(r.body.contains("SkipDB live leaderboard"));
        assertTrue(r.body.contains("api/board"));
        assertEquals(r.body, http("GET", "/index.html").body);
    }


    @Test
    public void boardIsTopNBestFirst() throws IOException {
        redis.call("ZADD", "demo:board", "100", "alice", "85", "bob", "92.5",
            "carol", "10", "dave");
        Response r = http("GET", "/api/board?n=3");
        assertEquals(200, r.status);
        assertEquals("application/json", r.type);
        assertTrue(r.body, r.body.startsWith(
            "{\"key\":\"demo:board\",\"players\":4,\"writes\":0,"
                + "\"latencyMicros\":"));
        assertTrue(r.body, r.body.endsWith(",\"top\":["
            + "{\"member\":\"alice\",\"score\":100},"
            + "{\"member\":\"carol\",\"score\":92.5},"
            + "{\"member\":\"bob\",\"score\":85}]}"));
    }


    @Test
    public void emptyBoard() throws IOException {
        Response r = http("GET", "/api/board");
        assertEquals(200, r.status);
        assertTrue(r.body, r.body.contains("\"players\":0"));
        assertTrue(r.body, r.body.endsWith("\"top\":[]}"));
    }


    @Test
    public void rankLookup() throws IOException {
        redis.call("ZADD", "demo:board", "100", "alice", "85", "bob");
        assertEquals("{\"member\":\"bob\",\"rank\":2,\"score\":85}", http(
            "GET", "/api/rank?member=bob").body);
        assertEquals("{\"member\":\"zed\",\"rank\":null,\"score\":null}",
            http("GET", "/api/rank?member=zed").body);
    }


    @Test
    public void bumpAddsPointsOverResp() throws IOException {
        redis.call("ZADD", "demo:board", "100", "alice", "85", "bob");
        Response r = http("POST", "/api/bump?member=bob");
        assertEquals(200, r.status);
        assertEquals("{\"member\":\"bob\",\"score\":110}", r.body);
        assertEquals("110", redis.call("ZSCORE", "demo:board", "bob"));
        assertEquals(0L, redis.call("ZREVRANK", "demo:board", "bob"));
        assertEquals(1, demo.writes());
        // URL-encoded names, created on first bump
        assertEquals("{\"member\":\"new player\",\"score\":25}", http("POST",
            "/api/bump?member=new%20player").body);
    }


    @Test
    public void badRequests() throws IOException {
        assertEquals(404, http("GET", "/nope").status);
        assertEquals(405, http("GET", "/api/bump?member=a").status);
        assertEquals(405, http("POST", "/api/board").status);
        Response r = http("GET", "/api/board?n=0");
        assertEquals(400, r.status);
        assertEquals("{\"error\":\"n must be between 1 and 100\"}", r.body);
        assertEquals(400, http("GET", "/api/board?n=x").status);
        assertEquals(400, http("GET", "/api/rank").status);
        char[] longName = new char[LeaderboardDemo.MAX_MEMBER + 1];
        Arrays.fill(longName, 'a');
        assertEquals(400, http("POST", "/api/bump?member=" + new String(
            longName)).status);
    }


    @Test
    public void wrongTypeIsBadGateway() throws IOException {
        redis.call("SET", "demo:board", "not a zset");
        Response r = http("GET", "/api/board");
        assertEquals(502, r.status);
        assertTrue(r.body, r.body.startsWith("{\"error\":\"SkipDB: WRONGTYPE"));
    }


    @Test
    public void seedKeepsExistingScores() throws IOException {
        redis.call("ZADD", "demo:board", "500", "grace");
        assertEquals((long)LeaderboardDemo.PLAYERS.length - 1, demo.seed());
        assertEquals(0L, demo.seed());
        assertEquals("500", redis.call("ZSCORE", "demo:board", "grace"));
        assertEquals((long)LeaderboardDemo.PLAYERS.length, redis.call("ZCARD",
            "demo:board"));
    }


    @Test
    public void ticksMoveScores() throws IOException {
        demo.seed();
        Random rnd = new Random(42);
        for (int i = 0; i < 200; i++) {
            demo.tick(redis, rnd);
        }
        assertEquals(200, demo.writes());
        List<?> flat = (List<?>)redis.call("ZRANGE", "demo:board", "0", "-1",
            "WITHSCORES");
        double total = 0;
        for (int i = 1; i < flat.size(); i += 2) {
            total += Double.parseDouble((String)flat.get(i));
        }
        // 200 ticks of 1..30 or 100 points each
        assertTrue(total >= 200 && total <= 200 * 100);
        assertEquals((long)LeaderboardDemo.PLAYERS.length, redis.call("ZCARD",
            "demo:board"));
    }


    @Test
    public void simulationRunsInTheBackground() throws Exception {
        assertEquals((long)LeaderboardDemo.PLAYERS.length, demo
            .startSimulation(5, new Random(1)));
        long deadline = System.currentTimeMillis() + 5000;
        while (demo.writes() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(demo.writes() >= 5);
        try {
            demo.startSimulation(5, new Random());
            fail("expected IllegalStateException");
        }
        catch (IllegalStateException expected) {
            // only one simulator per demo
        }
    }


    @Test
    public void launchStartsEverything() throws IOException {
        try (LeaderboardDemo d = LeaderboardDemo.launch("localhost", server
            .port(), 0)) {
            assertTrue(d.port() > 0);
            assertEquals((long)LeaderboardDemo.PLAYERS.length, redis.call(
                "ZCARD", "demo:board"));
        }
    }


    @Test
    public void jsonHelpers() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003cscript\\u003e\"", LeaderboardDemo
            .quote("a\"b\\c\n<script>"));
        assertEquals("100", LeaderboardDemo.number("100"));
        assertEquals("-3", LeaderboardDemo.number("-3"));
        assertEquals("95.5", LeaderboardDemo.number("95.5"));
        assertEquals("null", LeaderboardDemo.number("inf"));
        assertEquals("null", LeaderboardDemo.number("-inf"));
        Map<String, String> q = LeaderboardDemo.parseQuery(
            "member=a%26b&n=5&flag");
        assertEquals("a&b", q.get("member"));
        assertEquals("5", q.get("n"));
        assertEquals("", q.get("flag"));
        assertEquals(15, LeaderboardDemo.parseTop(null));
    }


    @Test
    public void respClientDecodesEveryReplyType() throws IOException {
        assertEquals("PONG", redis.call("PING"));
        assertEquals(1L, redis.call("ZADD", "z", "1", "a"));
        assertNull(redis.call("GET", "missing"));
        assertEquals(Arrays.asList("a", "1"), redis.call("ZRANGE", "z", "0",
            "-1", "WITHSCORES"));
        assertEquals("café", redis.call("ECHO", "café"));
        try {
            redis.call("NOSUCHCOMMAND");
            fail("expected ServerError");
        }
        catch (RespClient.ServerError e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(
                "ERR unknown command"));
        }
        assertEquals("PONG", redis.call("PING")); // still in sync
    }


    @Test
    public void respClientKeepsArraysInSyncAroundErrors() throws IOException {
        Object reply = RespClient.readReply(new ByteArrayInputStream(
            "*3\r\n:1\r\n-ERR boom\r\n$-1\r\n".getBytes(
                StandardCharsets.UTF_8)));
        List<?> items = (List<?>)reply;
        assertEquals(3, items.size());
        assertEquals(1L, items.get(0));
        assertEquals("ERR boom", ((RespClient.ServerError)items.get(1))
            .getMessage());
        assertNull(items.get(2));
        assertNull(RespClient.readReply(new ByteArrayInputStream("*-1\r\n"
            .getBytes(StandardCharsets.UTF_8))));
    }
}
