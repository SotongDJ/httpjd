package io.github.sotongdj.httpjd.core;

/**
 * RFC 7233 {@code Range: bytes=…} parser — a direct port of {@code parse_range}
 * in {@code httprd}'s {@code main.rs}.
 *
 * <p>Byte positions are treated as unsigned 64-bit values (as in Rust's
 * {@code u64}); {@link Long#compareUnsigned} and {@link Long#parseUnsignedLong}
 * keep the arithmetic faithful even for {@code bytes=0-18446744073709551615}.
 * Multi-range requests are reduced to the first range only.
 */
public final class RangeParser {

    private RangeParser() {}

    /** Inclusive byte range {@code [start, end]}. */
    public record Range(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }

    /**
     * Parse a {@code Range} header value against a known file size.
     *
     * @return the satisfiable inclusive range, or {@code null} if the header is
     *         malformed or not satisfiable (caller responds {@code 416}).
     */
    public static Range parse(String rangeStr, long fileSize) {
        if (rangeStr == null || !rangeStr.startsWith("bytes=")) {
            return null;
        }
        String spec = rangeStr.substring("bytes=".length());
        int comma = spec.indexOf(',');
        String first = (comma >= 0 ? spec.substring(0, comma) : spec).trim();

        if (fileSize == 0) {
            return null;
        }

        if (first.startsWith("-")) {
            // Suffix range: bytes=-N → last N bytes.
            Long n = parseUnsigned(first.substring(1).trim());
            if (n == null || n == 0) {
                return null;
            }
            long start = Long.compareUnsigned(n, fileSize) > 0 ? 0 : fileSize - n;
            return new Range(start, fileSize - 1);
        }

        int dash = first.indexOf('-');
        if (dash < 0) {
            return null;
        }
        Long start = parseUnsigned(first.substring(0, dash).trim());
        if (start == null || Long.compareUnsigned(start, fileSize) >= 0) {
            return null;
        }
        String endStr = first.substring(dash + 1).trim();
        long end;
        if (endStr.isEmpty()) {
            end = fileSize - 1;
        } else {
            Long parsed = parseUnsigned(endStr);
            if (parsed == null) {
                return null;
            }
            end = Long.compareUnsigned(parsed, fileSize - 1) > 0 ? fileSize - 1 : parsed;
        }
        if (Long.compareUnsigned(start, end) > 0) {
            return null;
        }
        return new Range(start, end);
    }

    private static Long parseUnsigned(String s) {
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseUnsignedLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
