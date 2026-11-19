package skipdb;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * RESP2 (REdis Serialization Protocol) reader and writer.
 *
 * <p>Requests are arrays of bulk strings ({@code *2\r\n$3\r\nGET\r\n$1\r\nk\r\n})
 * or inline commands ({@code GET k\r\n}, handy with telnet/nc). Replies are
 * written from plain Java values: String = bulk string, Long/Integer =
 * integer, List = array, null = null bulk, plus {@link Simple} and
 * {@link Error}.
 */
public final class Resp {
    private static final int MAX_BULK = 512 * 1024 * 1024;
    private static final int MAX_ARRAY = 1024 * 1024;

    private Resp() {
    }

    /** A simple string reply such as +OK. */
    public static final class Simple {
        public final String text;

        public Simple(String text) {
            this.text = text;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Simple && ((Simple)o).text.equals(text);
        }


        @Override
        public int hashCode() {
            return text.hashCode();
        }


        @Override
        public String toString() {
            return "+" + text;
        }
    }

    /** An error reply such as -ERR unknown command. */
    public static final class Error {
        public final String message;

        public Error(String message) {
            this.message = message;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Error && ((Error)o).message.equals(message);
        }


        @Override
        public int hashCode() {
            return message.hashCode();
        }


        @Override
        public String toString() {
            return "-" + message;
        }
    }

    public static final Simple OK = new Simple("OK");

    /** Thrown for malformed input; the connection should send an error and close. */
    public static final class ProtocolException extends IOException {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String msg) {
            super(msg);
        }
    }

    /**
     * Reads one command.
     *
     * @return the command and its arguments, an empty list for a blank inline
     *         line, or null at a clean end of stream
     */
    public static List<String> readCommand(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        if (first != '*') {
            String line = (char)first + readLine(in);
            String trimmed = line.trim();
            return trimmed.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
        }
        int n = parseInt(readLine(in));
        if (n < 0 || n > MAX_ARRAY) {
            throw new ProtocolException("invalid multibulk length");
        }
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int t = in.read();
            if (t == -1) {
                throw new EOFException();
            }
            if (t != '$') {
                throw new ProtocolException("expected '$'");
            }
            int len = parseInt(readLine(in));
            if (len < 0 || len > MAX_BULK) {
                throw new ProtocolException("invalid bulk length");
            }
            byte[] buf = in.readNBytes(len);
            if (buf.length < len) {
                throw new EOFException();
            }
            int cr = in.read();
            int lf = cr == -1 ? -1 : in.read();
            if (lf == -1) {
                throw new EOFException();
            }
            if (cr != '\r' || lf != '\n') {
                throw new ProtocolException("expected CRLF after bulk string");
            }
            args.add(new String(buf, StandardCharsets.UTF_8));
        }
        return args;
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
            if (buf.size() > 64 * 1024) {
                throw new ProtocolException("line too long");
            }
        }
        throw new EOFException();
    }


    private static int parseInt(String s) throws ProtocolException {
        try {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e) {
            throw new ProtocolException("invalid length: " + s);
        }
    }


    /** Encodes a reply value to bytes. */
    public static byte[] encode(Object reply) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out, reply);
        }
        catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen for a byte buffer
        }
        return out.toByteArray();
    }


    /** Writes a reply value. */
    public static void write(OutputStream out, Object reply) throws IOException {
        if (reply == null) {
            ascii(out, "$-1\r\n");
        }
        else if (reply instanceof Simple) {
            ascii(out, "+" + ((Simple)reply).text + "\r\n");
        }
        else if (reply instanceof Error) {
            ascii(out, "-" + ((Error)reply).message.replace("\r", " ").replace(
                "\n", " ") + "\r\n");
        }
        else if (reply instanceof Long || reply instanceof Integer) {
            ascii(out, ":" + reply + "\r\n");
        }
        else if (reply instanceof String) {
            byte[] b = ((String)reply).getBytes(StandardCharsets.UTF_8);
            ascii(out, "$" + b.length + "\r\n");
            out.write(b);
            ascii(out, "\r\n");
        }
        else if (reply instanceof Collection) {
            Collection<?> items = (Collection<?>)reply;
            ascii(out, "*" + items.size() + "\r\n");
            for (Object item : items) {
                write(out, item);
            }
        }
        else {
            throw new IllegalArgumentException("cannot encode " + reply
                .getClass());
        }
    }


    private static void ascii(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
