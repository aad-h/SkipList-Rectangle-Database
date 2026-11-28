package skipdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import skipdb.demo.LeaderboardDemo;

/**
 * Command-line parsing of {@link SkipDBServer.Options}, and the Dockerfile
 * checked against it: the image's default command must parse, and the ports
 * it listens on must be the ones the image exposes and health-checks.
 */
public class ServerOptionsTest {

    private static SkipDBServer.Options parse(String... args) {
        return SkipDBServer.Options.parse(args);
    }


    private static void assertRejected(String expectedMessage,
        String... args) {
        try {
            parse(args);
            fail("expected an error for " + String.join(" ", args));
        }
        catch (IllegalArgumentException e) {
            assertEquals(expectedMessage, e.getMessage());
        }
    }


    @Test
    public void defaults() {
        SkipDBServer.Options o = parse();
        assertEquals(6380, o.port);
        assertEquals(SkipDBServer.Options.DEFAULT_PORT, o.port);
        assertNull(o.aof);
        assertEquals(Aof.Fsync.EVERYSEC, o.fsync);
        assertEquals(SkipDBServer.Io.THREADS, o.io);
        assertFalse(o.demo);
        assertEquals(LeaderboardDemo.DEFAULT_HTTP_PORT, o.demoPort);
    }


    @Test
    public void everyFlagInAnyOrder() {
        SkipDBServer.Options o = parse("--io", "NIO", "--aof", "/data/a.aof",
            "7000", "--appendfsync", "always", "--demo-port", "9090");
        assertEquals(7000, o.port);
        assertEquals(Paths.get("/data/a.aof"), o.aof);
        assertEquals(Aof.Fsync.ALWAYS, o.fsync);
        assertEquals(SkipDBServer.Io.NIO, o.io);
        assertTrue("--demo-port implies --demo", o.demo);
        assertEquals(9090, o.demoPort);

        o = parse("--demo", "0");
        assertTrue(o.demo);
        assertEquals(0, o.port);
        assertEquals(LeaderboardDemo.DEFAULT_HTTP_PORT, o.demoPort);
    }


    @Test
    public void rejectsBadArguments() {
        assertRejected("unknown option: --aoff", "--aoff", "x.aof");
        assertRejected("unknown option: -p", "-p", "6379");
        assertRejected("--aof needs a value", "6380", "--aof");
        assertRejected("--io needs a value", "--io");
        assertRejected("--io must be threads or nio, got: epoll", "--io",
            "epoll");
        assertRejected("appendfsync must be always, everysec or no: sometimes",
            "--appendfsync", "sometimes");
        assertRejected("port must be a number from 0 to 65535, got: abc",
            "abc");
        assertRejected("port must be a number from 0 to 65535, got: 70000",
            "70000");
        assertRejected("--demo-port must be a number from 0 to 65535, got: x",
            "--demo-port", "x");
        assertRejected("unexpected argument: 6381", "6380", "6381");
    }


    // ---- Dockerfile ------------------------------------------------------

    private static String dockerfile() throws IOException {
        Path p = Paths.get("Dockerfile");
        assertTrue("run the tests from the repository root", Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }


    /** The JSON-array strings of the instruction {@code name} (exec form). */
    private static List<String> execForm(String dockerfile, String name) {
        Matcher m = Pattern.compile("(?m)^" + name + "\\s+\\[(.*)\\]\\s*$")
            .matcher(dockerfile);
        assertTrue("Dockerfile needs an exec-form " + name, m.find());
        List<String> out = new ArrayList<>();
        Matcher s = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (s.find()) {
            out.add(s.group(1));
        }
        return out;
    }


    @Test
    public void dockerDefaultCommandParses() throws IOException {
        String df = dockerfile();
        SkipDBServer.Options o = parse(execForm(df, "CMD").toArray(
            new String[0]));
        assertEquals(SkipDBServer.Io.NIO, o.io);
        assertNotNull("the image persists by default", o.aof);
        assertTrue("the AOF must live in the volume", o.aof.startsWith(
            "/data"));
        assertTrue(df.contains("VOLUME /data"));
        assertTrue(df.contains("WORKDIR /data")); // BGREWRITEAOF temp files

        Matcher expose = Pattern.compile("(?m)^EXPOSE\\s+(.*)$").matcher(df);
        assertTrue(expose.find());
        List<String> exposed = List.of(expose.group(1).trim().split("\\s+"));
        assertTrue(exposed.contains(String.valueOf(o.port)));
        assertTrue(exposed.contains(String.valueOf(
            LeaderboardDemo.DEFAULT_HTTP_PORT)));
        assertTrue("healthcheck must ping the served port", df.contains(
            "/dev/tcp/127.0.0.1/" + o.port + " "));
    }


    @Test
    public void dockerEntrypointRunsTheServer() throws Exception {
        String df = dockerfile();
        List<String> entry = execForm(df, "ENTRYPOINT");
        assertEquals("java", entry.get(0));
        String jar = entry.get(entry.indexOf("-jar") + 1);
        Matcher m = Pattern.compile("--main-class (\\S+)").matcher(df);
        assertTrue(m.find());
        assertEquals(SkipDBServer.class, Class.forName(m.group(1)));
        assertTrue("the built jar is copied to the entrypoint's path", df
            .contains("COPY --from=build /skipdb.jar " + jar));
    }
}
