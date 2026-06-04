package io.github.sotongdj.httpjd.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Extension → MIME-type guessing, the counterpart of the {@code mime_guess}
 * crate used by {@code httprd}. Unknown extensions fall back to
 * {@code application/octet-stream} (mirroring {@code first_or_octet_stream}).
 */
public final class MimeTypes {

    private MimeTypes() {}

    public static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> BY_EXT = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("htm",  "text/html"),
            Map.entry("css",  "text/css"),
            Map.entry("js",   "text/javascript"),
            Map.entry("mjs",  "text/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("xml",  "text/xml"),
            Map.entry("txt",  "text/plain"),
            Map.entry("md",   "text/markdown"),
            Map.entry("csv",  "text/csv"),
            Map.entry("pdf",  "application/pdf"),
            Map.entry("zip",  "application/zip"),
            Map.entry("gz",   "application/gzip"),
            Map.entry("tar",  "application/x-tar"),
            Map.entry("png",  "image/png"),
            Map.entry("jpg",  "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif",  "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("svg",  "image/svg+xml"),
            Map.entry("ico",  "image/x-icon"),
            Map.entry("bmp",  "image/bmp"),
            Map.entry("mp3",  "audio/mpeg"),
            Map.entry("wav",  "audio/wav"),
            Map.entry("ogg",  "audio/ogg"),
            Map.entry("mp4",  "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf",  "font/ttf"),
            Map.entry("wasm", "application/wasm")
    );

    public static String guess(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return DEFAULT;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXT.getOrDefault(ext, DEFAULT);
    }
}
