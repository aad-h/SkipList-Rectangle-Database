package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RespTest {

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }


    private static String enc(Object o) {
        return new String(Resp.encode(o), StandardCharsets.UTF_8);
    }


    @Test
    public void readsMultibulkAndInline() throws IOException {
        InputStream s = in("*3\r\n$4\r\nZADD\r\n$1\r\nk\r\n$5\r\nhé y\r\nPING  hi \r\n");
        assertEquals(List.of("ZADD", "k", "hé y"), Resp.readCommand(s));
        assertEquals(List.of("PING", "hi"), Resp.readCommand(s));
        assertNull(Resp.readCommand(s));
    }


    @Test
    public void bulkLengthIsInBytes() throws IOException {
        // "é" is 2 bytes in UTF-8
        assertEquals(List.of("é"), Resp.readCommand(in("*1\r\n$2\r\né\r\n")));
        assertEquals("$2\r\né\r\n", enc("é"));
    }


    @Test(expected = Resp.ProtocolException.class)
    public void rejectsBadLength() throws IOException {
        Resp.readCommand(in("*x\r\n"));
    }


    @Test(expected = Resp.ProtocolException.class)
    public void rejectsMissingCrlf() throws IOException {
        Resp.readCommand(in("*1\r\n$1\r\nabX\n"));
    }


    @Test
    public void encodesAllTypes() {
        assertEquals("+OK\r\n", enc(Resp.OK));
        assertEquals("-ERR bad thing\r\n", enc(new Resp.Error("ERR bad\nthing")));
        assertEquals(":42\r\n", enc(42L));
        assertEquals("$-1\r\n", enc(null));
        assertEquals("*2\r\n$1\r\na\r\n:1\r\n", enc(Arrays.asList("a", 1L)));
        assertTrue(enc(List.of()).equals("*0\r\n"));
    }
}
