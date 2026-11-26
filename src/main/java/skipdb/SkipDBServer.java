package skipdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server speaking RESP2 and RESP3, so redis-cli and Redis client libraries
 * can connect. Each connection starts in RESP2 and may switch with
 * {@code HELLO 3} (redis-py 8, Lettuce and others do on connect). Command
 * execution is serialized on a single lock (Redis's model: no data races, no
 * per-structure locking). MULTI/EXEC transactions queue commands per
 * connection and run them as one atomic batch.
 *
 * <p>Two I/O models ({@link Io}): {@code threads} (default) gives each client
 * a thread doing blocking reads; {@code nio} is Redis's model, one thread
 * multiplexing every socket through a {@link Selector}, decoding pipelined
 * requests incrementally and buffering replies until the socket can take
 * them.
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
 * java -cp target/classes skipdb.SkipDBServer [port] [--io threads|nio]
 *     [--aof appendonly.aof] [--appendfsync everysec]
 * redis-cli -p 6380 ZADD board 100 alice
 * </pre>
 */
public final class SkipDBServer implements AutoCloseable {

    private static final Resp.Simple QUEUED = new Resp.Simple("QUEUED");

    /** How client connections are served. */
    public enum Io {
        /** One blocking thread per client. */
        THREADS,
        /** One selector thread for all clients (Redis's event loop). */
        NIO;

