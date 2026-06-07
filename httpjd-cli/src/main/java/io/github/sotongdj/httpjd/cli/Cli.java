package io.github.sotongdj.httpjd.cli;

import io.github.sotongdj.httpjd.core.FileServer;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command-line entry point for httpjd — the Java port of httprd.
 *
 * <p>Arguments mirror the original clap interface:
 * <pre>
 *   -p, --port &lt;PORT&gt;   Port to listen on            [default: 8080]
 *   -d, --dir  &lt;DIR&gt;    Root directory to serve      [default: .]
 *   -i, --index         Show directory listing when no index file exists
 * </pre>
 */
public final class Cli {

    private static final String NAME = "httpjd";
    private static final String VERSION = "0.2.3";

    public static void main(String[] args) {
        int port = 8080;
        String dir = ".";
        boolean showIndex = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> {
                    printHelp(System.out);
                    return;
                }
                case "-V", "--version" -> {
                    System.out.println(NAME + " " + VERSION);
                    return;
                }
                case "-i", "--index" -> showIndex = true;
                case "-p", "--port" -> {
                    if (++i >= args.length) {
                        fail("option '" + a + "' requires a value");
                    }
                    port = parsePort(args[i]);
                }
                case "-d", "--dir" -> {
                    if (++i >= args.length) {
                        fail("option '" + a + "' requires a value");
                    }
                    dir = args[i];
                }
                default -> {
                    if (a.startsWith("--port=")) {
                        port = parsePort(a.substring("--port=".length()));
                    } else if (a.startsWith("--dir=")) {
                        dir = a.substring("--dir=".length());
                    } else {
                        fail("unexpected argument '" + a + "'");
                    }
                }
            }
        }

        FileServer server;
        try {
            server = new FileServer(Path.of(dir), showIndex, "0.0.0.0", port);
        } catch (IOException e) {
            System.err.println("error: cannot access '" + dir + "': " + e.getMessage());
            System.exit(1);
            return;
        }

        System.err.printf("Serving '%s' on port %d%s%n",
                server.root(), port, showIndex ? " (directory listing enabled)" : "");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p < 0 || p > 65535) {
                throw new NumberFormatException();
            }
            return p;
        } catch (NumberFormatException e) {
            fail("invalid port '" + s + "'");
            return -1; // unreachable
        }
    }

    private static void fail(String msg) {
        System.err.println("error: " + msg);
        System.err.println("Try '" + NAME + " --help' for more information.");
        System.exit(2);
    }

    private static void printHelp(java.io.PrintStream out) {
        out.println("HTTP file server with resumable-download support");
        out.println();
        out.println("Usage: " + NAME + " [OPTIONS]");
        out.println();
        out.println("Options:");
        out.println("  -p, --port <PORT>  Port to listen on [default: 8080]");
        out.println("  -d, --dir <DIR>    Root directory to serve [default: .]");
        out.println("  -i, --index        Show directory listing when no index file exists");
        out.println("  -h, --help         Print help");
        out.println("  -V, --version      Print version");
    }

    private Cli() {}
}
