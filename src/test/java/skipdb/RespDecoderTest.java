package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The incremental decoder used by the event loop must accept exactly what the
 * blocking reader accepts, however the bytes are split.
 */
public class RespDecoderTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }


    /** Feeds {@code data} in chunks of the given sizes, collecting commands. */
    private static List<List<String>> decode(byte[] data, Random chunks,
        int maxChunk) throws IOException {
        Resp.Decoder d = new Resp.Decoder();
        List<List<String>> out = new ArrayList<>();
        int off = 0;
        while (off < data.length) {
            int n = Math.min(data.length - off, 1 + chunks.nextInt(maxChunk));
            d.feed(data, off, n);
            off += n;
            List<String> cmd;
            while ((cmd = d.next()) != null) {
                out.add(cmd);
            }
        }
        assertEquals("all bytes consumed", 0, d.buffered());
        return out;
    }


    private static List<List<String>> readAll(byte[] data) throws IOException {
        InputStream in = new ByteArrayInputStream(data);
        List<List<String>> out = new ArrayList<>();
        List<String> cmd;
        while ((cmd = Resp.readCommand(in)) != null) {
            out.add(cmd);
        }
        return out;
    }


    @Test
    public void waitsForCompleteCommands() throws IOException {
        Resp.Decoder d = new Resp.Decoder();
        byte[] b = bytes("*2\r\n$3\r\nGET\r\n$1\r\nk\r\nPING\r\n");
        for (int i = 0; i < b.length; i++) {
            d.feed(b, i, 1);
            List<String> cmd = d.next();
            if (i == 19) { // last byte of the GET command
                assertEquals(List.of("GET", "k"), cmd);
            }
            else if (i == b.length - 1) {
                assertEquals(List.of("PING"), cmd);
            }
            else {
                assertNull("byte " + i, cmd);
            }
        }
        assertNull(d.next());
        assertEquals(0, d.buffered());
    }


    @Test
    public void blankInlineLineIsAnEmptyCommand() throws IOException {
        Resp.Decoder d = new Resp.Decoder();
        byte[] b = bytes("\r\n  \nECHO  a\n");
        d.feed(b, 0, b.length);
        assertEquals(List.of(), d.next());
        assertEquals(List.of(), d.next());
        assertEquals(List.of("ECHO", "a"), d.next());
        assertNull(d.next());
    }


    @Test
    public void largeBulkStringAcrossManyChunks() throws IOException {
        char[] big = new char[3_000_000];
        Arrays.fill(big, 'x');
        String v = new String(big);
        byte[] data = bytes("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$" + big.length
            + "\r\n" + v + "\r\n");
        List<List<String>> cmds = decode(data, new Random(1), 65536);
        assertEquals(1, cmds.size());
        assertEquals(v, cmds.get(0).get(2));
    }


    @Test
    public void matchesBlockingReaderOnRandomPipelines() throws IOException {
        Random rnd = new Random(42);
        String alphabet = "abcé \t*$:\r\n0123456789";
        for (int trial = 0; trial < 200; trial++) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int cmds = 1 + rnd.nextInt(20);
            for (int c = 0; c < cmds; c++) {
                if (rnd.nextInt(4) == 0) {
                    // inline command, possibly blank
                    StringBuilder sb = new StringBuilder();
                    int words = rnd.nextInt(4);
                    for (int w = 0; w < words; w++) {
                        sb.append(' ').append("w").append(rnd.nextInt(100));
                    }
                    buf.writeBytes(bytes("x" + sb + (rnd.nextBoolean()
                        ? "\r\n"
                        : "\n")));
                    continue;
                }
                int argc = rnd.nextInt(5);
                buf.writeBytes(bytes("*" + argc + "\r\n"));
                for (int a = 0; a < argc; a++) {
                    StringBuilder sb = new StringBuilder();
                    int len = rnd.nextInt(rnd.nextInt(10) == 0 ? 5000 : 12);
                    for (int i = 0; i < len; i++) {
                        sb.append(alphabet.charAt(rnd.nextInt(alphabet
                            .length())));
                    }
                    byte[] arg = bytes(sb.toString());
                    buf.writeBytes(bytes("$" + arg.length + "\r\n"));
                    buf.writeBytes(arg);
                    buf.writeBytes(bytes("\r\n"));
                }
            }
            byte[] data = buf.toByteArray();
            List<List<String>> expected = readAll(data);
            assertEquals(cmds, expected.size());
            assertEquals("trial " + trial, expected, decode(data, rnd, 1
                + rnd.nextInt(64)));
        }
    }


    @Test
    public void sameProtocolErrorsAsBlockingReader() throws IOException {
        String[] bad = { "*x\r\n", "*-1\r\n", "*2000000\r\n",
            "*1\r\nGET\r\n", "*1\r\n$abc\r\n", "*1\r\n$-5\r\n",
            "*1\r\n$1\r\nabX\n" };
        for (String s : bad) {
            String expected;
            try {
                Resp.readCommand(new ByteArrayInputStream(bytes(s)));
                fail("blocking reader accepted " + s);
                return;
            }
            catch (Resp.ProtocolException e) {
                expected = e.getMessage();
            }
            Resp.Decoder d = new Resp.Decoder();
            d.feed(bytes(s), 0, bytes(s).length);
            try {
                d.next();
                fail("decoder accepted " + s);
            }
            catch (Resp.ProtocolException e) {
                assertEquals(s, expected, e.getMessage());
            }
        }
    }


    @Test
    public void rejectsOverlongLineBeforeItEnds() {
        char[] junk = new char[70 * 1024];
        Arrays.fill(junk, 'a');
        Resp.Decoder d = new Resp.Decoder();
        byte[] b = bytes(new String(junk));
        d.feed(b, 0, b.length);
        try {
            d.next();
            fail("no error for a 70 KiB line without LF");
        }
        catch (Resp.ProtocolException e) {
            assertEquals("line too long", e.getMessage());
        }
    }
}
