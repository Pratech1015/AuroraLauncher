package aurora.launcher;

import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal Discord Rich Presence client over the Discord IPC socket.
 *
 * Uses Java NIO Unix domain sockets ({@code java.net.UnixDomainSocketAddress},
 * available since Java 16). Connects to the first writable {@code discord-ipc-0}
 * socket found along the standard platform paths, performs the IPC handshake,
 * then can push {@code SET_ACTIVITY} payloads (rich presence).
 *
 * On Windows this class no-ops (named pipes require JNA; out of scope here).
 * Every operation is best-effort: if Discord isn't running, the socket simply
 * isn't found and all methods return gracefully.
 *
 * Replace {@link #APP_ID} with a real registered Discord application id.
 */
public final class DiscordRpc implements Closeable {

    /** Your Discord application id. Change this to a real registered app. */
    public static final String APP_ID = "1536033390090911765";

    private static final int HANDSHAKE = 0, FRAME = 1, PING = 2, PONG = 3, CLOSE = 4;
    private static final AtomicBoolean windows = new AtomicBoolean();
    static { windows.set(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")); }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aurora-discord-rpc"); t.setDaemon(true); return t;
    });
    private SocketChannel sock;
    private boolean connected;

    public DiscordRpc() {}

    public boolean connect() {
        if (windows.get()) return false; // named pipes not supported here
        if (connected) return true;
        List<String> candidates = candidatePaths();
        for (String p : candidates) {
            Path socket = Path.of(p);
            if (!Files.exists(socket)) continue;
            try {
                var addr = UnixDomainSocketAddress.of(socket);
                sock = SocketChannel.open(StandardProtocolFamily.UNIX);
                sock.connect(addr);
                if (doHandshake()) {
                    connected = true;
                    startHeartbeat();
                    return true;
                }
            } catch (Exception e) {
                closeQuietly(sock);
            }
        }
        connected = false;
        return false;
    }

    /** True if a live IPC connection has completed the handshake. */
    public boolean isConnected() { return connected; }

    private boolean doHandshake() {
        try {
            send(HANDSHAKE, "{\"v\":1,\"client_id\":\"" + APP_ID + "\"}");
            Frame f = read(4000);
            if (f == null) return false;
            if (f.opcode == 0) return true;          // HANDSHAKE_OK
            if (f.opcode == 4) return false;          // CLOSE-ish
            return f.opcode == 1;                     // accept a frame reply too
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Publish a rich-presence activity.
     *
     * @param details   long line, e.g. "Minecraft 1.14.4"
     * @param state     short line, e.g. "Singleplayer"
     * @param largeImage asset id (Discord art id), e.g. "minecraft"
     * @param largeText  hover text for the large image
     */
    public boolean setActivity(String details, String state, String largeImage, String largeText) {
        if (!connected) return false;
        long now = Instant.now().getEpochSecond();
        String args = "{\"activity\":{"
                + "\"state\":\"" + esc(state) + "\","
                + "\"details\":\"" + esc(details) + "\","
                + "\"timestamps\":{\"start\":" + now + "},"
                + "\"assets\":{\"large_image\":\"" + esc(largeImage) + "\","
                + "\"large_text\":\"" + esc(largeText) + "\"},"
                + "\"instance\":false},\"session_id\":\"" + APP_ID + "\"}";
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":" + args
                + ",\"nonce\":\"" + UUID.randomUUID() + "\"}";
        try {
            send(FRAME, payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean clearActivity() {
        if (!connected) return false;
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"activity\":null,\"session_id\":\""
                + APP_ID + "\"},\"nonce\":\"" + UUID.randomUUID() + "\"}";
        try { send(FRAME, payload); return true; } catch (Exception e) { return false; }
    }

    // -- IPC framing: [opcode:int32 LE][length:int32 LE][payload bytes] --

    private void send(int opcode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + body.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(opcode).putInt(body.length).put(body);
        buf.flip();
        while (buf.hasRemaining()) sock.write(buf);
    }

    private Frame read() throws IOException {
        return read(0);
    }

    /** Read one IPC frame, optionally bounded by {@code timeoutMs} (0 = block). */
    private Frame read(long timeoutMs) throws IOException {
        if (timeoutMs > 0 && !isReadable(timeoutMs)) return null;
        ByteBuffer hdr = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (hdr.hasRemaining()) {
            if (sock.read(hdr) == -1) return null;
        }
        hdr.flip();
        int opcode = hdr.getInt();
        int len = hdr.getInt();
        if (len < 0 || len > 16 * 1024 * 1024) return null;
        ByteBuffer body = ByteBuffer.allocate(len);
        while (body.hasRemaining()) {
            if (sock.read(body) == -1) return null;
        }
        body.flip();
        byte[] bytes = new byte[len];
        body.get(bytes);
        return new Frame(opcode, new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Block until the channel has a frame waiting (or {@code timeoutMs} elapses),
     * without hanging the thread. UNIX-domain {@code SocketChannel}s do not
     * support {@code socket().setSoTimeout}, so we drive the timeout via a
     * {@link Selector}.
     */
    private boolean isReadable(long timeoutMs) throws IOException {
        sock.configureBlocking(false);
        SelectionKey key = null;
        Selector sel = null;
        try {
            sel = Selector.open();
            key = sock.register(sel, SelectionKey.OP_READ);
            return sel.select(timeoutMs) > 0;
        } finally {
            if (key != null) key.cancel();
            if (sel != null) sel.close();
            sock.configureBlocking(true);
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                send(PING, "{\"nonce\":\"" + UUID.randomUUID() + "\"}");
                Frame f = read();
                if (f == null) { connected = false; }
            } catch (Exception e) {
                connected = false;
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private static List<String> candidatePaths() {
        List<String> out = new ArrayList<>();
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null) out.add(xdg + "/discord-ipc-0");
        String uid = "";
        try { uid = String.valueOf((int) java.nio.file.Files.getAttribute(
                Path.of("/proc/self"), "unix:uid")); } catch (Exception ignored) {}
        if (!uid.isEmpty()) out.add("/run/user/" + uid + "/discord-ipc-0");
        String home = System.getProperty("user.home");
        out.add(home + "/.config/discord-ipc-0");
        out.add(home + "/.discord-ipc-0");
        out.add(home + "/Library/Application Support/Discord/DiscordCanary/discord-ipc-0");
        out.add(home + "/Library/Application Support/DiscordCanary/discord-ipc-0");
        out.add(home + "/Library/Application Support/Discord PTB/discord-ipc-0");
        out.add(home + "/Library/Application Support/Discord/development/discord-ipc-0");
        out.add(home + "/Library/Application Support/Discord/stable/discord-ipc-0");
        return out;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(Channel c) {
        try { if (c != null && c.isOpen()) c.close(); } catch (Exception ignored) {}
    }

    /** Tear down the IPC link, clearing any active presence first. */
    public void disconnect() {
        try { clearActivity(); } catch (Exception ignored) {}
        close();
    }

    @Override
    public void close() {
        connected = false;
        scheduler.shutdownNow();
        closeQuietly(sock);
    }

    private record Frame(int opcode, String data) {}
}
