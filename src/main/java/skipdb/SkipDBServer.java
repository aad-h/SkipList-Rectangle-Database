package skipdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server speaking RESP2, so redis-cli and Redis client libraries can
 * connect. One thread per client; command execution is serialized on a single
 * lock (Redis's model: no data races, no per-structure locking). MULTI/EXEC
 * transactions queue commands per connection and run them as one atomic batch.
 *
 * <p>With an append-only file ({@code --aof path}) every successful write is
 * logged before its reply is sent, and the file is replayed on startup.
 * {@code --appendfsync always|everysec|no} picks when it is fsynced (default
 * {@code everysec}), and {@code BGREWRITEAOF} compacts it in the background.
 *
 * <p>A cron thread runs Redis's active expiry ten times a second, deleting
 * keys whose TTL has passed even if no client touches them again.
 *
 * <pre>
 * java -cp target/classes skipdb.SkipDBServer [port] [--aof appendonly.aof]
 *     [--appendfsync everysec]
 * redis-cli -p 6380 ZADD board 100 alice
 * </pre>
 */
public final class SkipDBServer implements AutoCloseable {

    private static final Resp.Simple QUEUED = new Resp.Simple("QUEUED");

    private final CommandHandler handler = new CommandHandler();
    private final ServerSocket server;
    private final Aof aof; // null when persistence is off
    private final ScheduledExecutorService cron; // active expiry + fsync
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
        this(port, null);
    }


    /**
     * Binds to the given port (0 picks a free port), first replaying the
     * append-only file at {@code aofPath} if it is non-null.
     *
     * @throws IOException
     *             if the port cannot be bound or the AOF is unreadable/corrupt
     */
    public SkipDBServer(int port, Path aofPath) throws IOException {
        this(port, aofPath, Aof.Fsync.EVERYSEC);
    }


    /**
     * Binds to the given port (0 picks a free port), first replaying the
     * append-only file at {@code aofPath} if it is non-null.
     *
     * @param fsync
     *            the AOF fsync policy; ignored when {@code aofPath} is null
     * @throws IOException
     *             if the port cannot be bound or the AOF is unreadable/corrupt
     */
    public SkipDBServer(int port, Path aofPath, Aof.Fsync fsync)
        throws IOException {
        if (aofPath != null) {
            aof = new Aof(aofPath, fsync);
            long n = aof.load(handler);
            if (n > 0) {
                System.out.println("AOF: loaded " + n + " commands from "
                    + aofPath);
            }
        }
        else {
            aof = null;
        }
        handler.setPropagation(aof != null);
        try {
            server = new ServerSocket(port);
        }
        catch (IOException e) {
            if (aof != null) {
                aof.close();
            }
            throw e;
        }
        cron = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skipdb-cron");
            t.setDaemon(true);
            return t;
        });
        cron.scheduleWithFixedDelay(this::expireTick, EXPIRE_PERIOD_MS,
            EXPIRE_PERIOD_MS, TimeUnit.MILLISECONDS);
        if (aof != null && fsync == Aof.Fsync.EVERYSEC) {
            cron.scheduleWithFixedDelay(this::fsyncTick, 1, 1,
                TimeUnit.SECONDS);
        }
    }


    /** Active expiry runs every 100ms, Redis's default {@code hz 10}. */
    static final long EXPIRE_PERIOD_MS = 100;

    private void expireTick() {
        try {
            synchronized (handler) {
                handler.activeExpireCycle();
                if (aof != null) {
                    logToAof(handler.drainPropagated());
                }
            }
        }
        catch (RuntimeException e) {
            // never let one failure cancel the periodic task
            System.err.println("active expire failed: " + e);
        }
    }


    private void fsyncTick() {
        try {
            aof.fsyncIfDirty();
        }
        catch (IOException e) {
            System.err.println("AOF: fsync of " + aof.path() + " failed: " + e
                .getMessage());
        }
    }


    /** @return the AOF, or null when persistence is off */
    public Aof aof() {
        return aof;
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
                            List<List<String>> writes = new ArrayList<>();
                            for (List<String> q : queued) {
                                results.add(run(q));
                                writes.addAll(handler.drainPropagated());
                            }
                            if (aof != null) {
                                logToAof(writes);
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
                        reply = run(cmd);
                        if (aof != null) {
                            logToAof(handler.drainPropagated());
                        }
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


    /**
     * Executes one command; caller holds the handler lock. Server-level
     * commands that need the AOF are handled here, the rest by the handler.
     */
    private Object run(List<String> cmd) {
        if (cmd.get(0).equalsIgnoreCase("bgrewriteaof")) {
            if (cmd.size() != 1) {
                return new Resp.Error(
                    "ERR wrong number of arguments for 'bgrewriteaof' command");
            }
            if (aof == null) {
                return new Resp.Error(
                    "ERR Append only file is not enabled (start with --aof)");
            }
            if (aof.rewriteInProgress()) {
                return new Resp.Error(
                    "ERR Background append only file rewriting already in progress");
            }
            // snapshot and start under the lock: later writes hit the buffer
            aof.rewrite(handler.rewriteCommands());
            return new Resp.Simple(
                "Background append only file rewriting started");
        }
        return handler.execute(cmd);
    }


    private void logToAof(List<List<String>> writes) {
        try {
            aof.appendTransaction(writes);
        }
        catch (IOException e) {
            System.err.println("AOF: write to " + aof.path() + " failed: " + e
                .getMessage());
        }
    }


    /** @return the number of clients currently connected */
    public long connectedClients() {
        return clients.get();
    }


    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        pool.shutdownNow();
        cron.shutdownNow();
        if (aof != null) {
            synchronized (handler) {
                aof.close();
            }
        }
    }


    public static void main(String[] args) throws IOException {
        int port = 6380;
        Path aofPath = null;
        Aof.Fsync fsync = Aof.Fsync.EVERYSEC;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--aof") && i + 1 < args.length) {
                aofPath = Paths.get(args[++i]);
            }
            else if (args[i].equals("--appendfsync") && i + 1 < args.length) {
                fsync = Aof.Fsync.parse(args[++i]);
            }
            else {
                port = Integer.parseInt(args[i]);
            }
        }
        SkipDBServer srv = new SkipDBServer(port, aofPath, fsync);
        System.out.println("SkipDB listening on port " + srv.port()
            + "  (try: redis-cli -p " + srv.port() + " ping)");
        srv.serve();
    }
}
