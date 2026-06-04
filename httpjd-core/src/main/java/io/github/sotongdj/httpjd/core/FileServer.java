package io.github.sotongdj.httpjd.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A small, hardened static-file HTTP server — the Java port of {@code httprd}.
 *
 * <p>Built on the JDK's {@code com.sun.net.httpserver}, it has zero external
 * dependencies so it compiles cleanly to a GraalVM native image. Bind to port
 * {@code 0} to let the OS choose an ephemeral port (useful for tests); the
 * chosen port is then available from {@link #port()}.
 */
public final class FileServer implements AutoCloseable {

    private final HttpServer server;
    private final Path root;
    private final boolean showIndex;

    /**
     * Create a server bound to {@code host:port}, serving {@code dir}.
     *
     * @param dir       the root directory to serve (resolved to its real path)
     * @param showIndex whether to render directory listings when no index file exists
     * @param host      the bind address, e.g. {@code 0.0.0.0} or {@code 127.0.0.1}
     * @param port      the TCP port, or {@code 0} for an OS-assigned ephemeral port
     * @throws IOException if {@code dir} is inaccessible or the port cannot be bound
     */
    public FileServer(Path dir, boolean showIndex, String host, int port) throws IOException {
        this.root = dir.toRealPath();
        if (!Files.isDirectory(root)) {
            throw new IOException("not a directory: " + dir);
        }
        this.showIndex = showIndex;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/", new RequestHandler(root, showIndex));
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    /** Stop the server, allowing up to {@code delaySeconds} for in-flight exchanges. */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    @Override
    public void close() {
        stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public Path root() {
        return root;
    }

    public boolean showIndex() {
        return showIndex;
    }
}
