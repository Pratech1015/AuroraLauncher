package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a Minecraft version: downloads the version document, the client
 * jar, all declared libraries, the asset index + referenced objects, and
 * extracts native libraries. Also figures out the launch classpath and the
 * main class.
 */
public final class Installer {

    private final AuroraEngine engine;
    private final Http http;
    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Manifest manifest;
    private final Printer printer;

    public Installer(AuroraEngine engine, Http http, Path versionsDir,
                     Path librariesDir, Path assetsDir) {
        this.engine = engine;
        this.http = http;
        this.versionsDir = versionsDir;
        this.librariesDir = librariesDir;
        this.assetsDir = assetsDir;
        this.manifest = new Manifest(http);
        this.printer = new Printer();
    }

    /** Result of attempting to install a version. */
    public static final class InstallResult {
        public final boolean success;
        public final String resolvedVersion;
        public final String error;
        public InstallResult(boolean s, String rv, String e) {
            this.success = s; this.resolvedVersion = rv; this.error = e;
        }
    }

    /** A fully-installed Minecraft instance ready to launch. */
    public static final class MinecraftInstall {
        public final Path workDir;      // versions/<id>
        public final Path clientJar;    // the game jar
        public final Path nativeDir;    // versions/<id>/natives
        public final List<Path> classpath; // libs + client
        public final String mainClass;
        public final int requiredJava;
        public final String versionId;
        public final String assetIndex;
        public final String versionType;   // "release"/"snapshot"
        public final List<Object> jvmArgs;  // raw argument spec from the doc
        public final List<Object> gameArgs;
        public final Map<String, Object> logging;
        public final Map<String, Object> versionDoc; // full doc (for legacy arg fallback)

        public MinecraftInstall(Path w, Path c, Path n, List<Path> cp,
                                String mc, int rj, String id, String ai,
                                String vt, List<Object> jvm, List<Object> game,
                                Map<String, Object> log, Map<String, Object> doc) {
            this.workDir = w; this.clientJar = c; this.nativeDir = n;
            this.classpath = cp; this.mainClass = mc; this.requiredJava = rj;
            this.versionId = id; this.assetIndex = ai;
            this.versionType = vt; this.jvmArgs = jvm; this.gameArgs = game;
            this.logging = log; this.versionDoc = doc;
        }
    }

    public InstallResult install(String versionId) throws Exception {
        try {
            Map<String, Object> doc = manifest.versionDoc(versionId);
            // Resolve "inheritsFrom" (modern manifests inherit from a base).
            String inherits = Json.str(doc, "inheritsFrom");
            if (inherits != null && !inherits.isEmpty()) {
                // We install the parent too; for launching we use the child doc.
                installIfNeeded(inherits);
            }
            String resolved = Json.str(doc, "id");
            downloadVersionArtifacts(doc);
            return new InstallResult(true, resolved, null);
         } catch (Exception e) {
            return new InstallResult(false, null, e.toString());
        }
    }

    /** Prepare and return a ready-to-launch instance, installing if absent. */
    public MinecraftInstall prepare(String versionId) throws Exception {
        Map<String, Object> doc = manifest.versionDoc(versionId);
        String resolved = Json.str(doc, "id");
        Path workDir = versionsDir.resolve(resolved);
        if (!isFullyInstalled(doc, resolved)) {
            downloadVersionArtifacts(doc);
        }
        return buildInstall(doc, workDir, resolved);
    }

    private boolean isFullyInstalled(Map<String, Object> doc, String resolved) {
        Path workDir = versionsDir.resolve(resolved);
        Path client = workDir.resolve(resolved + ".jar");
        return Files.exists(client);
    }

    private void installIfNeeded(String parent) throws Exception {
        Map<String, Object> pdoc = manifest.versionDoc(parent);
        String presolved = Json.str(pdoc, "id");
        if (!isFullyInstalled(pdoc, presolved)) downloadVersionArtifacts(pdoc);
    }

    private void downloadVersionArtifacts(Map<String, Object> doc) throws Exception {
        String id = Json.str(doc, "id");
        Path workDir = versionsDir.resolve(id);
        Files.createDirectories(workDir);
        Files.createDirectories(workDir.resolve("natives"));

        // --- client jar ---
        Map<String, Object> dl = Json.map(doc, "downloads");
        if (dl != null && dl.containsKey("client")) {
            Map<String, Object> c = Json.map(dl, "client");
            String url = Json.str(c, "url");
            String sha1 = Json.str(c, "sha1");
            long size = ((Number) c.getOrDefault("size", 0L)).longValue();
            Path target = workDir.resolve(id + ".jar");
            downloadOrFail(url, target, sha1, size);
        }

        // --- libraries ---
        downloadLibraries(Json.arr(doc, "libraries"));
        // --- assets ---
        downloadAssets(doc);
        // --- logging (log4j2.xml) ---
            resolveLogging(doc, workDir); // ensure the log config file exists
    }

