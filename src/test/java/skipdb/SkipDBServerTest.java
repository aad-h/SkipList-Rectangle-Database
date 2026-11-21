package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** End-to-end tests over a real TCP socket. */
public class SkipDBServerTest {

    private SkipDBServer server;

    @Before
    public void start() throws IOException {
        server = new SkipDBServer(0).start();
    }


    @After
    public void stop() throws IOException {
        server.close();
    }


    /** Minimal RESP client: sends a command, reads one raw reply. */
    private static final class Client implements AutoCloseable {
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
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
            return readReply();
        }


        String readReply() throws IOException {
            String line = readLine();
            char t = line.charAt(0);
            if (t == '$') {
                int n = Integer.parseInt(line.substring(1));
                if (n < 0) {
                    return "(nil)";
                }
                String v = new String(in.readNBytes(n), StandardCharsets.UTF_8);
                readLine();
                return v;
            }
            if (t == '*') {
                int n = Integer.parseInt(line.substring(1));
                List<String> items = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    items.add(readReply());
                }
                return items.toString();
            }
            return line; // +OK, -ERR..., :1
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


        void sendRaw(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }


        @Override
        public void close() throws IOException {
            s.close();
        }
    }

    @Test
    public void leaderboardOverTheWire() throws IOException {
        try (Client c = new Client(server.port())) {
            assertEquals("+PONG", c.call("PING"));
            assertEquals(":3", c.call("ZADD", "board", "100", "alice", "85",
                "bob", "92", "carol"));
            assertEquals("[alice, 100, carol, 92]", c.call("ZREVRANGE", "board",
                "0", "1", "WITHSCORES"));
            assertEquals(":2", c.call("ZRANK", "board", "alice"));
            assertEquals("(nil)", c.call("ZSCORE", "board", "ghost"));
            assertEquals("-ERR unknown command 'nope'", c.call("NOPE"));
            assertEquals("+OK", c.call("QUIT"));
        }
    }


    @Test
    public void inlineAndPipelinedCommands() throws IOException {
        try (Client c = new Client(server.port())) {
            c.sendRaw("SET a 1\r\nGET a\r\n*1\r\n$4\r\nPING\r\n");
            assertEquals("+OK", c.readReply());
            assertEquals("1", c.readReply());
            assertEquals("+PONG", c.readReply());
        }
    }


    @Test
    public void protocolErrorClosesOnlyThatClient() throws IOException {
        try (Client bad = new Client(server.port());
            Client good = new Client(server.port())) {
            bad.sendRaw("*1\r\n$abc\r\n");
            assertEquals("-ERR Protocol error: invalid length: abc", bad
                .readReply());
            assertEquals("+PONG", good.call("PING"));
        }
    }


    @Test
    public void concurrentClientsDoNotLoseUpdates() throws Exception {
        int clients = 8;
        int each = 500;
        ExecutorService ex = Executors.newFixedThreadPool(clients);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < clients; t++) {
            fs.add(ex.submit(() -> {
                try (Client c = new Client(server.port())) {
                    for (int i = 0; i < each; i++) {
                        c.call("ZINCRBY", "hits", "1", "page");
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        ex.shutdown();
        try (Client c = new Client(server.port())) {
            assertEquals(String.valueOf(clients * each), c.call("ZSCORE",
                "hits", "page"));
        }
    }

    @Test
    public void multiExecRunsQueuedCommandsAtomically() throws IOException {
        try (Client c = new Client(server.port())) {
            assertEquals("-ERR EXEC without MULTI", c.call("EXEC"));
            assertEquals("+OK", c.call("MULTI"));
            assertEquals("-ERR MULTI calls can not be nested", c.call("MULTI"));
            assertEquals("+QUEUED", c.call("ZADD", "z", "1", "a"));
            assertEquals("+QUEUED", c.call("ZINCRBY", "z", "5", "a"));
            assertEquals("+QUEUED", c.call("NOPE"));
            assertEquals("[:1, 6, -ERR unknown command 'nope']", c.call("EXEC"));
            assertEquals("+OK", c.call("MULTI"));
            assertEquals("+QUEUED", c.call("DEL", "z"));
            assertEquals("+OK", c.call("DISCARD"));
            assertEquals("6", c.call("ZSCORE", "z", "a"));
        }
    }


    @Test
    public void activeExpiryReclaimsKeysNobodyReads() throws Exception {
        try (Client c = new Client(server.port())) {
            assertEquals("+OK", c.call("SET", "session", "x", "PX", "150"));
            assertEquals("+OK", c.call("SET", "keep", "y"));
            assertEquals(":1", c.call("ZADD", "board", "1", "a"));
            assertEquals(":1", c.call("PEXPIRE", "board", "150"));
            long pttl = Long.parseLong(c.call("PTTL", "board").substring(1));
            assertTrue(String.valueOf(pttl), pttl > 0 && pttl <= 150);
            // DBSIZE does not expire keys itself, so only the cron can shrink it
            long deadline = System.currentTimeMillis() + 3000;
            String size = c.call("DBSIZE");
            while (!size.equals(":1") && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                size = c.call("DBSIZE");
            }
            assertEquals(":1", size);
            assertEquals("y", c.call("GET", "keep"));
            assertEquals(":-2", c.call("TTL", "session"));
        }
    }
}
