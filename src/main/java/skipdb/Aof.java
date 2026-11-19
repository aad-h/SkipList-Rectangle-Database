package skipdb;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
 * <p>Writes are flushed to the OS after every command (or transaction) but not
 * fsynced; fsync policies are a separate step. Not thread-safe: the server
 * calls it under the same lock that serializes command execution.
 */
public final class Aof implements AutoCloseable {

    private final Path path;
    private OutputStream out;

    /**
     * @param path
     *            the append-only file; created on first write if missing
     */
    public Aof(Path path) {
        this.path = path;
    }


    /** @return the file this AOF reads and appends to */
    public Path path() {
        return path;
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
        out = new BufferedOutputStream(Files.newOutputStream(path,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        return applied;
    }


    /**
     * Appends one write command and flushes it to the OS.
     */
    public void append(List<String> cmd) throws IOException {
        writeCommand(cmd);
        out.flush();
    }


    /**
     * Appends the write commands of one transaction wrapped in MULTI/EXEC, or
     * nothing if there are none. A single command is logged without the wrapper.
     */
    public void appendTransaction(List<List<String>> cmds) throws IOException {
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
        out.flush();
    }


    private void writeCommand(List<String> cmd) throws IOException {
        if (out == null) {
            throw new IOException("AOF is not open");
        }
        Resp.write(out, cmd);
    }


    @Override
    public void close() throws IOException {
        if (out != null) {
            out.close();
            out = null;
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
