package notification;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transparent TCP proxy that counts application-layer bytes in both directions.
 * Used to measure the REAL bytes on the wire per request (headers + framing + body),
 * not just the serialized body. IP/TCP headers are not included.
 */
public final class CountingProxy implements AutoCloseable {
    public final AtomicLong clientToServer = new AtomicLong();
    public final AtomicLong serverToClient = new AtomicLong();
    private final ServerSocket listener;
    private final int targetPort;

    public CountingProxy(int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "proxy-accept");
        t.setDaemon(true);
        t.start();
    }

    public int port() { return listener.getLocalPort(); }

    public void reset() {
        clientToServer.set(0);
        serverToClient.set(0);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket client = listener.accept();
                Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                client.setTcpNoDelay(true);
                server.setTcpNoDelay(true);
                pump(client, server, clientToServer);
                pump(server, client, serverToClient);
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void pump(Socket from, Socket to, AtomicLong counter) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16 * 1024];
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    counter.addAndGet(n);
                }
            } catch (IOException ignored) {
                // connection closed
            } finally {
                try { from.close(); } catch (IOException ignored) { }
                try { to.close(); } catch (IOException ignored) { }
            }
        }, "proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException { listener.close(); }
}