    private void downloadLibraries(List<Object> libs) throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futs = new ArrayList<>();
        for (Object o : libs) {
            Map<String, Object> lib = (Map<String, Object>) o;
            String name = Json.str(lib, "name");
            if (name == null) continue;
            // Skip native-specific libs that don't apply (we pick later); download all, filter at build.
            if (lib.containsKey("downloads") && !((Map<String, Object>) lib.get("downloads")).isEmpty()) {
                // has explicit per-artifact downloads
                downloadLibraryWithDownloads(lib, pool, futs);
            } else {
                // legacy form: downloads.classifiers + url
                downloadLegacyLibrary(lib);
            }
        }
        for (var f : futs) try { f.get(); } catch (Exception ignored) {}
        pool.shutdown();
    }

    private void downloadLibraryWithDownloads(Map<String, Object> lib,
                                              ExecutorService pool, List<Future<?>> futs) {
        Map<String, Object> dl = Json.map(lib, "downloads");
        // 1) The primary artifact (the jar that goes on the classpath).
        Map<String, Object> artifact = Json.map(dl, "artifact");
        submitArtifact(artifact, pool, futs);

        // 2) Native classifiers for the current OS. `natives` maps os -> classifier name.
        Map<String, Object> natives = Json.map(lib, "natives");
        if (!natives.isEmpty()) {
            String os = osName();
            Object nat = natives.get(os);
            if (nat != null) {
                String classifier = nat instanceof List
                        ? String.join(",", ((List<?>) nat).stream().map(Object::toString).toArray(String[]::new))
                        // older manifests use a single string; 1.18+ can map to a list like ["linux"]
                        : nat.toString();
                // Handle comma-separated or single classifier.
                for (String cls : classifier.split(",")) {
                    Map<String, Object> clsArt = Json.map(Json.map(dl, "classifiers"), cls);
                    if (!clsArt.isEmpty()) submitArtifact(clsArt, pool, futs);
                }
            }
        }
    }

    private void submitArtifact(Map<String, Object> art, ExecutorService pool, List<Future<?>> futs) {
        if (art.isEmpty()) return;
        String url = Json.str(art, "url");
        if (url == null) return;
        String sha1 = Json.str(art, "sha1");
        long size = ((Number) art.getOrDefault("size", 0L)).longValue();
        String path = Json.str(art, "path");
        Path target = librariesDir.resolve(path);
        futs.add(pool.submit(() -> {
            try {
                if (Files.exists(target) && (sha1 == null || Http.verify(target, sha1))) return;
                http.download(url, target, sha1, size);
            } catch (Exception e) {
                System.err.println("  [lib] " + path + " failed: " + e);
            }
        }));
    }

    /** Legacy libraries: { "name": group:artifact:version[:classifier], "url": "...", downloads: {} } */
    private void downloadLegacyLibrary(Map<String, Object> lib) throws Exception {
        String name = Json.str(lib, "name");
        String url = Json.str(lib, "url");
        if (url == null) {
            // Default to Mojang's library base if no url provided.
            url = AuroraEngine.LIBRARIES_URL;
        }
        // We can't reliably fetch size/sha1 without the manifest's per-artifact downloads,
        // so just download by conventional path if present.
        String[] parts = name.split(":");
        String path = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                + (parts.length > 3 ? "/" + parts[3] : "") + "/"
                + parts[1] + "-" + parts[2]
                + (parts.length > 3 ? "-" + parts[3] : "")
                + ".jar";
        Path target = librariesDir.resolve(parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]);
        try { http.download(url + path, target, null, 0); }
        catch (Exception e) { System.err.println("  [legacy lib] " + name + " failed: " + e.getMessage()); }
    }

    private void downloadOrFail(String url, Path target, String sha1, long size) throws Exception {
        if (Files.exists(target) && (sha1 == null || Http.verify(target, sha1))) return;
        if (!http.download(url, target, sha1, size)) {
            throw new IOException("checksum failed: " + target);
        }
    }

    private void downloadAssets(Map<String, Object> doc) throws Exception {
        Map<String, Object> di = Json.map(doc, "assetIndex");
        if (di.isEmpty()) di = Json.map(Json.map(doc, "downloads"), "assetIndex");
        String index = Json.str(di, "id");
        String indexUrl = Json.str(di, "url");
        long indexSize = ((Number) di.getOrDefault("size", 0L)).longValue();
        String indSha = Json.str(di, "sha1");
        if (index == null) index = "0";
        if (indexUrl == null) indexUrl = AuroraEngine.ASSETS_URL + index + "/indexes/" + index + ".json";
        Path idxFile = assetsDir.resolve("indexes").resolve(index + ".json");
        downloadOrFail(indexUrl, idxFile, indSha, indexSize);

        Map<String, Object> im = (Map<String, Object>) Json.parse(idxFile);
        Map<String, Object> objects = Json.map(im, "objects");
        if (objects.isEmpty()) return;
        int total = objects.size();
        System.err.println("  ↳ fetching " + total + " asset objects (may take a while)...");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futs = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        for (Object key : objects.keySet()) {
            Map<String, Object> entry = Json.map(objects, (String) key);
            String hash = Json.str(entry, "hash");
            if (hash == null) continue;
            long size = ((Number) entry.getOrDefault("size", 0L)).longValue();
            String objUrl = AuroraEngine.OBJECTS_URL + hash;
            String dir = hash.substring(0, 2);
            Path target = assetsDir.resolve("objects").resolve(dir).resolve(hash);
            futs.add(pool.submit(() -> {
                boolean ok = false;
                try { http.download(objUrl, target, hash, size); ok = true; }
                catch (Exception e) {
                    int c = done.incrementAndGet();
                    if (c % 50 == 0 || done.get() <= 5) System.err.println("  [asset] " + hash + " failed: " + e.toString());
                }
                if (ok) { int c = done.incrementAndGet(); if (c % 50 == 0) System.err.println("  ↳ assets " + c + "/" + total); }
            }));
        }
        for (var f : futs) try { f.get(); } catch (Exception ignored) {}
        pool.shutdown();
        int existing = existingObjectCount();
        System.err.println("  ↳ assets: " + existing + "/" + total + " present ("
                + (total - existing) + " will be fetched by the game on first launch).");
    }

    private int existingObjectCount() {
        int n = 0;
        try (var s = Files.walk(assetsDir.resolve("objects"))) {
            n = (int) s.filter(Files::isRegularFile).count();
        } catch (Exception ignored) {}
        return n;
    }

    private MinecraftInstall buildInstall(Map<String, Object> doc, Path workDir, String resolved) throws Exception {
        List<Path> libs = selectedLibraries(Json.arr(doc, "libraries"));
        // Deduplicate (some manifests list the same artifact under multiple rule entries).
        java.util.Set<Path> cpSet = new java.util.LinkedHashSet<>(libs);
        List<Path> cp = new ArrayList<>(cpSet);
        cp.add(0, workDir.resolve(resolved + ".jar")); // client jar first
        Path nativeDir = workDir.resolve("natives");
        // Native .so/.dll/.dylib live in the OS-specific classifier jars, which are
        // NOT on the classpath; extract them into a dedicated natives directory.
        extractNatives(selectedNativeJars(Json.arr(doc, "libraries")), nativeDir);

        String mainClass = Json.str(doc, "mainClass");
        if (mainClass == null || mainClass.isEmpty()) mainClass = "net.minecraft.client.main.Main";
        // For modern releases where mainClass is an object {"client":"..."}
        if (mainClass.startsWith("{") && mainClass.endsWith("}")) {
            try {
                Object obj = Json.parse(mainClass);
                if (obj instanceof Map) {
                    Map<String, Object> objMap = (Map<String, Object>) obj;
                    Object v = objMap.get("client");
                    if (v != null) mainClass = v.toString();
                }
            } catch (Exception ignored) {}
        }
        int java = parseJavaVersion(null, doc);
        String assetIndex = assetIndexName(doc);
        String versionType = Json.str(doc, "type");
        if (versionType == null) versionType = "release";

        Map<String, Object> arguments = Json.map(doc, "arguments");
        List<Object> jvm = Json.arr(arguments, "jvm");
        List<Object> game = Json.arr(arguments, "game");
        Map<String, Object> logging = resolveLogging(doc, workDir);
        Path client = workDir.resolve(resolved + ".jar");
        return new MinecraftInstall(workDir, client, nativeDir, cp, mainClass, java,
                resolved, assetIndex, versionType, jvm, game, logging, doc);
    }

    /** Download the version's log4j2 config file (modern) or write a safe fallback. */
    private Map<String, Object> resolveLogging(Map<String, Object> doc, Path workDir) throws Exception {
        Map<String, Object> logging = Json.map(doc, "logging");
        Map<String, Object> client = Json.map(logging, "client");
        Map<String, Object> file = Json.map(client, "file");
        if (!file.isEmpty()) {
            String url = Json.str(file, "url");
            String sha1 = Json.str(file, "sha1");
            long size = ((Number) file.getOrDefault("size", 0L)).longValue();
            if (url != null) {
                String id = Json.str(file, "id");
                if (id == null || id.isEmpty()) id = "log4j2.xml";
                Path out = workDir.resolve(id);
                downloadOrFail(url, out, sha1, size);
                Map<String, Object> m = new HashMap<>();
                m.put("id", id);
                m.put("path", out.toString());
                m.put("argument", Json.str(client, "argument"));
                return m;
            }
        }
        // Fallback: write a minimal log4j2.xml so the client has something to read.
        String cfg = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Configuration status="ERROR">
              <Appenders>
                <Console name="Console" target="SYSTEM_OUT">
                  <PatternLayout pattern="%d{HH:mm:ss.SSS} [%thread]/[%level]: %msg%n"/>
                </Console>
              </Appenders>
              <Loggers>
                <Logger name="io.netty" level="INFO" additivity="false"><AppenderRef ref="Console"/></Logger>
                <Root level="INFO"><AppenderRef ref="Console"/></Root>
              </Loggers>
            </Configuration>""";
        Path out = workDir.resolve("log4j2.xml");
        Files.writeString(out, cfg, StandardCharsets.UTF_8);
        Map<String, Object> m = new HashMap<>();
        m.put("id", "log4j2.xml");
        m.put("path", out.toString());
        m.put("argument", "-Dlog4j.configurationFile=" + out);
        return m;
    }

    private int parseJavaVersion(String javaVersionField, Map<String, Object> doc) {
        if (javaVersionField == null) {
            Map<String, Object> jv = Json.map(doc, "javaVersion");
            if (!jv.isEmpty()) {
                Object mj = jv.get("majorVersion");
                if (mj != null) return ((Number) mj).intValue();
            }
            String major = Json.str(doc, "id");
            if (major != null) {
                String digits = major.replaceAll("^\\D*(\\d+).*", "$1");
                try {
                    int y = Integer.parseInt(digits);
                    return y >= 17 ? y : 17;
                } catch (Exception ignored) {}
            }
            return 21;
        }
        String s = javaVersionField;
        String num = s.replaceAll("[^0-9].*", "");
        if (!num.isEmpty()) return Integer.parseInt(num);
        return 21;
    }

    private String assetIndexName(Map<String, Object> doc) {
        Map<String, Object> di = Json.map(Json.map(doc, "downloads"), "assetIndex");
        if (di.isEmpty()) di = Json.map(doc, "assetIndex");
        String id = Json.str(di, "id");
        return id == null ? "0" : id;
    }

    /** Pick the library jar for the current OS/arch, handling classifiers. */
    private List<Path> selectedLibraries(List<Object> libs) {
        List<Path> out = new ArrayList<>();
        String os = osName();
        for (Object o : libs) {
            Map<String, Object> lib = (Map<String, Object>) o;
            if (!applies(lib)) continue;
            String path = resolveLibraryPath(lib, os);
            if (path == null) continue;
            Path p = librariesDir.resolve(path);
            if (Files.exists(p)) out.add(p);
        }
        return out;
    }

    /** Collect the OS-specific native classifier jars (contain .so/.dll/.dylib). */
    private List<Path> selectedNativeJars(List<Object> libs) {
        List<Path> out = new ArrayList<>();
        String os = osName();
        for (Object o : libs) {
            Map<String, Object> lib = (Map<String, Object>) o;
            if (!applies(lib)) continue;
            Path p = nativeJarPath(lib, os);
            if (p != null && Files.exists(p)) out.add(p);
        }
        return out;
    }

    private Path nativeJarPath(Map<String, Object> lib, String os) {
        Map<String, Object> natives = Json.map(lib, "natives");
        if (natives.isEmpty()) return null;
        Object nat = natives.get(os);
        if (nat == null) return null;
        String classifier;
        if (nat instanceof List) {
            classifier = ((List<?>) nat).stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        } else {
            classifier = nat.toString();
        }
        // 1.14.4/1.16: natives is a string like "natives-linux"
        // 1.18+: can be a list, but usually single string per os.
        String cls = classifier.split(",")[0].trim();
        Map<String, Object> clsMap = Json.map(Json.map(lib, "downloads"), "classifiers");
        Map<String, Object> art = Json.map(clsMap, cls);
        if (art.isEmpty()) return null;
        return librariesDir.resolve(Json.str(art, "path"));
    }

    private boolean applies(Map<String, Object> lib) {
        Object rules = lib.get("rules");
        if (rules == null) return true;
        List<Object> rl = (List<Object>) rules;
        int state = 0; // 0 = default allow if no disallow, 1 = allow
        // Mojang rules: each rule has action "allow"/"disallow" and optional os.
        for (Object r : rl) {
            Map<String, Object> rule = (Map<String, Object>) r;
            String action = Json.str(rule, "action");
            Map<String, Object> osrule = Json.map(rule, "os");
            String matches = osrule != null ? Json.str(osrule, "name") : null;
            boolean osMatches = matches == null || matches.equalsIgnoreCase(osName())
                    || matches.equals("any");
            boolean archOk = true;
            if (osrule != null && osrule.containsKey("version")) {
                String v = Json.str(osrule, "version");
                // simple regex-ish: we treat as literal contains check on arch.
                archOk = v != null && (v.equals("x86") ^ "amd64".equalsIgnoreCase(System.getProperty("os.arch"))) == false;
                // conservative: if it specifies version, require match
                String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
                if (v.contains(arch.substring(0, Math.min(3, arch.length())))) archOk = true;
            }
            boolean ruleMatched = osMatches && archOk;
            if (ruleMatched) {
                state = "allow".equals(action) ? 1 : 0;
            }
        }
        // default: if there were rules and last matching action was allow -> ok
        return state == 1 || rl.isEmpty();
    }

    private String resolveLibraryPath(Map<String, Object> lib, String os) {
        String name = Json.str(lib, "name");
        Map<String, Object> dl = Json.map(lib, "downloads");
        if (dl != null && dl.containsKey("classifiers")) {
            Map<String, Object> cls = Json.map(dl, "classifiers");
            // Prefer classifier matching the OS: linux, darwin, windows, or '' if none.
            String[] prefs = osPrefs(os);
            for (String pref : prefs) {
                if (cls.containsKey(pref)) {
                    Map<String, Object> art = Json.map(cls, pref);
                    return Json.str(art, "path");
                }
            }
            if (cls.containsKey("jarless")) return Json.str(Json.map(cls, "jarless"), "path");
            if (cls.containsKey("sources")) return Json.str(Json.map(cls, "sources"), "path");
        }
        if (dl != null && dl.containsKey("artifact")) {
            Map<String, Object> art = Json.map(dl, "artifact");
            return Json.str(art, "path");
        }
        // Legacy: synthesize conventional maven path.
        String[] parts = name.split(":");
        if (parts.length < 3) return null;
        String g = parts[0].replace('.', '/'), a = parts[1], v = parts[2];
        String base = g + "/" + a + "/" + v + "/" + a + "-" + v;
        Map<String, Object> natives = Json.map(lib, "natives");
        String classifier = null;
        if (natives != null && natives.containsKey(os)) {
            classifier = Json.str(natives, os);
        }
        if (classifier != null) base += "-" + classifier;
        return base + ".jar";
    }

    private static String osName() {
        String n = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return "windows";
        if (n.contains("mac")) return "osx";
        return "linux";
    }

    private static String[] osPrefs(String os) {
        return switch (os) {
            case "windows" -> new String[]{"windows", ""};
            case "osx" -> new String[]{"osx", "macos", ""};
            default -> new String[]{"linux", ""};
        };
    }

    private void extractNatives(List<Path> jars, Path nativeDir) throws IOException {
        Files.createDirectories(nativeDir);
        for (Path jar : jars) extractNatives(jar, nativeDir);
    }

    private void extractNatives(Path jar, Path nativeDir) throws IOException {
        try (var is = Files.newInputStream(jar);
             var zis = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.endsWith(".so") && !name.endsWith(".dll") && !name.endsWith(".dylib")) continue;
                if (e.isDirectory()) continue;
                Path out = nativeDir.resolve(new File(name).getName());
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                try { Files.setAttribute(out, "dos:readonly", true); } catch (Exception ignored) {}
            }
        }
    }
}
