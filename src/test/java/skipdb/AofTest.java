package skipdb;

import static org.junit.Assert.assertEquals;
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
import java.util.Arrays;
import java.util.List;
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
        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
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
}
