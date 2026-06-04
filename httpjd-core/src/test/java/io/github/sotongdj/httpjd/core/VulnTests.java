package io.github.sotongdj.httpjd.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Vulnerability test suite for httpjd — a port of httprd's {@code vuln_tests.rs}.
 *
 * <p>The server is started in-process on an ephemeral port and driven over raw
 * TCP (127.0.0.1), so URL normalisation in any HTTP client is bypassed.
 *
 * <p>Test categories mirror vulnerability.md:
 * <ul>
 *   <li>PT  – Path Traversal / Directory Traversal   (§3)</li>
 *   <li>ID  – Information Disclosure                  (§11)</li>
 *   <li>AC  – Access Control Misconfiguration         (§10)</li>
 *   <li>RR  – Range Requests / DoS surface            (§8)</li>
 *   <li>RS  – HTTP Response Splitting                 (§5)</li>
 *   <li>GEN – General correctness cross-checks</li>
 * </ul>
 */
class VulnTests {

    private final List<Path> tempDirs = new ArrayList<>();
    private final List<FileServer> servers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (FileServer s : servers) {
            try { s.stop(0); } catch (Exception ignored) {}
        }
        for (Path d : tempDirs) {
            try (Stream<Path> walk = Files.walk(d)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    // ── fixture ────────────────────────────────────────────────────────────────

    /** Mirrors the Rust {@code fixture()}: a root with a few files plus an escape symlink. */
    private Path fixture() throws IOException {
        Path dir = Files.createTempDirectory("httpjd-root");
        tempDirs.add(dir);
        Files.write(dir.resolve("hello.txt"), "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("empty.txt"), new byte[0]);
        Files.createDirectory(dir.resolve("sub"));
        Files.write(dir.resolve("sub").resolve("nested.txt"), "nested".getBytes(StandardCharsets.US_ASCII));

        // A directory OUTSIDE the root, linked into it — a symlink-escape attempt.
        Path outside = Files.createTempDirectory("httpjd-outside");
        tempDirs.add(outside);
        Files.write(outside.resolve("secret.txt"), "OUTSIDE".getBytes(StandardCharsets.US_ASCII));
        try {
            Files.createSymbolicLink(dir.resolve("escape_link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Platform without symlink support; pt_05 will be skipped.
        }
        return dir;
    }

    private Server start(Path root, boolean showIndex) throws IOException {
        FileServer fs = new FileServer(root, showIndex, "127.0.0.1", 0);
        fs.start();
        servers.add(fs);
        return new Server(fs.port());
    }

    /** Thin handle exposing the bound port and a raw-request helper. */
    private record Server(int port) {
        String send(String request) throws IOException {
            return raw(port, request);
        }
    }

    // ── raw HTTP over TCP ────────────────────────────────────────────────────────

    private static String raw(int port, String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream os = s.getOutputStream();
            os.write(request.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] tmp = new byte[4096];
            try {
                int n;
                while ((n = in.read(tmp)) >= 0) {
                    buf.write(tmp, 0, n);
                }
            } catch (SocketTimeoutException ignored) {
                // return whatever arrived before the timeout
            }
            return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    // ── response parsing helpers ─────────────────────────────────────────────────

    private static int statusOf(String resp) {
        String[] parts = resp.split("\\s+");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String headerVal(String resp, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : resp.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static String bodyOf(String resp) {
        int idx = resp.indexOf("\r\n\r\n");
        return idx < 0 ? "" : resp.substring(idx + 4);
    }

    // ── request builders ─────────────────────────────────────────────────────────

    private static String reqGet(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    }

    private static String reqRange(String path, String range) {
        return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\nRange: " + range
                + "\r\nConnection: close\r\n\r\n";
    }

    private static String reqHead(String path) {
        return "HEAD " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    }

    private static String reqMethod(String method, String path) {
        return method + " " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
    }

    // ════════════════════════════════════════════════════════════════════════════
    // PT – Path Traversal
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void pt01_dotdotPlain() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/../../../etc/passwd")));
        assertTrue(st == 404 || st == 403, "TC-PT-01 FAIL: plain ../ traversal returned " + st);
    }

    @Test
    void pt02_dotdotUrlEncoded() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/%2e%2e/%2e%2e/etc/passwd")));
        assertTrue(st == 404 || st == 403, "TC-PT-02 FAIL: %2e%2e traversal returned " + st);
    }

    @Test
    void pt03_dotdotDoubleEncoded() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/%252e%252e/%252e%252e/etc/passwd")));
        assertNotEquals(200, st, "TC-PT-03 FAIL: double-encoded traversal returned 200");
    }

    @Test
    void pt04_nullByteInPath() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/hello.txt%00.php")));
        assertNotEquals(200, st, "TC-PT-04 FAIL: null-byte path returned 200");
    }

    @Test
    void pt05_symlinkEscape() throws IOException {
        Path root = fixture();
        assumeTrue(Files.exists(root.resolve("escape_link")), "symlinks unsupported on this platform");
        int st = statusOf(start(root, false).send(reqGet("/escape_link/secret.txt")));
        assertTrue(st == 403 || st == 404, "TC-PT-05 FAIL: symlink escape returned " + st);
    }

    @Test
    void pt06_encodedSlashTraversal() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/..%2fetc%2fpasswd")));
        assertNotEquals(200, st, "TC-PT-06 FAIL: encoded-slash traversal returned 200");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // ID – Information Disclosure
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void id01_noServerVersionHeader() throws IOException {
        String resp = start(fixture(), false).send(reqGet("/hello.txt"));
        String server = headerVal(resp, "server");
        server = server == null ? "" : server.toLowerCase(Locale.ROOT);
        assertTrue(!server.contains("httpjd") && !server.contains("httprd") && !server.contains("0.1"),
                "TC-ID-01 FAIL: Server header exposes version: " + server);
    }

    @Test
    void id02_xContentTypeOptionsOn200() throws IOException {
        String resp = start(fixture(), false).send(reqGet("/hello.txt"));
        assertEquals(200, statusOf(resp));
        assertEquals("nosniff", headerVal(resp, "x-content-type-options"),
                "TC-ID-02 FAIL: X-Content-Type-Options: nosniff missing on 200");
    }

    @Test
    void id03_xContentTypeOptionsOn206() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=0-4"));
        assertEquals(206, statusOf(resp));
        assertEquals("nosniff", headerVal(resp, "x-content-type-options"),
                "TC-ID-03 FAIL: X-Content-Type-Options: nosniff missing on 206");
    }

    @Test
    void id04_errorBodyNotVerbose() throws IOException {
        String body = bodyOf(start(fixture(), false).send(reqGet("/nonexistent_xyz_file"))).toLowerCase(Locale.ROOT);
        assertTrue(!body.contains("/home/") && !body.contains("/tmp/"),
                "TC-ID-04 FAIL: 404 body leaks filesystem path");
        assertTrue(!body.contains("stack") && !body.contains("backtrace"),
                "TC-ID-04 FAIL: 404 body leaks stack trace");
    }

    @Test
    void id05_listingRequiresIndexFlag() throws IOException {
        int st = statusOf(start(fixture(), false).send(reqGet("/")));
        assertEquals(403, st, "TC-ID-05 FAIL: listing returned " + st + " without -i flag");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // AC – Access Control
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void ac01_postRejected405() throws IOException {
        assertEquals(405, statusOf(start(fixture(), false).send(reqMethod("POST", "/hello.txt"))));
    }

    @Test
    void ac02_putRejected405() throws IOException {
        assertEquals(405, statusOf(start(fixture(), false).send(reqMethod("PUT", "/hello.txt"))));
    }

    @Test
    void ac03_deleteRejected405() throws IOException {
        assertEquals(405, statusOf(start(fixture(), false).send(reqMethod("DELETE", "/hello.txt"))));
    }

    @Test
    void ac04_patchRejected405() throws IOException {
        assertEquals(405, statusOf(start(fixture(), false).send(reqMethod("PATCH", "/hello.txt"))));
    }

    @Test
    void ac05_cspOnDirectoryListing() throws IOException {
        String resp = start(fixture(), true).send(reqGet("/"));
        assertEquals(200, statusOf(resp));
        assertTrue(headerVal(resp, "content-security-policy") != null,
                "TC-AC-05 FAIL: directory listing has no Content-Security-Policy header");
    }

    @Test
    void ac06_listingContentCorrect() throws IOException {
        String resp = start(fixture(), true).send(reqGet("/"));
        assertEquals(200, statusOf(resp));
        String body = bodyOf(resp);
        assertTrue(body.contains("hello.txt"), "TC-AC-06 FAIL: hello.txt absent from listing");
        assertTrue(body.contains("href="), "TC-AC-06 FAIL: no links in listing");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RR – Range Requests / DoS surface
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void rr01_validRange206() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=0-4"));
        assertEquals(206, statusOf(resp));
        assertEquals("ABCDE", bodyOf(resp));
    }

    @Test
    void rr02_openEndedRange() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=20-"));
        assertEquals(206, statusOf(resp));
        assertEquals("UVWXYZ", bodyOf(resp));
    }

    @Test
    void rr03_suffixRange() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=-5"));
        assertEquals(206, statusOf(resp));
        assertEquals("VWXYZ", bodyOf(resp));
    }

    @Test
    void rr04_endClampedToEof() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=20-9999"));
        assertEquals(206, statusOf(resp));
        assertEquals("UVWXYZ", bodyOf(resp));
    }

    @Test
    void rr05_startBeyondEof416() throws IOException {
        assertEquals(416, statusOf(start(fixture(), false).send(reqRange("/hello.txt", "bytes=999-1000"))));
    }

    @Test
    void rr06_startGtEnd416() throws IOException {
        assertEquals(416, statusOf(start(fixture(), false).send(reqRange("/hello.txt", "bytes=10-5"))));
    }

    @Test
    void rr07_malformedRange416() throws IOException {
        assertEquals(416, statusOf(start(fixture(), false).send(reqRange("/hello.txt", "bytes=abc-def"))));
    }

    @Test
    void rr08_rangeOnEmptyFile416() throws IOException {
        assertEquals(416, statusOf(start(fixture(), false).send(reqRange("/empty.txt", "bytes=0-0"))));
    }

    @Test
    void rr09_u64MaxEndClamped() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=0-18446744073709551615"));
        assertEquals(206, statusOf(resp));
        assertEquals(26, bodyOf(resp).length(), "TC-RR-09 FAIL: should return whole file");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // RS – HTTP Response Splitting
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void rs01_crlfNotInjectedIntoHeaders() throws IOException {
        String resp = start(fixture(), false).send(reqGet("/sub%0d%0aX-Evil:%20injected"));
        assertTrue(!resp.toLowerCase(Locale.ROOT).contains("x-evil"),
                "TC-RS-01 FAIL: injected header name appeared in response");
    }

    // ════════════════════════════════════════════════════════════════════════════
    // GEN – General correctness
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void gen01_headNoBody() throws IOException {
        String resp = start(fixture(), false).send(reqHead("/hello.txt"));
        assertEquals(200, statusOf(resp));
        assertTrue(bodyOf(resp).isEmpty(), "TC-GEN-01 FAIL: HEAD response has non-empty body");
    }

    @Test
    void gen02_get200WithAcceptRanges() throws IOException {
        String resp = start(fixture(), false).send(reqGet("/hello.txt"));
        assertEquals(200, statusOf(resp));
        assertEquals("bytes", headerVal(resp, "accept-ranges"),
                "TC-GEN-02 FAIL: Accept-Ranges: bytes not advertised");
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ", bodyOf(resp));
    }

    @Test
    void gen03_dirRedirectAddsSlash() throws IOException {
        String resp = start(fixture(), false).send(reqGet("/sub"));
        assertEquals(301, statusOf(resp), "TC-GEN-03 FAIL: /sub returned " + statusOf(resp));
        String loc = headerVal(resp, "location");
        assertTrue(loc != null && loc.endsWith("/sub/"), "TC-GEN-03 FAIL: Location is " + loc);
    }

    @Test
    void gen04_contentRangeHeaderFormat() throws IOException {
        String resp = start(fixture(), false).send(reqRange("/hello.txt", "bytes=5-9"));
        assertEquals(206, statusOf(resp));
        assertEquals("bytes 5-9/26", headerVal(resp, "content-range"),
                "TC-GEN-04 FAIL: unexpected Content-Range");
    }
}
