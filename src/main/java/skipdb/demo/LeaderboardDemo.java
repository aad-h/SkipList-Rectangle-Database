package skipdb.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live leaderboard demo: a tiny HTTP server ({@code com.sun.net.httpserver})
 * serving one HTML page that polls SkipDB and animates rank changes. Every
 * read and write goes over RESP through {@link RespClient}, exactly like any
 * other client, so the demo works against real Redis too.
 *
 * <p>Endpoints:
 * <ul>
 * <li>{@code GET /}: the page</li>
 * <li>{@code GET /api/board?n=15}: top n as JSON ({@code ZREVRANGE ...
 * WITHSCORES} + {@code ZCARD})</li>
 * <li>{@code GET /api/rank?member=alice}: one player's rank and score
 * ({@code ZREVRANK} + {@code ZSCORE})</li>
 * <li>{@code POST /api/bump?member=alice}: {@code ZINCRBY} by
 * {@link #BUMP_POINTS}</li>
 * </ul>
 *
 * <p>An optional simulator seeds {@link #PLAYERS} (with {@code ZADD NX}, so an
 * AOF-restored board keeps its scores) and keeps adding points to random
 * players so the ranks move.
 *
 * <pre>
 * java -cp target/classes skipdb.SkipDBServer --demo        # both in one
 * java -cp target/classes skipdb.demo.LeaderboardDemo [--redis host:port]
 *     [--port 8080] [--key demo:board] [--no-sim]
 * </pre>
 */
public final class LeaderboardDemo implements AutoCloseable {

    /** Sorted-set key the demo reads and writes by default. */
    public static final String DEFAULT_KEY = "demo:board";

    /** Default HTTP port. */
    public static final int DEFAULT_HTTP_PORT = 8080;

    /** Points a click on a row (or {@code POST /api/bump}) adds. */
    public static final int BUMP_POINTS = 25;

    /** Largest {@code n} accepted by {@code /api/board}. */
    static final int MAX_TOP = 100;

    /** Longest member name accepted from HTTP. */
    static final int MAX_MEMBER = 64;

    /** Players the simulator seeds and moves. */
    static final String[] PLAYERS = { "ada", "alan", "barbara", "bjarne",
        "dennis", "donald", "edsger", "frances", "grace", "guido", "james",
        "john", "ken", "linus", "margaret", "niklaus", "radia", "richard",
        "shafi", "sophie", "tim", "tony", "barbara-l", "leslie" };

    private static final String PAGE_RESOURCE = "leaderboard.html";
    /** Used when running from javac output, which has no resources. */
    private static final Path PAGE_SOURCE = Paths.get("src", "main",
        "resources", "skipdb", "demo", PAGE_RESOURCE);

    private final String respHost;
    private final int respPort;
    private final RespClient db;
    private final String key;
    private final byte[] page;
    private final HttpServer http;
    private final ExecutorService httpPool;
    private final AtomicLong writes = new AtomicLong();
    private ScheduledExecutorService sim;
    private RespClient simDb;

    /**
     * Connects to the RESP server and binds the HTTP port (0 picks a free
     * port). Call {@link #start()} to begin serving.
     *
     * @throws IOException
     *             if either connection fails or the page cannot be found
     */
    public LeaderboardDemo(String host, int respPort, int httpPort, String key)
        throws IOException {
        this.respHost = host;
        this.respPort = respPort;
        this.key = key;
        this.page = loadPage();
        db = new RespClient(host, respPort);
        try {
            http = HttpServer.create(new InetSocketAddress(httpPort), 0);
        }
        catch (IOException e) {
            db.close();
            throw e;
        }
        httpPool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "skipdb-demo-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(httpPool);
        http.createContext("/", this::handle);
    }

    /**
     * Starts a demo with the simulator running: the one-liner behind
     * {@code SkipDBServer --demo}.
     *
     * @throws IOException
     *             if the RESP server is unreachable or the port is taken
     */
    public static LeaderboardDemo launch(String host, int respPort,
        int httpPort) throws IOException {
        LeaderboardDemo d = new LeaderboardDemo(host, respPort, httpPort,
            DEFAULT_KEY);
        try {
            d.startSimulation(250, new Random());
            return d.start();
        }
        catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
    }


    private static byte[] loadPage() throws IOException {
        try (InputStream in = LeaderboardDemo.class.getResourceAsStream(
            PAGE_RESOURCE)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        if (Files.isReadable(PAGE_SOURCE)) {
            return Files.readAllBytes(PAGE_SOURCE);
        }
        throw new IOException("leaderboard page not found: neither resource "
            + PAGE_RESOURCE + " nor " + PAGE_SOURCE);
    }


    /** Starts serving HTTP on a background thread pool. */
    public LeaderboardDemo start() {
        http.start();
        return this;
    }


    /** @return the bound HTTP port */
    public int port() {
        return http.getAddress().getPort();
    }


    /** @return writes made through the demo (simulator ticks and bumps) */
    public long writes() {
        return writes.get();
    }


    /**
     * Seeds {@link #PLAYERS} with {@code ZADD NX} (existing scores are kept)
     * and, on a separate connection, adds points to a random player every
     * {@code periodMs}.
     *
     * @return the number of players newly added
     * @throws IOException
     *             if seeding fails
     */
    public synchronized long startSimulation(long periodMs, Random rnd)
        throws IOException {
        if (sim != null) {
            throw new IllegalStateException("simulation already running");
        }
        long added = seed();
        simDb = new RespClient(respHost, respPort);
        sim = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skipdb-demo-sim");
            t.setDaemon(true);
            return t;
        });
        sim.scheduleWithFixedDelay(() -> {
            try {
                tick(simDb, rnd);
            }
            catch (IOException | RuntimeException e) {
                // keep ticking; the server may be restarting
                System.err.println("demo simulator: " + e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return added;
    }


    /**
     * Adds every player in {@link #PLAYERS} with score 0 unless present.
     *
     * @return how many were added
     */
    long seed() throws IOException {
        String[] args = new String[3 + 2 * PLAYERS.length];
        args[0] = "ZADD";
        args[1] = key;
        args[2] = "NX";
        for (int i = 0; i < PLAYERS.length; i++) {
            args[3 + 2 * i] = "0";
            args[4 + 2 * i] = PLAYERS[i];
        }
        return (Long)db.call(args);
    }


    /**
     * One simulator step: a random player gains 1..30 points, or, one time
     * in twenty, a 100-point jump that reshuffles the top.
     *
     * @return the player that scored
     */
    String tick(RespClient conn, Random rnd) throws IOException {
        String player = PLAYERS[rnd.nextInt(PLAYERS.length)];
        int points = rnd.nextInt(20) == 0 ? 100 : 1 + rnd.nextInt(30);
        conn.call("ZINCRBY", key, Integer.toString(points), player);
        writes.incrementAndGet();
        return player;
    }


    private void handle(HttpExchange ex) throws IOException {
        try (HttpExchange e = ex) {
            String path = e.getRequestURI().getPath();
            String method = e.getRequestMethod();
            Map<String, String> q = parseQuery(e.getRequestURI()
                .getRawQuery());
            try {
                switch (path) {
                    case "/":
                    case "/index.html":
                        if (!requireMethod(e, method, "GET")) {
                            return;
                        }
                        send(e, 200, "text/html; charset=utf-8", page);
                        return;
                    case "/api/board":
                        if (requireMethod(e, method, "GET")) {
                            json(e, 200, board(parseTop(q.get("n"))));
                        }
                        return;
                    case "/api/rank":
                        if (requireMethod(e, method, "GET")) {
                            json(e, 200, rank(member(q)));
                        }
                        return;
                    case "/api/bump":
                        if (requireMethod(e, method, "POST")) {
                            json(e, 200, bump(member(q)));
                        }
                        return;
                    default:
                        json(e, 404, error("not found: " + path));
                }
            }
            catch (IllegalArgumentException bad) {
                json(e, 400, error(bad.getMessage()));
            }
            catch (IOException down) {
                json(e, 502, error("SkipDB: " + down.getMessage()));
            }
        }
    }


    private static boolean requireMethod(HttpExchange e, String method,
        String allowed) throws IOException {
        if (method.equals(allowed)) {
            return true;
        }
        e.getResponseHeaders().set("Allow", allowed);
        json(e, 405, error("method not allowed: " + method));
        return false;
    }


    static int parseTop(String n) {
        if (n == null) {
            return 15;
        }
        int top;
        try {
            top = Integer.parseInt(n);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("n must be an integer");
        }
        if (top < 1 || top > MAX_TOP) {
            throw new IllegalArgumentException("n must be between 1 and "
                + MAX_TOP);
        }
        return top;
    }


    private static String member(Map<String, String> q) {
        String m = q.get("member");
        if (m == null || m.isEmpty()) {
            throw new IllegalArgumentException("member is required");
        }
        if (m.length() > MAX_MEMBER) {
            throw new IllegalArgumentException("member is longer than "
                + MAX_MEMBER + " characters");
        }
        return m;
    }


    /**
     * Top {@code n} as JSON: {@code {"key", "players", "writes",
     * "latencyMicros", "top": [{"member", "score"}, ...]}}, best first.
     * {@code latencyMicros} is the RESP round trip for both commands.
     */
    String board(int n) throws IOException {
        long t0 = System.nanoTime();
        List<?> flat = (List<?>)db.call("ZREVRANGE", key, "0", Integer
            .toString(n - 1), "WITHSCORES");
        long players = (Long)db.call("ZCARD", key);
        long micros = (System.nanoTime() - t0) / 1000;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"key\":").append(quote(key));
        sb.append(",\"players\":").append(players);
        sb.append(",\"writes\":").append(writes.get());
        sb.append(",\"latencyMicros\":").append(micros);
        sb.append(",\"top\":[");
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"member\":").append(quote((String)flat.get(i)));
            sb.append(",\"score\":").append(number((String)flat.get(i + 1)))
                .append('}');
        }
        return sb.append("]}").toString();
    }


    /**
     * One player's 1-based rank (best = 1) and score, both null if absent.
     */
    String rank(String member) throws IOException {
        Object r = db.call("ZREVRANK", key, member);
        Object s = db.call("ZSCORE", key, member);
        return "{\"member\":" + quote(member) + ",\"rank\":" + (r == null
            ? "null"
            : Long.toString((Long)r + 1)) + ",\"score\":" + (s == null
                ? "null"
                : number((String)s)) + "}";
    }


    /** Adds {@link #BUMP_POINTS} to a player (creating it if new). */
    String bump(String member) throws IOException {
        String score = (String)db.call("ZINCRBY", key, Integer.toString(
            BUMP_POINTS), member);
        writes.incrementAndGet();
        return "{\"member\":" + quote(member) + ",\"score\":" + number(score)
            + "}";
    }


    /** Parses {@code a=1&b=2} (URL-decoded); later duplicates win. */
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return q;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder
                .decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }


    /**
     * A Redis score string as a JSON number: integral values without a
     * fraction, infinities as null (JSON has no infinity).
     */
    static String number(String redisScore) {
        double d = Double.parseDouble(redisScore.replace("inf", "Infinity"));
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long)d);
        }
        return Double.toString(d);
    }


    /** @return {@code s} as a JSON string literal */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // < > & escaped too, so the JSON is safe to inline in HTML
                    if (c < 0x20 || c == '<' || c == '>' || c == '&'
                        || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int)c));
                    }
                    else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }


    private static String error(String msg) {
        return "{\"error\":" + quote(msg) + "}";
    }


    private static void json(HttpExchange e, int status, String body)
        throws IOException {
        e.getResponseHeaders().set("Cache-Control", "no-store");
        send(e, status, "application/json", body.getBytes(
            StandardCharsets.UTF_8));
    }


    private static void send(HttpExchange e, int status, String type,
        byte[] body) throws IOException {
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(status, body.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(body);
        }
    }


    @Override
    public synchronized void close() throws IOException {
        http.stop(0);
        httpPool.shutdownNow();
        if (sim != null) {
            sim.shutdownNow();
            simDb.close();
        }
        db.close();
    }


    public static void main(String[] args) throws IOException {
        String host = "localhost";
        int respPort = 6380;
        int httpPort = DEFAULT_HTTP_PORT;
        String key = DEFAULT_KEY;
        boolean simulate = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--redis") && i + 1 < args.length) {
                String[] hp = args[++i].split(":");
                host = hp[0];
                if (hp.length > 1) {
                    respPort = Integer.parseInt(hp[1]);
                }
            }
            else if (args[i].equals("--port") && i + 1 < args.length) {
                httpPort = Integer.parseInt(args[++i]);
            }
            else if (args[i].equals("--key") && i + 1 < args.length) {
                key = args[++i];
            }
            else if (args[i].equals("--no-sim")) {
                simulate = false;
            }
            else {
                throw new IllegalArgumentException("unknown argument: "
                    + args[i]);
            }
        }
        LeaderboardDemo d = new LeaderboardDemo(host, respPort, httpPort, key);
        if (simulate) {
            d.startSimulation(250, new Random());
        }
        d.start();
        System.out.println("Leaderboard demo on http://localhost:" + d.port()
            + "/ (" + host + ":" + respPort + ", key " + key + ")"
            + (simulate ? "" : ", simulator off"));
    }
}
