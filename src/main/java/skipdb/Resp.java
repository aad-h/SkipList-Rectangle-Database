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
 * RESP (REdis Serialization Protocol) reader and writer, versions 2 and 3.
 *
 * <p>Requests are arrays of bulk strings ({@code *2\r\n$3\r\nGET\r\n$1\r\nk\r\n})
 * or inline commands ({@code GET k\r\n}, handy with telnet/nc). Replies are
 * written from plain Java values: String = bulk string, Long/Integer =
 * integer, List = array, null = null, plus {@link Simple}, {@link Error} and
 * the protocol-dependent types {@link Dbl}, {@link Map}, {@link Pairs} and
 * {@link Verbatim}. The same reply value is encoded per connection: in RESP2
 * a double is a bulk string, a map a flat array and null {@code $-1}; in RESP3
 * ({@code HELLO 3}) they use the native {@code ,} {@code %} and {@code _}
 * types, exactly as Redis 7 does.
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

    /**
     * A double reply (ZSCORE, ZINCRBY, WITHSCORES scores): a bulk string in
     * RESP2, the {@code ,} type in RESP3.
     */
    public static final class Dbl {
        public final double value;

        public Dbl(double value) {
            this.value = value;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Dbl && Double.compare(((Dbl)o).value,
                value) == 0;
        }


        @Override
        public int hashCode() {
            return Double.hashCode(value);
        }


        @Override
        public String toString() {
            return CommandHandler.fmt(value);
        }
    }

    /**
     * A map reply given as alternating keys and values (HELLO): a flat array
     * in RESP2, the {@code %} type in RESP3.
     */
    public static final class Map {
        public final List<Object> keysAndValues;

        public Map(List<Object> keysAndValues) {
            if (keysAndValues.size() % 2 != 0) {
                throw new IllegalArgumentException("odd number of map items");
            }
            this.keysAndValues = keysAndValues;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Map && ((Map)o).keysAndValues.equals(
                keysAndValues);
        }


        @Override
        public int hashCode() {
            return keysAndValues.hashCode();
        }


        @Override
        public String toString() {
            return "%" + keysAndValues;
        }
    }

    /**
     * Member/score pairs given as a flat list ({@code ZRANGE ... WITHSCORES}):
     * a flat array in RESP2, an array of two-element arrays in RESP3.
     */
    public static final class Pairs {
        public final List<Object> flat;

        public Pairs(List<Object> flat) {
            if (flat.size() % 2 != 0) {
                throw new IllegalArgumentException("odd number of pair items");
            }
            this.flat = flat;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Pairs && ((Pairs)o).flat.equals(flat);
        }


        @Override
        public int hashCode() {
            return flat.hashCode();
        }


        @Override
        public String toString() {
            return "pairs" + flat;
        }
    }

    /**
     * A verbatim string with a three-letter format such as {@code txt}
     * (INFO): a bulk string in RESP2, the {@code =} type in RESP3.
     */
    public static final class Verbatim {
        public final String format;
        public final String text;

        public Verbatim(String format, String text) {
            if (format.length() != 3) {
                throw new IllegalArgumentException("format must be 3 chars");
            }
            this.format = format;
            this.text = text;
        }


        @Override
        public boolean equals(Object o) {
            return o instanceof Verbatim && ((Verbatim)o).format.equals(format)
                && ((Verbatim)o).text.equals(text);
        }


        @Override
        public int hashCode() {
            return format.hashCode() * 31 + text.hashCode();
        }


        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Lowers a reply to the plain values a RESP2 client would decode:
     * doubles become their bulk-string form, maps and pairs flat lists,
     * verbatim strings plain strings. Handy for comparing replies in tests.
     *
     * @return an equivalent reply built only from String, Long, List, null,
     *         {@link Simple} and {@link Error}
     */
    public static Object toResp2(Object reply) {
        if (reply instanceof Dbl) {
            return reply.toString();
        }
        if (reply instanceof Verbatim) {
            return ((Verbatim)reply).text;
        }
        if (reply instanceof Map) {
            return toResp2(((Map)reply).keysAndValues);
        }
        if (reply instanceof Pairs) {
            return toResp2(((Pairs)reply).flat);
        }
        if (reply instanceof Collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Collection<?>)reply) {
                out.add(toResp2(item));
            }
            return out;
        }
        return reply;
    }

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


    /**
     * Incremental request decoder for non-blocking I/O: bytes arrive in
     * arbitrary chunks via {@link #feed}, and {@link #next} returns each
     * command once all of its bytes are buffered. It accepts exactly what
     * {@link Resp#readCommand} accepts, with the same error messages, so the
     * blocking and event-loop servers behave identically on the wire.
     */
    public static final class Decoder {
        private static final int MAX_LINE = 64 * 1024;
        private byte[] buf = new byte[16 * 1024];
        private int start; // first unconsumed byte
        private int end; // one past the last buffered byte
        private int pos; // scan cursor during one parse attempt

        /** Appends all remaining bytes of {@code src} to the buffer. */
        public void feed(java.nio.ByteBuffer src) {
            int n = src.remaining();
            if (end + n > buf.length) {
                int live = end - start;
                byte[] dst = live + n > buf.length
                    ? new byte[Math.max(buf.length * 2, live + n)]
                    : buf;
                System.arraycopy(buf, start, dst, 0, live);
                buf = dst;
                start = 0;
                end = live;
            }
            src.get(buf, end, n);
            end += n;
        }


        /** Appends {@code len} bytes of {@code b} starting at {@code off}. */
        public void feed(byte[] b, int off, int len) {
            feed(java.nio.ByteBuffer.wrap(b, off, len));
        }


        /** @return the number of buffered bytes not yet decoded */
        public int buffered() {
            return end - start;
        }


        /**
         * Decodes the next complete command.
         *
         * @return the command and its arguments, an empty list for a blank
         *         inline line, or null if more bytes are needed
         * @throws ProtocolException
         *             on malformed input; the connection should be closed
         */
        public List<String> next() throws ProtocolException {
            if (start == end) {
                return null;
            }
            pos = start;
            List<String> cmd = parse();
            if (cmd != null) {
                start = pos;
                if (start == end) {
                    start = 0;
                    end = 0;
                }
            }
            return cmd;
        }


        private List<String> parse() throws ProtocolException {
            if (buf[pos] != '*') {
                String line = line();
                if (line == null) {
                    return null;
                }
                String trimmed = line.trim();
                return trimmed.isEmpty()
                    ? new ArrayList<>()
                    : new ArrayList<>(Arrays.asList(trimmed.split("\\s+")));
            }
            pos++;
            String header = line();
            if (header == null) {
                return null;
            }
            int n = parseInt(header);
            if (n < 0 || n > MAX_ARRAY) {
                throw new ProtocolException("invalid multibulk length");
            }
            List<String> args = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (pos == end) {
                    return null;
                }
                if (buf[pos] != '$') {
                    throw new ProtocolException("expected '$'");
                }
                pos++;
                String lenLine = line();
                if (lenLine == null) {
                    return null;
                }
                int len = parseInt(lenLine);
                if (len < 0 || len > MAX_BULK) {
                    throw new ProtocolException("invalid bulk length");
                }
                if (end - pos < (long)len + 2) {
                    return null;
                }
                if (buf[pos + len] != '\r' || buf[pos + len + 1] != '\n') {
                    throw new ProtocolException(
                        "expected CRLF after bulk string");
                }
                args.add(new String(buf, pos, len, StandardCharsets.UTF_8));
                pos += len + 2;
            }
            return args;
        }


        /** Reads up to LF (dropping a trailing CR), or null if incomplete. */
        private String line() throws ProtocolException {
            for (int i = pos; i < end; i++) {
                if (buf[i] == '\n') {
                    int len = i - pos;
                    if (len > MAX_LINE) {
                        throw new ProtocolException("line too long");
                    }
                    if (len > 0 && buf[i - 1] == '\r') {
                        len--;
                    }
                    String s = new String(buf, pos, len, StandardCharsets.UTF_8);
                    pos = i + 1;
                    return s;
                }
            }
            if (end - pos > MAX_LINE) {
                throw new ProtocolException("line too long");
            }
            return null;
        }
    }


    /** Encodes a reply value to RESP2 bytes. */
    public static byte[] encode(Object reply) {
        return encode(reply, 2);
    }


    /**
     * Encodes a reply value to bytes.
     *
     * @param proto
     *            the protocol version, 2 or 3
     */
    public static byte[] encode(Object reply, int proto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out, reply, proto);
        }
        catch (IOException e) {
            throw new IllegalStateException(e); // cannot happen for a byte buffer
        }
        return out.toByteArray();
    }


    /** Writes a reply value in RESP2. */
    public static void write(OutputStream out, Object reply) throws IOException {
        write(out, reply, 2);
    }


    /**
     * Writes a reply value.
     *
     * @param proto
     *            the connection's protocol version, 2 or 3
     */
    public static void write(OutputStream out, Object reply, int proto)
        throws IOException {
        boolean resp3 = proto >= 3;
        if (reply == null) {
            ascii(out, resp3 ? "_\r\n" : "$-1\r\n");
        }
        else if (reply instanceof Dbl) {
            if (resp3) {
                double d = ((Dbl)reply).value;
                ascii(out, "," + (Double.isNaN(d) ? "nan" : reply.toString())
                    + "\r\n");
            }
            else {
                write(out, reply.toString(), proto);
            }
        }
        else if (reply instanceof Map) {
            List<Object> kv = ((Map)reply).keysAndValues;
            if (resp3) {
                ascii(out, "%" + kv.size() / 2 + "\r\n");
                for (Object item : kv) {
                    write(out, item, proto);
                }
            }
            else {
                write(out, kv, proto);
            }
        }
        else if (reply instanceof Pairs) {
            List<Object> flat = ((Pairs)reply).flat;
            if (resp3) {
                ascii(out, "*" + flat.size() / 2 + "\r\n");
                for (int i = 0; i < flat.size(); i += 2) {
                    ascii(out, "*2\r\n");
                    write(out, flat.get(i), proto);
                    write(out, flat.get(i + 1), proto);
                }
            }
            else {
                write(out, flat, proto);
            }
        }
        else if (reply instanceof Verbatim) {
            Verbatim v = (Verbatim)reply;
            if (resp3) {
                byte[] b = (v.format + ":" + v.text).getBytes(
                    StandardCharsets.UTF_8);
                ascii(out, "=" + b.length + "\r\n");
                out.write(b);
                ascii(out, "\r\n");
            }
            else {
                write(out, v.text, proto);
            }
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
                write(out, item, proto);
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
