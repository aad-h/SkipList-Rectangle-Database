package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AofTest {

    private Path dir;
    private Path file;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("skipdb-aof");
        file = dir.resolve("appendonly.aof");
    }


    @After
    public void tearDown() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p
                .toFile().delete());
        }
    }


    private static List<String> cmd(String line) {
        return Arrays.asList(line.split(" "));
    }


    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }


    private void write(String s) throws IOException {
        Files.write(file, s.getBytes(StandardCharsets.UTF_8));
    }


    @Test
    public void appendsRespArraysAndReplaysThem() throws IOException {
        try (Aof aof = new Aof(file)) {
            assertEquals(0, aof.load(new CommandHandler()));
            aof.append(cmd("ZADD board 100 alice 85 bob"));
            aof.append(Arrays.asList("SET", "greeting", "héllo world"));
        }
        assertTrue(read(file).startsWith(
            "*6\r\n$4\r\nZADD\r\n$5\r\nboard\r\n$3\r\n100\r\n"));
        CommandHandler h = new CommandHandler();
        try (Aof aof = new Aof(file)) {
            assertEquals(2, aof.load(h));
        }
        assertEquals(List.of("alice", "bob"), h.execute(cmd(
            "ZREVRANGE board 0 -1")));
        assertEquals("héllo world", h.execute(cmd("GET greeting")));
    }


    @Test
    public void truncatedTailIsDroppedAndFileRepaired() throws IOException {
        String good = "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n";
        // every proper prefix of a second command counts as a torn write
        String second = "*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$1\r\n2\r\n";
        for (int cut = 1; cut < second.length(); cut++) {
            write(good + second.substring(0, cut));
            CommandHandler h = new CommandHandler();
            try (Aof aof = new Aof(file)) {
                assertEquals("cut " + cut, 1, aof.load(h));
                aof.append(cmd("SET c 3"));
            }
            assertEquals("1", h.execute(cmd("GET a")));
            assertNull(h.execute(cmd("GET b")));
            assertEquals(good + "*3\r\n$3\r\nSET\r\n$1\r\nc\r\n$1\r\n3\r\n",
                read(file));
        }
    }


    @Test
    public void unfinishedTransactionIsDiscarded() throws IOException {
        try (Aof aof = new Aof(file)) {
            aof.load(new CommandHandler());
            aof.appendTransaction(List.of(cmd("SET a 1"), cmd("SET b 2")));
        }
        String committed = read(file);
        assertTrue(committed.startsWith("*1\r\n$5\r\nMULTI\r\n"));
        assertTrue(committed.endsWith("*1\r\n$4\r\nEXEC\r\n"));
        // a crash after MULTI and one command: neither half may be applied
        write(committed + "*1\r\n$5\r\nMULTI\r\n*3\r\n$3\r\nSET\r\n$1\r\nx"
            + "\r\n$1\r\n9\r\n");
        CommandHandler h = new CommandHandler();
        try (Aof aof = new Aof(file)) {
            assertEquals(2, aof.load(h));
        }
        assertEquals("2", h.execute(cmd("GET b")));
        assertNull(h.execute(cmd("GET x")));
        assertEquals(committed, read(file));
    }


    @Test
    public void emptyAndSingleTransactionsAreNotWrapped() throws IOException {
        try (Aof aof = new Aof(file)) {
            aof.load(new CommandHandler());
            aof.appendTransaction(List.of());
            aof.appendTransaction(List.of(cmd("DEL a")));
        }
        assertEquals("*2\r\n$3\r\nDEL\r\n$1\r\na\r\n", read(file));
    }


    @Test
    public void corruptFileRefusesToLoad() throws IOException {
        write("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n*1\r\n$x\r\n");
        try (Aof aof = new Aof(file)) {
            aof.load(new CommandHandler());
            fail("expected corrupt AOF to be rejected");
        }
        catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith(
                "Bad file format reading the append only file"));
        }
        write("*1\r\n$4\r\nEXEC\r\n");
        try (Aof aof = new Aof(file)) {
            aof.load(new CommandHandler());
            fail("expected stray EXEC to be rejected");
        }
        catch (IOException e) {
            assertTrue(e.getMessage().contains("EXEC without MULTI"));
        }
    }


    @Test
    public void writeCommandClassification() {
        assertTrue(CommandHandler.isWriteCommand("ZADD"));
        assertTrue(CommandHandler.isWriteCommand("flushall"));
        assertTrue(!CommandHandler.isWriteCommand("zrange"));
        assertTrue(!CommandHandler.isWriteCommand("get"));
    }


    /** Sends one RESP command and returns the first reply line. */
    private static String call(Socket s, String... args) throws IOException {
        StringBuilder sb = new StringBuilder("*" + args.length + "\r\n");
        for (String a : args) {
            sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length)
                .append("\r\n").append(a).append("\r\n");
        }
        OutputStream out = s.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != '\n' && c != -1) {
            if (c != '\r') {
                line.append((char)c);
            }
        }
        if (line.charAt(0) == '$' && !line.toString().equals("$-1")) {
            int n = Integer.parseInt(line.substring(1));
            String v = new String(in.readNBytes(n), StandardCharsets.UTF_8);
            in.readNBytes(2);
            return v;
        }
        return line.toString();
    }


    @Test
    public void serverSurvivesRestart() throws IOException {
        try (SkipDBServer srv = new SkipDBServer(0, file).start();
            Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertEquals(":3", call(s, "ZADD", "board", "100", "alice", "85",
                "bob", "92", "carol"));
            assertEquals("101.5", call(s, "ZINCRBY", "board", "1.5", "alice"));
            assertEquals(":1", call(s, "ZREM", "board", "bob"));
            assertEquals("+OK", call(s, "SET", "k", "v"));
            assertTrue(call(s, "ZADD", "k", "1", "x").startsWith("-WRONGTYPE"));
            assertEquals("+OK", call(s, "MULTI"));
            assertEquals("+QUEUED", call(s, "ZADD", "board", "50", "dave"));
            assertEquals("+QUEUED", call(s, "GET", "k"));
            assertEquals("+QUEUED", call(s, "DEL", "k"));
            assertEquals("*3", call(s, "EXEC"));
        }
        String log = read(file);
        assertTrue("failed write must not be logged", !log.contains("$1\r\nx"));
        assertTrue("reads must not be logged", !log.contains("GET"));
        try (SkipDBServer srv = new SkipDBServer(0, file).start();
            Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertEquals("101.5", call(s, "ZSCORE", "board", "alice"));
            assertEquals("$-1", call(s, "ZSCORE", "board", "bob"));
            assertEquals(":0", call(s, "ZRANK", "board", "dave"));
            assertEquals(":0", call(s, "EXISTS", "k"));
            assertEquals(":3", call(s, "ZCARD", "board"));
        }
    }


    // ---- fsync policies ----

    @Test
    public void fsyncPolicyParsing() {
        assertEquals(Aof.Fsync.ALWAYS, Aof.Fsync.parse("always"));
        assertEquals(Aof.Fsync.EVERYSEC, Aof.Fsync.parse("EverySec"));
        assertEquals(Aof.Fsync.NO, Aof.Fsync.parse("no"));
        assertEquals(Aof.Fsync.EVERYSEC, new Aof(file).fsyncPolicy());
        try {
            Aof.Fsync.parse("sometimes");
            fail();
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("sometimes"));
        }
    }


    @Test
    public void alwaysFsyncsEveryWrite() throws IOException {
        try (Aof aof = new Aof(file, Aof.Fsync.ALWAYS)) {
            aof.load(new CommandHandler());
            aof.append(cmd("SET a 1"));
            aof.append(cmd("SET b 2"));
            assertEquals(2, aof.fsyncCount());
            aof.appendTransaction(List.of(cmd("SET c 3"), cmd("SET d 4")));
            assertEquals("one fsync per transaction", 3, aof.fsyncCount());
            assertFalse("nothing left to sync", aof.fsyncIfDirty());
        }
    }


    @Test
    public void everysecBatchesFsyncs() throws IOException {
        Aof aof = new Aof(file, Aof.Fsync.EVERYSEC);
        aof.load(new CommandHandler());
        assertFalse(aof.fsyncIfDirty());
        for (int i = 0; i < 100; i++) {
            aof.append(cmd("SET k " + i));
        }
        assertEquals("command path never fsyncs", 0, aof.fsyncCount());
        assertTrue(aof.fsyncIfDirty());
        assertFalse(aof.fsyncIfDirty());
        assertEquals(1, aof.fsyncCount());
        aof.append(cmd("SET k last"));
        aof.close();
        assertEquals("close syncs pending writes", 2, aof.fsyncCount());
        assertFalse(aof.fsyncIfDirty());
    }


    @Test
    public void noPolicyNeverFsyncs() throws IOException {
        Aof aof = new Aof(file, Aof.Fsync.NO);
        aof.load(new CommandHandler());
        aof.append(cmd("SET a 1"));
        assertFalse(aof.fsyncIfDirty());
        aof.close();
        assertEquals(0, aof.fsyncCount());
        assertTrue(read(file).contains("SET"));
    }


    // ---- rewrite ----

    @Test
    public void rewriteCommandsAreMinimalAndExact() {
        CommandHandler h = new CommandHandler();
        h.execute(cmd("SET s v1"));
        h.execute(cmd("SET s v2"));
        h.execute(cmd("ZADD big 1 x"));
        for (int i = 0; i < 150; i++) {
            h.execute(cmd("ZADD big " + i + " m" + i));
        }
        h.execute(Arrays.asList("ZADD", "odd", "-0", "negzero", "0.1", "tenth",
            "inf", "top", "-inf", "bottom", "1e300", "huge", "123456789012345678",
            "long", "-2.5", "neg"));
        h.execute(cmd("ZINCRBY odd 0.2 tenth"));
        h.execute(cmd("ZADD gone 1 a"));
        h.execute(cmd("ZREM gone a"));
        List<List<String>> cmds = h.rewriteCommands();
        // big: 151 members in chunks of 64 -> 3; odd -> 1; s -> 1; gone absent
        assertEquals(5, cmds.size());
        assertEquals(List.of("ZADD", "big"), cmds.get(0).subList(0, 2));
        assertEquals(2 + 2 * CommandHandler.REWRITE_ITEMS_PER_CMD, cmds.get(0)
            .size());
        assertEquals(List.of("SET", "s", "v2"), cmds.get(4));
        assertTrue(cmds.get(3).contains("-0"));

        CommandHandler copy = new CommandHandler();
        for (List<String> c : cmds) {
            assertTrue(c.toString(), !(copy.execute(c) instanceof Resp.Error));
        }
        for (String key : List.of("big", "odd")) {
            assertEquals(h.execute(cmd("ZRANGE " + key + " 0 -1 WITHSCORES")),
                copy.execute(cmd("ZRANGE " + key + " 0 -1 WITHSCORES")));
        }
        assertEquals(h.execute(cmd("ZSCORE odd tenth")), copy.execute(cmd(
            "ZSCORE odd tenth")));
        assertEquals("v2", copy.execute(cmd("GET s")));
        assertEquals(h.execute(cmd("DBSIZE")), copy.execute(cmd("DBSIZE")));
        // negative zero keeps its place ahead of positive zero
        copy.execute(cmd("ZADD odd 0 poszero"));
        h.execute(cmd("ZADD odd 0 poszero"));
        assertEquals(h.execute(cmd("ZRANK odd poszero")), copy.execute(cmd(
            "ZRANK odd poszero")));
    }


    @Test
    public void rewriteCompactsAndKeepsWritesMadeDuringIt() throws Exception {
        CommandHandler h = new CommandHandler();
        Aof aof = new Aof(file, Aof.Fsync.ALWAYS);
        aof.load(h);
        for (int i = 0; i < 500; i++) {
            List<String> c = cmd("ZINCRBY board 1 alice");
            h.execute(c);
            aof.append(c);
        }
        aof.append(cmd("SET tmp x"));
        h.execute(cmd("SET tmp x"));
        aof.append(cmd("DEL tmp"));
        h.execute(cmd("DEL tmp"));
        long before = Files.size(file);

        // writes that race with the rewrite must survive the swap
        aof.afterSnapshotHook = () -> {
            try {
                aof.append(cmd("ZADD board 7 bob"));
                aof.appendTransaction(List.of(cmd("SET a 1"), cmd("SET b 2")));
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        aof.rewrite(h.rewriteCommands()).get(10, TimeUnit.SECONDS);
        assertFalse(aof.rewriteInProgress());
        assertEquals(1, aof.rewriteCount());
        aof.append(cmd("SET c 3"));
        aof.close();

        String log = read(file);
        assertTrue("file shrank: " + Files.size(file) + " < " + before, Files
            .size(file) < before / 10);
        assertTrue(log.startsWith("*4\r\n$4\r\nZADD\r\n$5\r\nboard\r\n"
            + "$3\r\n500\r\n$5\r\nalice\r\n"));
        assertTrue(!log.contains("ZINCRBY") && !log.contains("tmp"));
        assertTrue(log.contains("MULTI"));
        try (java.util.stream.Stream<Path> left = Files.list(dir)) {
            assertEquals("temp file cleaned up", 1, left.count());
        }

        CommandHandler replay = new CommandHandler();
        try (Aof again = new Aof(file)) {
            again.load(replay);
        }
        assertEquals(List.of("bob", "7", "alice", "500"), replay.execute(cmd(
            "ZRANGE board 0 -1 WITHSCORES")));
        assertEquals("1", replay.execute(cmd("GET a")));
        assertEquals("2", replay.execute(cmd("GET b")));
        assertEquals("3", replay.execute(cmd("GET c")));
    }


    @Test
    public void onlyOneRewriteAtATime() throws Exception {
        CommandHandler h = new CommandHandler();
        Aof aof = new Aof(file);
        aof.load(h);
        CountDownLatch release = new CountDownLatch(1);
        aof.afterSnapshotHook = () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.CompletableFuture<Void> first = aof.rewrite(h
            .rewriteCommands());
        assertTrue(aof.rewriteInProgress());
        try {
            aof.rewrite(h.rewriteCommands());
            fail("second rewrite must be refused");
        }
        catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("already in progress"));
        }
        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        aof.afterSnapshotHook = null;
        aof.rewrite(h.rewriteCommands()).get(10, TimeUnit.SECONDS);
        assertEquals(2, aof.rewriteCount());
        aof.close();
    }


    @Test
    public void failedRewriteLeavesOldFileInUse() throws Exception {
        CommandHandler h = new CommandHandler();
        Aof aof = new Aof(file);
        aof.load(h);
        aof.append(cmd("SET a 1"));
        // a directory where the temp file should go makes the rewrite fail
        Files.createDirectory(dir.resolve("temp-rewriteaof-appendonly.aof"));
        try {
            aof.rewrite(List.of(cmd("SET a 1"))).get(10, TimeUnit.SECONDS);
            fail("rewrite should have failed");
        }
        catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
        assertFalse(aof.rewriteInProgress());
        aof.append(cmd("SET b 2"));
        aof.close();
        assertEquals("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n"
            + "*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$1\r\n2\r\n", read(file));
    }


    @Test
    public void bgrewriteaofOverTheWire() throws Exception {
        try (SkipDBServer srv = new SkipDBServer(0, null).start();
            Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertTrue(call(s, "BGREWRITEAOF").startsWith(
                "-ERR Append only file is not enabled"));
        }
        try (SkipDBServer srv = new SkipDBServer(0, file, Aof.Fsync.ALWAYS)
            .start(); Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            for (int i = 0; i < 200; i++) {
                call(s, "ZINCRBY", "board", "1", "p" + (i % 5));
            }
            assertEquals("+OK", call(s, "SET", "k", "v"));
            long before = Files.size(file);
            assertTrue(call(s, "BGREWRITEAOF", "x").startsWith(
                "-ERR wrong number of arguments"));
            assertEquals("+Background append only file rewriting started",
                call(s, "BGREWRITEAOF"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (srv.aof().rewriteCount() == 0) {
                assertTrue("rewrite timed out", System
                    .currentTimeMillis() < deadline);
                Thread.sleep(10);
            }
            assertTrue(Files.size(file) < before);
            assertEquals(":1", call(s, "ZADD", "board", "1", "late"));
            assertTrue(srv.aof().fsyncCount() > 200);
        }
        try (SkipDBServer srv = new SkipDBServer(0, file).start();
            Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertEquals(":6", call(s, "ZCARD", "board"));
            assertEquals("40", call(s, "ZSCORE", "board", "p3"));
            assertEquals("v", call(s, "GET", "k"));
        }
    }


    @Test
    public void serverLogsAbsoluteExpiryAndExpiredDeletes() throws Exception {
        try (SkipDBServer srv = new SkipDBServer(0, file, Aof.Fsync.ALWAYS)
            .start(); Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertEquals("+OK", call(s, "SET", "long", "v", "EX", "3600"));
            assertEquals("+OK", call(s, "SET", "brief", "v", "PX", "100"));
            assertEquals(":0", call(s, "EXPIRE", "missing", "10"));
            long deadline = System.currentTimeMillis() + 3000;
            while (!read(file).contains("DEL") && System
                .currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }
        String log = read(file);
        assertTrue(log, log.contains("PXAT"));
        assertTrue("relative EX must not be logged", !log.contains("$2\r\nEX"));
        assertTrue("no-op EXPIRE must not be logged", !log.contains("missing"));
        assertTrue("active expiry must log a DEL", log.contains(
            "*2\r\n$3\r\nDEL\r\n$5\r\nbrief"));
        try (SkipDBServer srv = new SkipDBServer(0, file).start();
            Socket s = new Socket("localhost", srv.port())) {
            s.setSoTimeout(5000);
            assertEquals(":1", call(s, "DBSIZE"));
            long ttl = Long.parseLong(call(s, "TTL", "long").substring(1));
            assertTrue(String.valueOf(ttl), ttl > 3590 && ttl <= 3600);
        }
    }
}
