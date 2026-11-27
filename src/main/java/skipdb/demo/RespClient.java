package skipdb.demo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal blocking RESP2 client, enough for the leaderboard demo to talk to
 * SkipDB (or a real Redis) over the wire like any other client would. Calls
 * are synchronized, so one instance may be shared between threads.
 *
 * <p>Replies decode to {@code String} (simple and bulk strings), {@code Long}
 * (integers), {@code null} (null bulk/array) and {@code List<Object>}
 * (arrays); an error reply throws {@link ServerError}, or appears as a
 * {@code ServerError} element when nested in an array.
 */
public final class RespClient implements AutoCloseable {

    /** An error reply ({@code -ERR ...}) from the server. */
    public static final class ServerError extends IOException {
        private static final long serialVersionUID = 1L;

        /**
         * @param message
         *            the error text without the leading '-'
         */
        public ServerError(String message) {
            super(message);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    /**
     * Connects to a RESP server.
     *
     * @throws IOException
     *             if the connection fails
     */
    public RespClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }


    /**
     * Sends one command as an array of bulk strings and reads its reply.
     *
     * @return the decoded reply
     * @throws ServerError
     *             if the server answered with an error
     * @throws IOException
     *             on a connection or protocol failure
     */
    public synchronized Object call(String... args) throws IOException {
        out.write(encode(args));
        out.flush();
        return readReply(in);
    }


    /** @return the command as a RESP array of bulk strings */
    static byte[] encode(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ascii(buf, "*" + args.length + "\r\n");
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            ascii(buf, "$" + b.length + "\r\n");
            buf.write(b, 0, b.length);
            ascii(buf, "\r\n");
        }
        return buf.toByteArray();
    }


    private static void ascii(ByteArrayOutputStream buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        buf.write(b, 0, b.length);
    }


    /**
     * Reads one RESP2 reply.
     *
     * @throws ServerError
     *             for an error reply (after consuming it)
     */
    static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("connection closed");
        }
        String line = readLine(in);
        switch (type) {
            case '+':
                return line;
            case '-':
                throw new ServerError(line);
            case ':':
                return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] b = in.readNBytes(len + 2); // payload + CRLF
                if (b.length < len + 2) {
                    throw new EOFException("truncated bulk string");
                }
                return new String(b, 0, len, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) {
                    return null;
                }
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    try {
                        items.add(readReply(in));
                    }
                    catch (ServerError e) {
                        items.add(e); // e.g. inside EXEC: keep the stream in sync
                    }
                }
                return items;
            }
            default:
                throw new IOException("unexpected reply type '" + (char)type
                    + "'");
        }
    }


    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] b = buf.toByteArray();
                int len = (b.length > 0 && b[b.length - 1] == '\r')
                    ? b.length - 1
                    : b.length;
                return new String(b, 0, len, StandardCharsets.UTF_8);
            }
            buf.write(c);
        }
        throw new EOFException("connection closed");
    }


    @Override
    public void close() throws IOException {
        socket.close();
    }
}