        /**
         * Parses {@code threads} or {@code nio}, ignoring case.
         *
         * @throws IllegalArgumentException
         *             for any other value
         */
        public static Io parse(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "threads":
                    return THREADS;
                case "nio":
                    return NIO;
                default:
                    throw new IllegalArgumentException(
                        "--io must be threads or nio, got: " + s);
            }
        }
    }

    /**
     * Stop reading from a client while this many reply bytes wait to be
     * sent, so a client that pipelines without reading cannot grow the
     * output buffer without bound (Redis's client output buffer limit, but
     * pausing instead of disconnecting).
     */
    static final int OUTPUT_HIGH_WATER = 1 << 20;

    private final CommandHandler handler = new CommandHandler();
    private final Io io;
    private final ServerSocket server; // THREADS only
    private final ServerSocketChannel acceptor; // NIO only
    private final Selector selector; // NIO only
    private final Aof aof; // null when persistence is off
    private final ScheduledExecutorService cron; // active expiry + fsync
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "skipdb-client");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong clients = new AtomicLong();
    private final AtomicLong nextClientId = new AtomicLong();

    /** Per-connection state that commands can change. */
    private static class Conn {
        final long id;
        int proto = 2;
        /** MULTI/EXEC queue: null when not in a transaction. */
        List<List<String>> queued;
        /** Set by QUIT: close once the reply is sent. */
        boolean quit;

        Conn(long id) {
            this.id = id;
        }
    }

    /** Byte buffer whose backing array can be written to a channel directly. */
    private static final class OutBuf extends java.io.ByteArrayOutputStream {
        OutBuf(int size) {
            super(size);
        }


        ByteBuffer slice(int from) {
            return ByteBuffer.wrap(buf, from, count - from);
        }


        int capacity() {
            return buf.length;
        }
    }

    /** An event-loop connection: socket, request decoder and reply buffer. */
    private static final class NioConn extends Conn {
        final SocketChannel ch;
        final Resp.Decoder decoder = new Resp.Decoder();
        OutBuf out = new OutBuf(16 * 1024);
        int sent; // bytes of out already written to the socket
        boolean closing; // stop reading; close once out is drained

        NioConn(long id, SocketChannel ch) {
            super(id);
            this.ch = ch;
        }


        int pending() {
            return out.size() - sent;
        }
    }
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
        this(port, aofPath, fsync, Io.THREADS);
    }


    /**
     * Binds to the given port (0 picks a free port) using the given I/O
     * model, first replaying the append-only file at {@code aofPath} if it is
     * non-null.
     *
     * @param fsync
     *            the AOF fsync policy; ignored when {@code aofPath} is null
     * @param io
     *            thread-per-client or a single-threaded event loop
     * @throws IOException
     *             if the port cannot be bound or the AOF is unreadable/corrupt
     */
    public SkipDBServer(int port, Path aofPath, Aof.Fsync fsync, Io io)
        throws IOException {
        this.io = io;
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
        ServerSocketChannel acc = null;
        try {
            if (io == Io.NIO) {
                server = null;
                acc = ServerSocketChannel.open();
                acc.bind(new InetSocketAddress(port), 511);
                acc.configureBlocking(false);
                selector = Selector.open();
                acc.register(selector, SelectionKey.OP_ACCEPT);
                acceptor = acc;
            }
            else {
                server = new ServerSocket(port);
                acceptor = null;
                selector = null;
            }
        }
        catch (IOException e) {
            if (acc != null) {
                acc.close();
            }
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


    /** @return the port the server is bound to */
    public int port() {
        return io == Io.NIO
            ? acceptor.socket().getLocalPort()
            : server.getLocalPort();
    }


    /** @return the I/O model this server was started with */
    public Io io() {
        return io;
    }


    /**
     * Serves clients until {@link #close()} is called: accepts on this
     * thread ({@code threads}) or runs the event loop on it ({@code nio}).
     */
    public void serve() {
        if (io == Io.NIO) {
            eventLoop();
            return;
        }
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
        Thread t = new Thread(this::serve, io == Io.NIO
            ? "skipdb-eventloop"
            : "skipdb-accept");
        t.setDaemon(true);
        t.start();
        return this;
    }


    private void handle(Socket socket) {
        try (Socket s = socket;
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            Conn conn = new Conn(nextClientId.incrementAndGet());
            while (!conn.quit) {
                List<String> cmd;
                try {
                    cmd = Resp.readCommand(in);
                }
                catch (Resp.ProtocolException e) {
                    Resp.write(out, protocolError(e));
                    out.flush();
                    return;
                }
                if (cmd == null) {
                    return;
                }
                if (cmd.isEmpty()) {
                    continue;
                }
                Resp.write(out, process(cmd, conn), conn.proto);
                // only flush when no pipelined command is already waiting
                if (in.available() == 0 || conn.quit) {
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
     * Redis's event loop: one thread waits on a selector for sockets that are
     * acceptable, readable or writable. A read decodes every complete
     * pipelined command, runs them in order and appends their replies to the
     * connection's output buffer, which is then written without blocking;
     * whatever the socket does not take waits for OP_WRITE. While more than
     * {@link #OUTPUT_HIGH_WATER} bytes are queued the connection is not read
     * from and its buffered commands wait, so slow readers get backpressure.
     */
    private void eventLoop() {
        ByteBuffer readBuf = ByteBuffer.allocateDirect(64 * 1024);
        try {
            while (running) {
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        acceptAll();
                        continue;
                    }
                    NioConn c = (NioConn)key.attachment();
                    try {
                        if (key.isReadable()) {
                            onReadable(key, c, readBuf);
                        }
                        if (key.isValid() && key.isWritable()) {
                            flush(key, c);
                            if (!c.closing && c.pending() < OUTPUT_HIGH_WATER
                                && c.decoder.buffered() > 0) {
                                runBuffered(c); // resume after backpressure
                                flush(key, c);
                            }
                        }
                    }
                    catch (IOException | CancelledKeyException e) {
                        closeConn(key, c);
                    }
                    catch (RuntimeException e) {
                        System.err.println("client " + c.id + " failed: " + e);
                        closeConn(key, c);
                    }
                }
            }
        }
        catch (IOException e) {
            if (running) {
                System.err.println("event loop failed: " + e.getMessage());
            }
        }
        finally {
            for (SelectionKey key : selector.keys()) {
                if (key.attachment() instanceof NioConn) {
                    closeConn(key, (NioConn)key.attachment());
                }
            }
            try {
                selector.close();
            }
            catch (IOException e) {
                // shutting down anyway
            }
        }
    }


    private void acceptAll() {
        while (true) {
            SocketChannel ch;
            try {
                ch = acceptor.accept();
                if (ch == null) {
                    return;
                }
                ch.configureBlocking(false);
                ch.socket().setTcpNoDelay(true);
                NioConn c = new NioConn(nextClientId.incrementAndGet(), ch);
                ch.register(selector, SelectionKey.OP_READ, c);
                clients.incrementAndGet();
            }
            catch (IOException e) {
                if (running) {
                    System.err.println("accept failed: " + e.getMessage());
                }
                return;
            }
        }
    }


    private void onReadable(SelectionKey key, NioConn c, ByteBuffer readBuf)
        throws IOException {
        readBuf.clear();
        int n = c.ch.read(readBuf);
        if (n < 0) {
            // client closed its side: send what is queued, then close
            c.closing = true;
        }
        else if (n > 0) {
            readBuf.flip();
            c.decoder.feed(readBuf);
            runBuffered(c);
        }
        flush(key, c);
    }


    /** Runs buffered commands until none is complete or output backs up. */
    private void runBuffered(NioConn c) {
        while (!c.closing && c.pending() < OUTPUT_HIGH_WATER) {
            List<String> cmd;
            try {
                cmd = c.decoder.next();
            }
            catch (Resp.ProtocolException e) {
                writeReply(c, protocolError(e));
                c.closing = true;
                return;
            }
            if (cmd == null) {
                return;
            }
            if (cmd.isEmpty()) {
                continue;
            }
            writeReply(c, process(cmd, c));
            if (c.quit) {
                c.closing = true;
            }
        }
    }


    private static void writeReply(NioConn c, Object reply) {
        try {
            Resp.write(c.out, reply, c.proto);
        }
        catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen for a byte buffer
        }
    }


    /**
     * Writes as much queued output as the socket takes, then sets the
     * interest ops: OP_WRITE while output remains, OP_READ unless closing or
     * over the high-water mark. Closes the connection once a closing
     * connection's output is drained.
     */
    private void flush(SelectionKey key, NioConn c) throws IOException {
        if (c.pending() > 0) {
            int wrote = c.ch.write(c.out.slice(c.sent));
            c.sent += wrote;
            if (c.pending() == 0) {
                if (c.out.capacity() > OUTPUT_HIGH_WATER) {
                    c.out = new OutBuf(16 * 1024); // give a big reply's memory back
                }
                else {
                    c.out.reset();
                }
                c.sent = 0;
            }
        }
        if (c.closing && c.pending() == 0) {
            closeConn(key, c);
            return;
        }
        int ops = 0;
        if (c.pending() > 0) {
            ops |= SelectionKey.OP_WRITE;
        }
        if (!c.closing && c.pending() < OUTPUT_HIGH_WATER) {
            ops |= SelectionKey.OP_READ;
        }
        if (key.interestOps() != ops) {
            key.interestOps(ops);
        }
    }


    private void closeConn(SelectionKey key, NioConn c) {
        if (!key.isValid() && !c.ch.isOpen()) {
            return;
        }
        key.cancel();
        try {
            c.ch.close();
        }
        catch (IOException e) {
            // already gone
        }
        clients.decrementAndGet();
    }


    private static Resp.Error protocolError(Resp.ProtocolException e) {
        return new Resp.Error("ERR Protocol error: " + e.getMessage());
    }


    /**
     * Runs one non-empty command for a connection, handling the
     * connection-level commands (QUIT, MULTI/EXEC/DISCARD and queueing) and
     * taking the handler lock for everything else. Shared by both I/O models.
     *
     * @return the reply to send
     */
    private Object process(List<String> cmd, Conn conn) {
        String name = cmd.get(0).toLowerCase(Locale.ROOT);
        if (name.equals("quit")) {
            conn.quit = true;
            return Resp.OK;
        }
        if (name.equals("multi")) {
            if (conn.queued != null) {
                return new Resp.Error("ERR MULTI calls can not be nested");
            }
            conn.queued = new ArrayList<>();
            return Resp.OK;
        }
        if (name.equals("exec") || name.equals("discard")) {
            if (conn.queued == null) {
                return new Resp.Error("ERR " + name.toUpperCase(Locale.ROOT)
                    + " without MULTI");
            }
            List<List<String>> queued = conn.queued;
            conn.queued = null;
            if (name.equals("discard")) {
                return Resp.OK;
            }
            // run the whole batch under one lock: atomic w.r.t. other clients
            List<Object> results = new ArrayList<>(queued.size());
            synchronized (handler) {
                List<List<String>> writes = new ArrayList<>();
                for (List<String> q : queued) {
                    results.add(run(q, conn));
                    writes.addAll(handler.drainPropagated());
                }
                if (aof != null) {
                    logToAof(writes);
                }
            }
            return results;
        }
        if (conn.queued != null) {
            conn.queued.add(cmd);
            return QUEUED;
        }
        synchronized (handler) {
            Object reply = run(cmd, conn);
            if (aof != null) {
                logToAof(handler.drainPropagated());
            }
            return reply;
        }
    }


    /**
     * Executes one command; caller holds the handler lock. Server-level
     * commands that need the AOF or the connection are handled here, the
     * rest by the handler.
     */
    private Object run(List<String> cmd, Conn conn) {
        if (cmd.get(0).equalsIgnoreCase("hello")) {
            try {
                conn.proto = CommandHandler.helloProtocol(cmd, conn.proto);
            }
            catch (CommandHandler.CommandError e) {
                return new Resp.Error(e.getMessage());
            }
            return CommandHandler.helloReply(conn.proto, conn.id);
        }
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
        if (io == Io.NIO) {
            acceptor.close();
            selector.wakeup(); // the loop closes the clients and selector
        }
        else {
            server.close();
        }
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
        Io io = Io.THREADS;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--aof") && i + 1 < args.length) {
                aofPath = Paths.get(args[++i]);
            }
            else if (args[i].equals("--appendfsync") && i + 1 < args.length) {
                fsync = Aof.Fsync.parse(args[++i]);
            }
            else if (args[i].equals("--io") && i + 1 < args.length) {
                io = Io.parse(args[++i]);
            }
            else {
                port = Integer.parseInt(args[i]);
            }
        }
        SkipDBServer srv = new SkipDBServer(port, aofPath, fsync, io);
        System.out.println("SkipDB listening on port " + srv.port() + " ("
            + io.name().toLowerCase(Locale.ROOT) + " I/O)"
            + "  (try: redis-cli -p " + srv.port() + " ping)");
        srv.serve();
    }
}
