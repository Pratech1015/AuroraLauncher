package aurora.launcher;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal HTTP client wrapper around {@link java.net.http.HttpClient}.
 *
 * Features used only by the launcher:
 * <ul>
 *   <li>GET with automatic gzip/deflate handling</li>
 *   <li>Range/resume downloads with SHA-1 verification</li>
 *   <li>A tiny progress ticker printed to stderr</li>
 * </ul>
 */
public final class Http {

    private final HttpClient client;
    private final Path logsDir;
    private final Printer printer;

    public Http(AuroraEngine engine, Path logsDir) {
        this.logsDir = logsDir;
        this.printer = new Printer();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** GET {@code url}, returning the decoded body (or {@code null} on 404). */
    public String get(String url, String range) throws Exception {
        HttpRequest.Builder rb = newBuilder(url);
        if (range != null) rb.header("Range", range);
        return read(client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** GET {@code url} with a single extra header (e.g. Authorization), returning decoded body. */
    public String getWithHeader(String url, String headerName, String headerValue) throws Exception {
        HttpRequest.Builder rb = newBuilder(url);
        if (headerName != null) rb.header(headerName, headerValue);
        return read(client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private HttpRequest.Builder newBuilder(String url) {
        return HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(60))
                .GET()
                .header("Accept", "*/*")
                .header("User-Agent", "AuroraLauncher/1.0")
                .header("Accept-Encoding", "gzip, deflate")
                .uri(URI.create(url));
    }


    /** POST a form or raw body. Returns the response body text for any status (callers parse errors). */
    public String post(String url, String body, String contentType) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "*/*")
                .header("User-Agent", "AuroraLauncher/1.0")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .uri(URI.create(url))
                .build();
        return read(client.send(req, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** POST a form-encoded body built from {@code form}, returning the response body text. */
    public String postForm(String url, Map<String, String> form) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!b.isEmpty()) b.append('&');
            b.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return post(url, b.toString(), "application/x-www-form-urlencoded");
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String read(HttpResponse<byte[]> resp) throws IOException {
        int code = resp.statusCode();
        if (code == 404) return null;
        byte[] bytes = resp.body();
        String enc = resp.headers().firstValue("Content-Encoding").orElse("");
        if (enc.equalsIgnoreCase("gzip") && bytes.length > 0 && bytes[0] == 0x1f) bytes = ungzip(bytes);
        else if (enc.equalsIgnoreCase("deflate")) bytes = inflate(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Download {@code url} to {@code target}, with SHA-1 verification.
     *  If a verified copy already exists it is kept. Prints progress to stderr.
     *  Transparently retries on truncated streams / I/O errors. */
    public boolean download(String url, Path target, String expectedSha1, long expectedSize) throws Exception {
        if (Files.exists(target)) {
            if (expectedSha1 == null || Http.verify(target, expectedSha1)) return true;
            Files.deleteIfExists(target);
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "dl-", ".tmp");
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                downloadOnce(url, tmp, expectedSha1, expectedSize);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) {
                    System.err.println("  ↳ retry " + attempt + " for " + shortName(url) + ": " + e.getMessage());
                }
                // Clean partial so the retry starts fresh.
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
        throw last;
    }

    private void downloadOnce(String url, Path tmp, String expectedSha1, long expectedSize) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(120))
                .GET()
                .header("User-Agent", "AuroraLauncher/1.0")
                .uri(URI.create(url))
                .build();
        // Best-effort progress ticker while HttpClient streams bytes to disk.
        Thread poller = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        long have = Files.size(tmp);
                        if (expectedSize > 0)
                            System.err.printf("\r  \u2193 %s %d/%d bytes (%d%%)",
                                    shortName(url), have, expectedSize, (have * 100) / expectedSize);
                        else
                            System.err.printf("\r  \u2193 %s %d bytes", shortName(url), have);
                        Thread.sleep(750);
                    } catch (Exception e) { break; }
                }
            } finally {
                System.err.println();
            }
        }, "progress-" + shortName(url));
        poller.setDaemon(true);
        poller.start();
        try {
            HttpResponse<Path> resp = client.send(req,
                    HttpResponse.BodyHandlers.ofFile(tmp,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            int code = resp.statusCode();
            if (code == 404) throw new FileNotFoundException(url);
            if (code >= 400) throw new IOException("HTTP " + code + " for " + url);
        } finally {
            poller.interrupt();
            try { poller.join(1000); } catch (InterruptedException ignored) {}
        }
        if (expectedSha1 != null && !Http.verify(tmp, expectedSha1)) {
            throw new IOException("checksum mismatch for " + shortName(url));
        }
    }

    private static String shortName(String url) {
        int i = url.lastIndexOf('/');
        return i < 0 ? url : url.substring(i + 1);
    }

    private static byte[] ungzip(byte[] gz) throws IOException {
        try (var bais = new ByteArrayInputStream(gz);
             var gis = new GZIPInputStream(bais);
             var bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static byte[] inflate(byte[] data) throws IOException {
        try (var bais = new ByteArrayInputStream(data);
             var dis = new java.util.zip.InflaterInputStream(bais);
             var bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = dis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static boolean verify(Path target, String expectedSha1) throws Exception {
        if (!Files.exists(target)) return false;
        var md = java.security.MessageDigest.getInstance("SHA-1");
        try (var is = Files.newInputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        String got = bytesToHex(md.digest());
        return got.equalsIgnoreCase(expectedSha1);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xf, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(x & 0xf, 16)));
        }
        return sb.toString();
    }
}
