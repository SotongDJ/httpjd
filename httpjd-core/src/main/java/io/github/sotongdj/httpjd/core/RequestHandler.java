package io.github.sotongdj.httpjd.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The request router — a port of {@code handle_request} and its helpers in
 * {@code httprd}'s {@code main.rs}. Serves GET/HEAD only, sandboxes the
 * filesystem path against the configured root, supports HTTP Range requests,
 * and optionally renders directory listings.
 */
public final class RequestHandler implements HttpHandler {

    private static final String[] INDEX_FILES = {"index.html", "index.htm"};
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final Path root;
    private final boolean showIndex;

    public RequestHandler(Path root, boolean showIndex) {
        this.root = root;
        this.showIndex = showIndex;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            route(ex);
        } catch (IOException e) {
            // Best-effort generic error; never leak internals to the client.
            try {
                plain(ex, 500, "500 Internal Server Error");
            } catch (IOException ignored) {
                // client gone
            }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String rawPath = ex.getRequestURI().getRawPath();

        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            plain(ex, 405, "405 Method Not Allowed");
            return;
        }

        // Percent-decode, then build a sandboxed filesystem path.
        String decoded = Format.percentDecode(rawPath);
        Path fsPath = root;
        for (String seg : decoded.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                // Drop empties, current-dir, and traversal attempts silently.
                continue;
            }
            if (seg.indexOf('\0') >= 0) {
                plain(ex, 400, "400 Bad Request");
                return;
            }
            fsPath = fsPath.resolve(seg);
        }

        // Resolve symlinks; reject anything that escaped the root.
        Path canonical;
        try {
            canonical = fsPath.toRealPath();
        } catch (IOException e) {
            plain(ex, 404, "404 Not Found");
            return;
        }
        if (!canonical.startsWith(root)) {
            plain(ex, 403, "403 Forbidden");
            return;
        }

        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(canonical, BasicFileAttributes.class);
        } catch (IOException e) {
            plain(ex, 404, "404 Not Found");
            return;
        }

        if (attrs.isDirectory()) {
            if (!rawPath.endsWith("/")) {
                redirect(ex, rawPath + "/");
                return;
            }
            serveDir(ex, canonical, rawPath, method);
        } else if (attrs.isRegularFile()) {
            serveFile(ex, canonical, attrs.size(), method, true);
        } else {
            plain(ex, 404, "404 Not Found");
        }
    }

    // ── directory handling ─────────────────────────────────────────────────────

    private void serveDir(HttpExchange ex, Path dir, String uriPath, String method) throws IOException {
        for (String name : INDEX_FILES) {
            Path p = dir.resolve(name);
            if (Files.isRegularFile(p)) {
                serveFile(ex, p, Files.size(p), method, false);
                return;
            }
        }
        if (!showIndex) {
            plain(ex, 403, "403 Forbidden: directory listing is disabled");
            return;
        }
        generateListing(ex, dir, uriPath, method);
    }

    private record Entry(String name, boolean isDir, long size, String mode, String nlink, Instant modified) {}

    private void generateListing(HttpExchange ex, Path dir, String uriPath, String method) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                entries.add(describe(p));
            }
        } catch (IOException e) {
            plain(ex, 500, "500 Internal Server Error");
            return;
        }

        // Directories first, then case-insensitive alphabetical.
        entries.sort(Comparator
                .comparing((Entry e) -> !e.isDir)
                .thenComparing(e -> e.name.toLowerCase()));

        String escPath = Format.htmlEscape(uriPath);
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n")
           .append("<html>\n<head>\n")
           .append("<meta charset=\"utf-8\">\n")
           .append("<title>Index of ").append(escPath).append("</title>\n")
           .append("<style>\n")
           .append("body { font-family: monospace; margin: 1em 2em; background:#fff; color:#000 }\n")
           .append("h2   { margin-bottom: .4em }\n")
           .append("pre  { margin: 0; line-height: 1.6 }\n")
           .append("a    { color: #0066cc; text-decoration: none }\n")
           .append("a:hover { text-decoration: underline }\n")
           .append("hr   { border: none; border-top: 1px solid #ccc; margin: .5em 0 }\n")
           .append("</style>\n</head>\n<body>\n")
           .append("<h2>Index of ").append(escPath).append("</h2>\n<hr>\n<pre>\n");

        // Header row, mimicking `ls -hal`.
        out.append(String.format("%-10s  %4s  %9s  %-17s  %s%n",
                "Mode", "Lnk", "Size", "Modified", "Name"));

        // Parent-directory link.
        if (!uriPath.equals("/")) {
            out.append(String.format("%-10s  %4s  %9s  %-17s  <a href=\"../\">../</a>%n",
                    "drwxr-xr-x", "-", "-", "-"));
        }

        Instant now = Instant.now();
        for (Entry e : entries) {
            String sizeStr = e.isDir ? "-" : Format.humanSizeBinary(e.size);
            String timeStr = Format.lsTime(e.modified, ZONE, now);
            String rawName = e.isDir ? e.name + "/" : e.name;
            String display = Format.htmlEscape(rawName);
            String enc = Format.encodeSegment(e.name);
            String href = e.isDir ? enc + "/" : enc;

            out.append(String.format("%-10s  %4s  %9s  %-17s  <a href=\"%s\">%s</a>%n",
                    e.mode, e.nlink, sizeStr, timeStr, href, display));
        }

        out.append("</pre>\n<hr>\n</body>\n</html>\n");

        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'");
        if ("HEAD".equals(method)) {
            ex.sendResponseHeaders(200, -1);
        } else {
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        }
    }

    private Entry describe(Path p) {
        String name = p.getFileName().toString();
        boolean isDir;
        long size = 0;
        Instant modified = Instant.EPOCH;
        String mode;
        String nlink = "-";
        try {
            BasicFileAttributes basic = Files.readAttributes(p, BasicFileAttributes.class);
            isDir = basic.isDirectory();
            size = basic.size();
            modified = basic.lastModifiedTime().toInstant();
            try {
                PosixFileAttributes posix = Files.readAttributes(p, PosixFileAttributes.class);
                mode = Format.modeString(posix.permissions(), isDir);
                Object nl = Files.getAttribute(p, "unix:nlink");
                if (nl != null) {
                    nlink = nl.toString();
                }
            } catch (UnsupportedOperationException | IOException noPosix) {
                boolean readOnly = !Files.isWritable(p);
                mode = Format.nonPosixMode(readOnly, isDir);
            }
        } catch (IOException e) {
            // Entry vanished between listing and stat; show a placeholder row.
            isDir = false;
            mode = Format.nonPosixMode(true, false);
        }
        return new Entry(name, isDir, size, mode, nlink, modified);
    }

    // ── file serving (full + range) ─────────────────────────────────────────────

    private void serveFile(HttpExchange ex, Path path, long size, String method, boolean allowRange)
            throws IOException {
        String contentType = MimeTypes.guess(path);

        if (allowRange) {
            String rangeHeader = ex.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null) {
                RangeParser.Range range = RangeParser.parse(rangeHeader, size);
                if (range == null) {
                    ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                    ex.sendResponseHeaders(416, -1);
                    return;
                }
                serveRange(ex, path, range, size, contentType, method);
                return;
            }
        }

        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        if ("HEAD".equals(method)) {
            ex.getResponseHeaders().set("Content-Length", Long.toString(size));
            ex.sendResponseHeaders(200, -1);
            return;
        }

        ex.sendResponseHeaders(200, size == 0 ? -1 : size);
        if (size > 0) {
            try (InputStream in = Files.newInputStream(path)) {
                in.transferTo(ex.getResponseBody());
            }
        }
    }

    private void serveRange(HttpExchange ex, Path path, RangeParser.Range range,
                            long size, String contentType, String method) throws IOException {
        long length = range.length();
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Content-Range",
                "bytes " + range.start() + "-" + range.end() + "/" + size);
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        if ("HEAD".equals(method)) {
            ex.getResponseHeaders().set("Content-Length", Long.toString(length));
            ex.sendResponseHeaders(206, -1);
            return;
        }

        ex.sendResponseHeaders(206, length);
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(range.start());
            OutputStream os = ex.getResponseBody();
            byte[] buf = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                os.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    // ── response helpers ──────────────────────────────────────────────────────

    private void plain(HttpExchange ex, int status, String msg) throws IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(301, -1);
    }
}
