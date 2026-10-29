package io.github.entorkrasniqi.contacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MainTest {

    private record Result(int code, String out, String err) { }

    @BeforeAll
    static void setUp() {
        TestData.initOrekit();
    }

    private static Result run(String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] demo(Path outDir, String... extra) {
        final String[] base = {"--tle", TestData.TLE_FILE.toString(), "--stations", TestData.STATIONS_FILE.toString(),
                "--start", "2026-10-24T00:00:00Z", "--out", outDir.toString()};
        final String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    @Test
    void endToEndWritesAllOutputFiles() throws IOException {
        final Path dir = Files.createTempDirectory("planner-test");
        final Result r = run(demo(dir, "--hours", "6"));
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("[greedy]"));
        assertTrue(r.out().contains("[exact] (optimal)"));
        assertTrue(r.out().contains("exact vs greedy"));
        for (String name : List.of("passes.csv", "schedule_greedy.csv", "schedule_exact.csv",
                "timeline_greedy.svg", "timeline_exact.svg")) {
            assertTrue(Files.size(dir.resolve(name)) > 0, name + " should exist and not be empty");
        }
        final List<String> passes = Files.readAllLines(dir.resolve("passes.csv"));
        assertTrue(passes.get(0).endsWith("scheduled_greedy,scheduled_exact"));
        assertTrue(passes.size() > 10);
        // every scheduled pass is also a possible pass
        assertTrue(Files.readAllLines(dir.resolve("schedule_exact.csv")).size() <= passes.size());
    }

    /** Regression snapshot for the documented 24-hour demo. Numbers confirmed by tools/crosscheck.py (pass counts). */
    @Test
    void theTwentyFourHourDemoGivesTheNumbersQuotedInTheReadme() throws IOException {
        final Path dir = Files.createTempDirectory("planner-test");
        final Result r = run(demo(dir));
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("-> 162 possible passes"), r.out());
        assertTrue(r.out().contains("85 of 162 passes"), r.out());
        assertTrue(r.out().contains("89 of 162 passes"), r.out());
        assertEquals(163, Files.readAllLines(dir.resolve("passes.csv")).size());   // header + 162
    }

    @Test
    void singleSchedulerOptionWritesOnlyThatSchedule() throws IOException {
        final Path dir = Files.createTempDirectory("planner-test");
        assertEquals(0, run(demo(dir, "--hours", "3", "--scheduler", "greedy")).code());
        assertTrue(Files.exists(dir.resolve("schedule_greedy.csv")));
        assertTrue(!Files.exists(dir.resolve("schedule_exact.csv")));
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        final Result r = run("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("Usage: Main"));
    }

    @Test
    void usageErrorsReturnCode2() {
        assertEquals(2, run().code());                                       // required options missing
        assertEquals(2, run("--tle", "a", "--stations", "b", "--bogus", "1").code());
        assertEquals(2, run("--tle").code());                                // option without value
    }

    @Test
    void problemsWithTheInputReturnCode1AndAMessage() throws IOException {
        final Path dir = Files.createTempDirectory("planner-test");
        Result r = run("--tle", "does-not-exist.tle", "--stations", TestData.STATIONS_FILE.toString(), "--out", dir.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().startsWith("error:"));
        r = run(demo(dir, "--hours", "-2"));
        assertEquals(1, r.code());
        r = run(demo(dir, "--scheduler", "magic"));
        assertEquals(1, r.code());
        r = run("--tle", TestData.TLE_FILE.toString(), "--stations", TestData.STATIONS_FILE.toString(),
                "--start", "yesterday", "--out", dir.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("--start"));
    }
}
