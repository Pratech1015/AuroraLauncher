package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Fetches the Mojang version manifest and resolves per-version metadata
 * documents (``<id>.json`` from {@code meta.minecraft.net}).
 *
 * All network traffic is JSON-over-HTTPS pulled from the public, unauthenticated
 * launcher metadata endpoints — no session/auth tokens are used.
 */
public final class Manifest {

    private final Http http;
    private final Path cacheDir;

    // Cache the manifest for the lifetime of a run; force-refresh on request.
    private volatile Map<String, Object> cachedManifest;
    private final DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;

    public Manifest(Http http) {
        this.http = http;
        this.cacheDir = AuroraEngine.defaultRoot().resolve("cache");
        try { Files.createDirectories(cacheDir); } catch (IOException ignored) {}
    }

    /** Latest-ish versions available to download (sorted newest-first). */
    public List<Version> latest() throws Exception {
        Map<String, Object> m = manifest();
        List<Object> raw = Json.arr(m, "versions");
        List<Version> out = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> v = (Map<String, Object>) o;
            out.add(new Version(Json.str(v, "id"), Json.str(v, "type"),
                    Json.str(v, "releaseTime"), Json.str(v, "url")));
        }
        out.sort(Comparator.comparing((Version v) -> v.releaseTime).reversed());
        return out;
    }

    public static final class Version {
        public final String id, type, releaseTime, url;
        public Version(String id, String type, String releaseTime, String url) {
            this.id = id; this.type = type; this.releaseTime = releaseTime; this.url = url;
        }
    }

    /** Download (or load from cache) the manifest, refreshing if stale. */
    private Map<String, Object> manifest() throws Exception {
        Map<String, Object> m = cachedManifest;
        if (m != null) return m;
        Path cached = cacheDir.resolve("manifest.json");
        try {
            m = loadWithRefresh(cached);
        } catch (Exception e) {
            m = fromUrl(http.get(AuroraEngine.MANIFEST_URL, null));
        }
        cachedManifest = m;
        return m;
    }

    private Map<String, Object> loadWithRefresh(Path cached) throws Exception {
        String body;
        // If we have a fresh-enough cache (within 6h), use it; else refetch.
        boolean fresh = false;
        if (Files.exists(cached)) {
            Instant mod = Files.getLastModifiedTime(cached).toInstant();
            fresh = Duration.between(mod, Instant.now()).toHours() < 6;
        }
        if (fresh) {
            body = Files.readString(cached, StandardCharsets.UTF_8);
        } else {
            body = http.get(AuroraEngine.MANIFEST_URL, null);
            Files.writeString(cached, body, StandardCharsets.UTF_8);
        }
        return fromUrl(body);
    }

    private static Map<String, Object> fromUrl(String body) {
        Object o = Json.parse(body);
        if (!(o instanceof Map)) throw new IllegalArgumentException("manifest not an object");
        return (Map<String, Object>) o;
    }

    /** Resolve & cache a full per-version document. */
    public Map<String, Object> versionDoc(String versionId) throws Exception {
        Path f = cacheDir.resolve(versionId + ".json");
        if (Files.exists(f)) {
            try {
                return (Map<String, Object>) Json.parse(f);
            } catch (Exception ignored) {}
        }
        // Find the manifest entry for this exact id.
        Map<String, Object> m = manifest();
        for (Object o : Json.arr(m, "versions")) {
            Map<String, Object> v = (Map<String, Object>) o;
            if (Json.str(v, "id").equals(versionId)) {
                String url = Json.str(v, "url");
                String body = http.get(url, null);
                Files.writeString(f, body, StandardCharsets.UTF_8);
                return (Map<String, Object>) Json.parse(body);
            }
        }
        throw new IllegalArgumentException("Unknown version: " + versionId);
    }
}
