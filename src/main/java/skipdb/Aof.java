package skipdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Append-only file persistence, Redis-style. Every successful write command is
 * appended to the file as a RESP array (exactly what a client would send), and
 * on startup the file is replayed through a {@link CommandHandler} to rebuild
 * the keyspace.
 *
 * <p>Transactions are logged wrapped in {@code MULTI} / {@code EXEC}; on replay
 * a transaction is applied only once its {@code EXEC} is read, so a crash in
 * the middle of writing one never leaves half of it applied. A file whose last
 * command was cut short (crash mid-write) is truncated back to the last
 * complete command, like Redis's default {@code aof-load-truncated yes}; any
 * other malformed content aborts loading.
 *
 * <p>Writes are always flushed to the OS before the reply is sent; when they
 * reach the disk depends on the {@link Fsync} policy ({@code appendfsync} in
 * Redis). With {@code everysec} the owner calls {@link #fsyncIfDirty()} once a
 * second from a background thread.
 *
 * <p>{@link #rewrite} compacts the file in the background the way Redis's
 * {@code BGREWRITEAOF} does: a snapshot of the minimal command set is written
 * to a temporary file while new writes keep going to the old file and are also
 * buffered; the buffer is then appended to the new file, which is fsynced and
 * atomically renamed over the old one.
 *
 * <p>All public methods are thread-safe.
 */
public final class Aof implements AutoCloseable {

    /** When appended data is fsynced to disk ({@code appendfsync}). */
    public enum Fsync {
        /** fsync after every write command, before the reply is sent */
        ALWAYS,
        /** fsync at most once a second, off the command path (Redis's default) */
        EVERYSEC,
        /** never fsync; leave it to the OS */
        NO;

        /**
         * @param s
         *            {@code always}, {@code everysec} or {@code no}, any case
         * @return the policy
         * @throws IllegalArgumentException
         *             for any other value
         */
        public static Fsync parse(String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "always":
                    return ALWAYS;
                case "everysec":
                    return EVERYSEC;
                case "no":
                    return NO;
                default:
                    throw new IllegalArgumentException(
                        "appendfsync must be always, everysec or no: " + s);
            }
        }
    }

    private final Path path;
    private final Fsync fsync;
    private FileOutputStream file;
    private OutputStream out;
    private boolean dirty; // written since the last fsync
    private boolean closed;
    private long fsyncs;
    private long rewrites;
    // non-null while a rewrite runs: writes made since its snapshot was taken
    private ByteArrayOutputStream rewriteBuffer;
    /** Test hook run on the rewrite thread after the snapshot is written. */
    volatile Runnable afterSnapshotHook;

    /**
     * Opens an AOF with the {@code everysec} fsync policy.
     *
     * @param path
     *            the append-only file; created on first write if missing
     */
    public Aof(Path path) {
        this(path, Fsync.EVERYSEC);
    }


    /**
     * @param path
     *            the append-only file; created on first write if missing
     * @param fsync
     *            when appended data is forced to disk
     */
    public Aof(Path path, Fsync fsync) {
        this.path = path;
        this.fsync = fsync;
    }


    /** @return the file this AOF reads and appends to */
    public Path path() {
        return path;
    }


    /** @return the fsync policy */
    public Fsync fsyncPolicy() {
        return fsync;
    }

    /**
     * Replays the file into {@code handler}, truncating a partial trailing
     * command, then opens the file for appending.
     *
     * @return the number of commands applied (MULTI/EXEC markers excluded)
     * @throws IOException
     *             if the file cannot be read or is corrupt
     */
    public long load(CommandHandler handler) throws IOException {
        long applied = 0;
        if (Files.exists(path)) {
            long validBytes = 0;
            List<List<String>> tx = null;
            try (CountingInputStream in = new CountingInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
                while (true) {
                    List<String> cmd;
                    try {
                        cmd = Resp.readCommand(in);
                    }
                    catch (EOFException e) {
                        System.err.println("AOF: " + path
                            + " ends with a truncated command; truncating to "
                            + validBytes + " bytes");
                        break;
                    }
                    catch (Resp.ProtocolException e) {
                        throw new IOException(
                            "Bad file format reading the append only file "
                                + path + " at byte " + validBytes + ": " + e
                                    .getMessage(), e);
                    }
                    if (cmd == null) {
                        break;
                    }
                    if (cmd.isEmpty()) {
                        validBytes = in.count;
                        continue;
                    }
                    String name = cmd.get(0).toLowerCase(Locale.ROOT);
                    if (name.equals("multi")) {
                        tx = new ArrayList<>();
                    }
                    else if (name.equals("exec")) {
                        if (tx == null) {
                            throw new IOException(
                                "Bad file format reading the append only file "
                                    + path + ": EXEC without MULTI");
                        }
                        for (List<String> q : tx) {
                            handler.execute(q);
                        }
                        applied += tx.size();
                        tx = null;
                    }
                    else if (tx != null) {
                        tx.add(cmd);
                    }
                    else {
                        handler.execute(cmd);
                        applied++;
                    }
                    // only a fully applied position is safe to truncate back to
                    if (tx == null) {
                        validBytes = in.count;
                    }
                }
            }
            if (tx != null) {
                System.err.println("AOF: " + path
                    + " ends inside an unfinished MULTI; discarding it");
            }
            if (Files.size(path) > validBytes) {
                try (FileChannel ch = FileChannel.open(path,
                    StandardOpenOption.WRITE)) {
                    ch.truncate(validBytes);
                }
            }
        }
        synchronized (this) {
            open();
        }
        return applied;
    }


    private void open() throws IOException {
        file = new FileOutputStream(path.toFile(), true);
        out = new BufferedOutputStream(file);
    }


    /**
     * Appends one write command and flushes it to the OS (and to disk under
     * {@code always}).
     */
    public synchronized void append(List<String> cmd) throws IOException {
        writeCommand(cmd);
        commit();
    }


    /**
     * Appends the write commands of one transaction wrapped in MULTI/EXEC, or
     * nothing if there are none. A single command is logged without the wrapper.
     */
    public synchronized void appendTransaction(List<List<String>> cmds)
        throws IOException {
        if (cmds.isEmpty()) {
            return;
        }
        if (cmds.size() == 1) {
            append(cmds.get(0));
            return;
        }
        writeCommand(Arrays.asList("MULTI"));
        for (List<String> c : cmds) {
            writeCommand(c);
        }
        writeCommand(Arrays.asList("EXEC"));
        commit();
    }


    private void writeCommand(List<String> cmd) throws IOException {
        if (out == null) {
            throw new IOException("AOF is not open");
        }
        Resp.write(out, cmd);
        if (rewriteBuffer != null) {
            Resp.write(rewriteBuffer, cmd);
        }
    }


    private void commit() throws IOException {
        out.flush();
        dirty = true;
        if (fsync == Fsync.ALWAYS) {
            forceNow();
        }
    }


    private void forceNow() throws IOException {
        file.getChannel().force(false);
        dirty = false;
        fsyncs++;
    }


    /**
     * fsyncs the file if anything was written since the last fsync (never
     * under the {@code no} policy). The {@code everysec} timer calls this; the disk wait happens outside the
     * lock, so writers are not blocked by a slow fsync.
     *
     * @return true if an fsync was issued
     */
    public boolean fsyncIfDirty() throws IOException {
        FileChannel ch;
        synchronized (this) {
            if (fsync == Fsync.NO || !dirty || closed || file == null) {
                return false;
            }
            ch = file.getChannel();
            dirty = false;
            fsyncs++;
        }
        try {
            ch.force(false);
        }
        catch (ClosedChannelException e) {
            // file was swapped by a rewrite (which fsyncs the new one) or closed
        }
        return true;
    }


    /** @return how many fsyncs have been issued */
    public synchronized long fsyncCount() {
        return fsyncs;
    }


    /** @return true while a background rewrite is running */
    public synchronized boolean rewriteInProgress() {
        return rewriteBuffer != null;
    }


    /** @return how many rewrites have completed successfully */
    public synchronized long rewriteCount() {
        return rewrites;
    }


    /**
     * Starts a background rewrite of the file from {@code snapshot}, the
     * minimal command set that rebuilds the current keyspace (see
     * {@link CommandHandler#rewriteCommands()}). The caller must take the
     * snapshot and call this without any write in between (the server holds
     * its command lock across both), so every later write lands in the buffer.
     *
     * @return a future completed once the new file has replaced the old one,
     *         or completed exceptionally if the rewrite failed (the old file is
     *         then left untouched and still in use)
     * @throws IllegalStateException
     *             if a rewrite is already running or the AOF is not open
     */
    public synchronized CompletableFuture<Void> rewrite(
        List<List<String>> snapshot) {
        if (rewriteBuffer != null) {
            throw new IllegalStateException(
                "Background append only file rewriting already in progress");
        }
        if (out == null) {
            throw new IllegalStateException("AOF is not open");
        }
        rewriteBuffer = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread t = new Thread(() -> runRewrite(snapshot, done),
            "skipdb-aof-rewrite");
        t.setDaemon(true);
        t.start();
        return done;
    }


    private void runRewrite(List<List<String>> snapshot,
        CompletableFuture<Void> done) {
        Path tmp = path.resolveSibling("temp-rewriteaof-" + path.getFileName());
        try {
            try (FileOutputStream f = new FileOutputStream(tmp.toFile());
                OutputStream o = new BufferedOutputStream(f, 1 << 16)) {
                for (List<String> cmd : snapshot) {
                    Resp.write(o, cmd);
                }
                o.flush();
                f.getChannel().force(false);
            }
            Runnable hook = afterSnapshotHook;
            if (hook != null) {
                hook.run();
            }
            synchronized (this) {
                if (closed) {
                    throw new IOException("AOF closed during rewrite");
                }
                // writes made while the snapshot was being written
                try (FileOutputStream f = new FileOutputStream(tmp.toFile(),
                    true)) {
                    rewriteBuffer.writeTo(f);
                    f.getChannel().force(false);
                }
                out.flush();
                out.close();
                out = null;
                file = null;
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
                fsyncDirectory();
                open();
                dirty = false;
                rewriteBuffer = null;
                rewrites++;
            }
            done.complete(null);
        }
        catch (IOException | RuntimeException e) {
            synchronized (this) {
                rewriteBuffer = null;
                if (!closed && out == null) {
                    // failed after closing the old stream: reopen it
                    try {
                        open();
                    }
                    catch (IOException reopen) {
                        e.addSuppressed(reopen);
                    }
                }
            }
            try {
                Files.deleteIfExists(tmp);
            }
            catch (IOException ignored) {
                // best effort
            }
            System.err.println("AOF: background rewrite of " + path
                + " failed: " + e.getMessage());
            done.completeExceptionally(e);
        }
    }


    /** Makes the rename durable; not supported on every platform. */
    private void fsyncDirectory() {
        Path dir = path.toAbsolutePath().getParent();
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
        catch (IOException e) {
            // e.g. Windows cannot open a directory; the rename still happened
        }
    }


    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (out != null) {
            try {
                out.flush();
                if (fsync != Fsync.NO && dirty) {
                    forceNow();
                }
            }
            finally {
                out.close();
                out = null;
                file = null;
            }
        }
    }

    /** Tracks how many bytes have been consumed, to find the last good offset. */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }


        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }


        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }


        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }
    }
}
