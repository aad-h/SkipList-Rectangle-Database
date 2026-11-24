package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * RESP3: the encoder's native types, HELLO's validation, and per-connection
 * protocol switching over a socket. Expected bytes were captured from Redis
 * 7.0.15 for the same commands.
 */
public class Resp3Test {

    private static String enc(Object o, int proto) {
        return new String(Resp.encode(o, proto), StandardCharsets.UTF_8);
    }


    private static List<Object> list(Object... xs) {
        return Arrays.asList(xs);
    }


    private static List<String> cmd(String line) {
        return Arrays.asList(line.split(" "));
    }


    // ---- encoder ----

    @Test
    public void nullIsUnderscoreInResp3() {
        assertEquals("$-1\r\n", enc(null, 2));
        assertEquals("_\r\n", enc(null, 3));
        assertEquals("*2\r\n_\r\n,1.5\r\n", enc(list(null, new Resp.Dbl(1.5)),
            3));
    }


    @Test
    public void doublesAreBulkInResp2AndCommaInResp3() {
        assertEquals("$3\r\n2.5\r\n", enc(new Resp.Dbl(2.5), 2));
        assertEquals(",2.5\r\n", enc(new Resp.Dbl(2.5), 3));
        assertEquals(",100\r\n", enc(new Resp.Dbl(100), 3));
        assertEquals(",inf\r\n", enc(new Resp.Dbl(
            Double.POSITIVE_INFINITY), 3));
        assertEquals(",-inf\r\n", enc(new Resp.Dbl(
            Double.NEGATIVE_INFINITY), 3));
        assertEquals(",nan\r\n", enc(new Resp.Dbl(Double.NaN), 3));
    }


    @Test
    public void mapsAreFlatInResp2() {
        Resp.Map m = new Resp.Map(list("a", 1L, "b", list()));
        assertEquals("*4\r\n$1\r\na\r\n:1\r\n$1\r\nb\r\n*0\r\n", enc(m, 2));
        assertEquals("%2\r\n$1\r\na\r\n:1\r\n$1\r\nb\r\n*0\r\n", enc(m, 3));
    }


    @Test
    public void pairsNestInResp3() {
        Resp.Pairs p = new Resp.Pairs(list("a", new Resp.Dbl(1), "b",
            new Resp.Dbl(2.5)));
        assertEquals("*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$3\r\n2.5\r\n",
            enc(p, 2));
        assertEquals("*2\r\n*2\r\n$1\r\na\r\n,1\r\n*2\r\n$1\r\nb\r\n,2.5\r\n",
            enc(p, 3));
        assertEquals("*0\r\n", enc(new Resp.Pairs(list()), 3));
    }


    @Test
    public void verbatimCarriesItsFormatInResp3() {
        Resp.Verbatim v = new Resp.Verbatim("txt", "héllo");
        assertEquals("$6\r\nhéllo\r\n", enc(v, 2));
        // length counts the "txt:" prefix and UTF-8 bytes
        assertEquals("=10\r\ntxt:héllo\r\n", enc(v, 3));
    }


    @Test
    public void toResp2LowersNestedTypes() {
        Object r = list(new Resp.Pairs(list("a", new Resp.Dbl(3))),
            new Resp.Map(list("k", new Resp.Dbl(0.5))), new Resp.Verbatim(
                "txt", "x"), null);
        assertEquals(list(list("a", "3"), list("k", "0.5"), "x", null), Resp
            .toResp2(r));
    }


    // ---- handler ----

    @Test
    public void handlerRepliesWithTypedValues() {
        CommandHandler h = new CommandHandler();
        h.execute(cmd("ZADD z 1 a 2.5 b"));
        assertEquals(new Resp.Dbl(2.5), h.execute(cmd("ZSCORE z b")));
        assertEquals(new Resp.Dbl(3), h.execute(cmd("ZINCRBY z 2 a")));
        assertEquals(new Resp.Pairs(list("b", new Resp.Dbl(2.5), "a",
            new Resp.Dbl(3))), h.execute(cmd("ZRANGE z 0 -1 WITHSCORES")));
        // ZPOPMIN without a count stays flat even in RESP3; with one it nests
        assertEquals(list("b", new Resp.Dbl(2.5)), h.execute(cmd(
            "ZPOPMIN z")));
        assertEquals(new Resp.Pairs(list("a", new Resp.Dbl(3))), h.execute(
            cmd("ZPOPMAX z 1")));
        assertTrue(h.execute(cmd("INFO")) instanceof Resp.Verbatim);
    }


    @Test
    public void helloValidation() {
        assertEquals(2, CommandHandler.helloProtocol(cmd("HELLO"), 2));
        assertEquals(3, CommandHandler.helloProtocol(cmd("HELLO"), 3));
        assertEquals(3, CommandHandler.helloProtocol(cmd("HELLO 3"), 2));
        assertEquals(3, CommandHandler.helloProtocol(cmd(
            "HELLO 3 AUTH default anything SETNAME app"), 2));
        assertEquals(2, CommandHandler.helloProtocol(cmd("hello 2 setname x"),
            3));
        helloError("NOPROTO unsupported protocol version", "HELLO", "4");
        helloError("NOPROTO unsupported protocol version", "HELLO", "1");
        helloError("ERR Protocol version is not an integer or out of range",
            "HELLO", "three");
        helloError("ERR Syntax error in HELLO option 'FOO'", "HELLO", "3",
            "FOO");
        helloError("ERR Syntax error in HELLO option 'AUTH'", "HELLO", "3",
            "AUTH", "default");
        helloError("WRONGPASS invalid username-password pair or user is "
            + "disabled.", "HELLO", "3", "AUTH", "bob", "pw");
        helloError("ERR Client names cannot contain spaces, newlines or "
            + "special characters.", "HELLO", "3", "SETNAME", "a b");
    }


