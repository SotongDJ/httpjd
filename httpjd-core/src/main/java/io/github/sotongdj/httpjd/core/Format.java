package io.github.sotongdj.httpjd.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;

/**
 * Formatting and encoding helpers — the Java counterpart of the small
 * free functions at the bottom of {@code httprd}'s {@code main.rs}
 * (percent-encoding set, {@code fmt_mode}, {@code fmt_time}, {@code html_escape}).
 */
public final class Format {

    private Format() {}

    /**
     * Characters that must be percent-encoded inside a URL path segment.
     * Mirrors the {@code PATH_SEGMENT} {@code AsciiSet} in the Rust source.
     */
    private static final String SEGMENT_RESERVED = " \"#%<>?[\\]^`{|}&";

    // ── percent-encoding ─────────────────────────────────────────────────────

    /** Percent-encode a single path segment for use inside an {@code href}. */
    public static String encodeSegment(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c < 0x20 || c >= 0x7F || SEGMENT_RESERVED.indexOf(c) >= 0) {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            } else {
                out.append((char) c);
            }
        }
        return out.toString();
    }

    /**
     * Percent-decode a raw URI path to a UTF-8 string, replacing malformed
     * sequences with the Unicode replacement character (the equivalent of
     * Rust's {@code decode_utf8_lossy}).
     */
    public static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            if (c < 0x80) {
                out.write(c);
            } else {
                byte[] enc = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(enc, 0, enc.length);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ── HTML escaping ─────────────────────────────────────────────────────────

    public static String htmlEscape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default  -> out.append(c);
            }
        }
        return out.toString();
    }

    // ── permission / mode strings ──────────────────────────────────────────────

    private static final PosixFilePermission[] MODE_BITS = {
        PosixFilePermission.OWNER_READ,  PosixFilePermission.OWNER_WRITE,  PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,  PosixFilePermission.GROUP_WRITE,  PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
    };
    private static final char[] MODE_CHARS = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};

    /** Render POSIX permissions like {@code ls -l}, e.g. {@code drwxr-xr-x}. */
    public static String modeString(Set<PosixFilePermission> perms, boolean isDir) {
        StringBuilder s = new StringBuilder(10);
        s.append(isDir ? 'd' : '-');
        for (int i = 0; i < MODE_BITS.length; i++) {
            s.append(perms.contains(MODE_BITS[i]) ? MODE_CHARS[i] : '-');
        }
        return s.toString();
    }

    /** Fallback mode string for filesystems without POSIX permissions. */
    public static String nonPosixMode(boolean readOnly, boolean isDir) {
        return (isDir ? "d" : "-") + (readOnly ? "r--r--r--" : "rw-rw-rw-");
    }

    // ── time formatting ─────────────────────────────────────────────────────────

    /**
     * Format an instant like {@code ls -l}: time-of-day for files newer than
     * ~6 months, otherwise the year. Day-of-month is space-padded to width 2,
     * matching {@code strftime}'s {@code %e}.
     */
    public static String lsTime(Instant modified, ZoneId zone, Instant now) {
        LocalDateTime dt = LocalDateTime.ofInstant(modified, zone);
        String month = dt.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        Duration age = Duration.between(modified, now);
        if (!age.isNegative() && age.toDays() < 182) {
            return String.format("%s %2d %02d:%02d",
                    month, dt.getDayOfMonth(), dt.getHour(), dt.getMinute());
        }
        return String.format("%s %2d  %d", month, dt.getDayOfMonth(), dt.getYear());
    }

    // ── human-readable sizes (binary / IEC) ──────────────────────────────────────

    private static final String[] BINARY_UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};

    /** Human-readable size using 1024-based IEC units, like {@code humansize}'s {@code BINARY}. */
    public static String humanSizeBinary(long bytes) {
        if (bytes < 1024) {
            return bytes + " " + BINARY_UNITS[0];
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < BINARY_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        String num = String.format("%.2f", value);
        // Trim trailing zeros / dot, mirroring humansize's compact output.
        if (num.contains(".")) {
            num = num.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return num + " " + BINARY_UNITS[unit];
    }
}
