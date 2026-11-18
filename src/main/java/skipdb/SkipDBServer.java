package skipdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server speaking RESP2, so redis-cli and Redis client libraries can
 * connect. One thread per client; command execution is serialized on a single
 * lock (Redis's model: no data races, no per-structure locking). MULTI/EXEC
 * transactions queue commands per connection and run them as one atomic batch.
 *
 * <pre>
 * java -cp target/classes skipdb.SkipDBServer [port]   # default 6380
 * redis-cli -p 6380 ZADD board 100 alice
 * </pre>
 */
public final class SkipDBServer implements AutoCloseable {

    private static final Resp.Simple QUEUED = new Resp.Simple("QUEUED");

    private final CommandHandler handler = new CommandHandler();
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skipdb-client");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong clients = new AtomicLong();
    private volatile boolean running = true;

    /**
     * Binds to the given port (0 picks a free port).
     */
    public SkipDBServer(int port) throws IOException {
        server = new ServerSocket(port);
    }


    public int port() {
        return server.getLocalPort();
    }


    /** Accepts clients until {@link #close()} is called. */
    public void serve() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                clients.incrementAndGet();
                pool.execute(() -> handle(s));
            }
            catch (IOException e) {
                if (running) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }


    /** Starts serving on a background thread and returns immediately. */
    public SkipDBServer start() {
        Thread t = new Thread(this::serve, "skipdb-accept");
        t.setDaemon(true);
        t.start();
        return this;
    }


    private void handle(Socket socket) {
        try (Socket s = socket;
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            // MULTI/EXEC state for this connection: null when not in a transaction
            List<List<String>> queued = null;
            while (true) {
                List<String> cmd;
                try {
                    cmd = Resp.readCommand(in);
                }
                catch (Resp.ProtocolException e) {
                    Resp.write(out, new Resp.Error("ERR Protocol error: " + e
                        .getMessage()));
                    out.flush();
                    return;
                }
                if (cmd == null) {
                    return;
                }
                if (cmd.isEmpty()) {
                    continue;
                }
                if (cmd.get(0).toLowerCase(Locale.ROOT).equals("quit")) {
                    Resp.write(out, Resp.OK);
                    out.flush();
                    return;
                }
                String name = cmd.get(0).toLowerCase(Locale.ROOT);
                Object reply;
                if (name.equals("multi")) {
                    if (queued != null) {
                        reply = new Resp.Error("ERR MULTI calls can not be nested");
                    }
                    else {
                        queued = new ArrayList<>();
                        reply = Resp.OK;
                    }
                }
                else if (name.equals("exec") || name.equals("discard")) {
                    if (queued == null) {
                        reply = new Resp.Error("ERR " + name.toUpperCase(
                            Locale.ROOT) + " without MULTI");
                    }
                    else if (name.equals("discard")) {
                        queued = null;
                        reply = Resp.OK;
                    }
                    else {
                        // run the whole batch under one lock: atomic w.r.t. other clients
                        List<Object> results = new ArrayList<>(queued.size());
                        synchronized (handler) {
                            for (List<String> q : queued) {
                                results.add(handler.execute(q));
                            }
                        }
                        queued = null;
                        reply = results;
                    }
                }
                else if (queued != null) {
                    queued.add(cmd);
                    reply = QUEUED;
                }
                else {
                    synchronized (handler) {
                        reply = handler.execute(cmd);
                    }
                }
                Resp.write(out, reply);
                // only flush when no pipelined command is already waiting
                if (in.available() == 0) {
                    out.flush();
                }
            }
        }
        catch (IOException e) {
            // client went away; nothing to do
        }
        finally {
            clients.decrementAndGet();
        }
    }


    public long connectedClients() {
        return clients.get();
    }


    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        pool.shutdownNow();
    }


    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6380;
        SkipDBServer srv = new SkipDBServer(port);
        System.out.println("SkipDB listening on port " + srv.port()
            + "  (try: redis-cli -p " + srv.port() + " ping)");
        srv.serve();
    }
}