    private static void helloError(String expected, String... args) {
        try {
            CommandHandler.helloProtocol(Arrays.asList(args), 2);
        }
        catch (CommandHandler.CommandError e) {
            assertEquals(expected, e.getMessage());
            return;
        }
        throw new AssertionError("no error for " + Arrays.toString(args));
    }


    // ---- over the wire ----

    private SkipDBServer server;

    @Before
    public void start() throws IOException {
        server = new SkipDBServer(0).start();
    }


    @After
    public void stop() throws IOException {
        server.close();
    }


    /** Sends commands and returns the raw reply bytes as a string. */
    private static final class Raw implements AutoCloseable {
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;

        Raw(int port) throws IOException {
            s = new Socket("localhost", port);
            s.setSoTimeout(5000);
            in = new BufferedInputStream(s.getInputStream());
            out = s.getOutputStream();
        }


        String call(String... args) throws IOException {
            StringBuilder sb = new StringBuilder("*" + args.length + "\r\n");
            for (String a : args) {
                sb.append('$').append(a.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n").append(a).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            StringBuilder r = new StringBuilder();
            readReply(r);
            return r.toString();
        }


        private void readReply(StringBuilder r) throws IOException {
            String line = readLine();
            r.append(line).append("\r\n");
            char t = line.charAt(0);
            int n = t == '$' || t == '=' || t == '*' || t == '%'
                ? Integer.parseInt(line.substring(1))
                : 0;
            if ((t == '$' || t == '=') && n >= 0) {
                r.append(new String(in.readNBytes(n + 2),
                    StandardCharsets.UTF_8));
            }
            else if (t == '*' || t == '%') {
                for (int i = 0; i < (t == '%' ? 2 * n : n); i++) {
                    readReply(r);
                }
            }
        }


        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != '\n') {
                if (c == -1) {
                    throw new IOException("closed");
                }
                if (c != '\r') {
                    sb.append((char)c);
                }
            }
            return sb.toString();
        }


        @Override
        public void close() throws IOException {
            s.close();
        }
    }

    @Test
    public void helloSwitchesOnlyThisConnection() throws IOException {
        try (Raw a = new Raw(server.port()); Raw b = new Raw(server.port())) {
            a.call("ZADD", "z", "1", "a", "2.5", "b");
            String hello = a.call("HELLO", "3");
            assertTrue(hello, hello.startsWith(
                "%7\r\n$6\r\nserver\r\n$6\r\nskipdb\r\n"));
            assertTrue(hello, hello.contains("$5\r\nproto\r\n:3\r\n"));
            assertEquals(",2.5\r\n", a.call("ZSCORE", "z", "b"));
            assertEquals("_\r\n", a.call("GET", "nope"));
            assertEquals("*2\r\n*2\r\n$1\r\na\r\n,1\r\n*2\r\n$1\r\nb\r\n,2.5\r\n",
                a.call("ZRANGE", "z", "0", "-1", "WITHSCORES"));
            assertEquals("*2\r\n_\r\n,1\r\n", a.call("ZMSCORE", "z", "x",
                "a"));
            assertTrue(a.call("INFO").startsWith("="));
            // the other connection is still RESP2
            assertEquals("$3\r\n2.5\r\n", b.call("ZSCORE", "z", "b"));
            assertEquals("$-1\r\n", b.call("GET", "nope"));
            assertEquals("*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$3\r\n2.5\r\n",
                b.call("ZRANGE", "z", "0", "-1", "WITHSCORES"));
            // a failed HELLO leaves the protocol as it was
            assertEquals("-NOPROTO unsupported protocol version\r\n", a.call(
                "HELLO", "4"));
            assertEquals(",2.5\r\n", a.call("ZSCORE", "z", "b"));
            // and HELLO 2 switches back
            assertTrue(a.call("HELLO", "2").startsWith("*14\r\n"));
            assertEquals("$-1\r\n", a.call("GET", "nope"));
        }
    }


    @Test
    public void connectionsGetDistinctIds() throws IOException {
        try (Raw a = new Raw(server.port()); Raw b = new Raw(server.port())) {
            String ha = a.call("HELLO");
            String hb = b.call("HELLO");
            String ida = ha.substring(ha.indexOf("$2\r\nid\r\n"));
            String idb = hb.substring(hb.indexOf("$2\r\nid\r\n"));
            assertTrue(ida + " vs " + idb, !ida.equals(idb));
        }
    }


    @Test
    public void transactionRepliesUseTheConnectionsProtocol()
        throws IOException {
        try (Raw a = new Raw(server.port())) {
            a.call("HELLO", "3");
            assertEquals("+OK\r\n", a.call("MULTI"));
            assertEquals("+QUEUED\r\n", a.call("ZADD", "t", "INCR", "4",
                "m"));
            assertEquals("+QUEUED\r\n", a.call("GET", "none"));
            assertEquals("*2\r\n,4\r\n_\r\n", a.call("EXEC"));
        }
    }
}
