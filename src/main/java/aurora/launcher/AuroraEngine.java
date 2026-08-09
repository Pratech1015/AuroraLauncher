package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The heart of AuroraLauncher: wires together the network/download layer,
 * the version/manifest model, the game installer, and the launcher.
 */
public final class AuroraEngine {

    // --- Constants pointing at Mojang's public API (no auth on purpose) ---
    static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    static final String LIBRARIES_URL = "https://libraries.minecraft.net/";
    static final String ASSETS_URL = "https://assets.minecraft.net/";
    static final String OBJECTS_URL = "https://objects.minecraft.net/";

    // --- Paths ---
    private final Path root;        // ~/.auroralauncher  (or %APPDATA%\.auroralauncher)
    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Path logsDir;

    private final Http http;
    private final Manifest manifest;
    private final Installer installer;
    private final Config config;
    private final Auth auth;
    private final DiscordRpc discord;
    private final Printer printer;

    public AuroraEngine() throws IOException {
        this.root = defaultRoot();
        this.versionsDir = root.resolve("versions");
        this.librariesDir = root.resolve("libraries");
        this.assetsDir = root.resolve("assets");
        this.logsDir = root.resolve("logs");
        Files.createDirectories(versionsDir);
        Files.createDirectories(librariesDir);
        Files.createDirectories(assetsDir);
        Files.createDirectories(logsDir);

        this.http = new Http(this, logsDir);
        this.printer = new Printer();
        this.manifest = new Manifest(http);
        this.installer = new Installer(this, http, versionsDir, librariesDir, assetsDir);
        this.config = Config.load(root.resolve("config.json"));
        this.auth = new Auth(root, config);
        this.discord = new DiscordRpc();
        // Drop the bundled logo into the data dir so the app has its own icon asset.
        installBundledLogo();
    }

    // ---- entry point: parse args and run the CLI loop ----
    public int run(String[] args) throws Exception {
        // Safety net: clear Discord presence + close IPC on any exit path
        // (Ctrl-C, normal return, game exit). Prevents a lingering activity.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> discord.disconnect(), "aurora-shutdown"));
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        if (argv.isEmpty()) {
            return interactive();
        }
        // Known commands take precedence so they aren't mistaken for version ids.
        String first = argv.get(0).toLowerCase(Locale.ROOT);
        if (!isKnownCommand(first)) {
            // Bare non-dash token that isn't a command: treat as a version to launch.
            if (!first.startsWith("-")) {
                String version = argv.remove(0);
                return launchVersion(version, extractJavaArgs(argv));
            }
            // Otherwise treat everything as a command anyway (fall through).
        }

        // Command dispatcher.
        String cmd = argv.remove(0).toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "list"   -> { listVersions(); return 0; }
            case "versions" -> { listInstalled(); return 0; }
            case "install" -> {
                String v = argv.isEmpty() ? promptVersion() : argv.remove(0);
                return installVersion(v) ? 0 : 1;
            }
            case "launch" -> {
                String v = argv.isEmpty() ? promptVersion() : argv.remove(0);
                return launchVersion(v, extractJavaArgs(argv));
            }
            case "where" -> { System.out.println("Root: " + root); return 0; }
            case "help" -> { printHelp(); return 0; }
            case "update" -> { return updateLauncher() ? 0 : 1; }
            case "auth" -> { return runAuth(argv); }
            case "crack" -> { return runCrack(argv); }
            case "discord" -> { return runDiscord(argv); }
            default -> { System.err.println("Unknown command: " + cmd); printHelp(); return 1; }
        }
    }

    private static boolean isKnownCommand(String cmd) {
        return Set.of("list", "versions", "install", "launch", "where", "help", "update",
                "auth", "crack", "discord").contains(cmd);
    }

    private static String[] extractJavaArgs(List<String> argv) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.startsWith("-Xmx") || a.startsWith("-Xms") || a.startsWith("-XX")) {
                out.add(a);
            }
        }
        return out.toArray(String[]::new);
    }

    // ---- commands ----------------------------------------------------------

    private void listVersions() throws Exception {
        printer.section("Available Minecraft versions");
        List<Manifest.Version> versions = manifest.latest();
        if (versions.isEmpty()) {
            System.err.println("  (no versions found - check your network connection)");
            return;
        }
        // Group by major release-ish; show the latest 25.
        versions.stream().limit(25).forEach(v ->
            System.out.printf("  %-16s %-8s  %s%n", v.id, v.type, v.releaseTime));
        System.out.println("  ... use 'install <version>' to install one.");
    }

    private void listInstalled() {
        printer.section("Installed versions");
        try (var stream = Files.list(versionsDir)) {
            var it = stream.filter(Files::isDirectory).iterator();
            if (!it.hasNext()) {
                System.out.println("  (none installed yet)");
                return;
            }
            while (it.hasNext()) {
                System.out.println("  " + it.next().getFileName());
            }
        } catch (IOException e) {
            System.err.println("  error: " + e.getMessage());
        }
    }

    private boolean installVersion(String version) throws Exception {
        printer.section("Installing " + version);
        var result = installer.install(version);
        if (result.success) {
            System.out.println("  -> installed: " + result.resolvedVersion);
            return true;
        }
        System.err.println("  -> failed: " + result.error);
        return false;
    }

    private int launchVersion(String version, String[] javaArgs) throws Exception {
        printer.section("Launching Minecraft " + version);
        var mc = installer.prepare(version);
        if (mc == null) {
            System.err.println("  Could not resolve version. Run 'install " + version + "' first.");
            return 1;
        }

        Path javaBin = findBestJava(mc);
        if (javaBin == null) {
            System.err.println("  No suitable Java found in PATH. Install Java " + mc.requiredJava + "+ to play modern versions.");
            return 1;
        }

        List<String> cmd = buildCommand(mc, javaBin, javaArgs);
        System.out.println("  command: " + String.join(" ", cmd));
        // Push Discord rich presence while the game is running.
        discord.setActivity("Minecraft " + mc.versionId, "Singleplayer",
                DISCORD_ASSET_KEY, "AuroraLauncher");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(mc.workDir.toFile());
        pb.inheritIO();
        try {
            Process p = pb.start();
            int rc = p.waitFor();
            discord.clearActivity();
            return rc;
        } catch (IOException | InterruptedException e) {
            System.err.println("  launch error: " + e.getMessage());
            discord.clearActivity();
            return 1;
        }
    }

    private Path findBestJava(Installer.MinecraftInstall mc) {
        // Walk PATH looking for a java whose major version >= required.
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File javaExec = new File(dir, isWindows() ? "java.exe" : "java");
                if (!javaExec.isFile()) continue;
                int major = ProbeJava.major(javaExec.getAbsolutePath());
                if (major >= mc.requiredJava) return javaExec.toPath();
            }
        }
        // Fall back to the java running this launcher if it's new enough.
        String home = System.getProperty("java.home");
        File javaExec = new File(home, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        int have = ProbeJava.major(javaExec.getAbsolutePath());
        if (have >= mc.requiredJava) return javaExec.toPath();
        return null;
    }

    private List<String> buildCommand(Installer.MinecraftInstall mc, Path javaBin, String[] javaArgs) {
        List<String> c = new ArrayList<>();
        c.add(javaBin.toString());
        // User-supplied overrides (e.g. -Xmx512m) go first; template values that
        // repeat an option (later -Xmx) will override them.
        c.addAll(Arrays.asList(javaArgs));

        String sep = isWindows() ? ";" : ":";
        String cp = mc.classpath.stream().map(Path::toString)
                .collect(java.util.stream.Collectors.joining(sep));
        Map<String, String> vars = new HashMap<>();
        vars.put("classpath", cp);
        vars.put("natives_directory", mc.nativeDir.toString());
        vars.put("assets_directory", assetsDir.toString());
        vars.put("assets_root", assetsDir.toString());
        vars.put("assets_index_name", mc.assetIndex);
        vars.put("asset_index_name", mc.assetIndex);
        vars.put("game_directory", mc.workDir.toString());
        vars.put("game_dir", mc.workDir.toString());
        vars.put("version_name", mc.versionId);
        vars.put("version_type", mc.versionType);
        vars.put("launcher_name", "AuroraLauncher");
        vars.put("launcher_version", "1.0.0");
        Auth.Account acc = auth.active();
        vars.put("auth_username", acc.username);
        vars.put("auth_player_name", acc.username);
        vars.put("auth_uuid", acc.uuid);
        vars.put("auth_xuid", acc.uuid.replace("-", ""));
        vars.put("auth_access_token", acc.accessToken);
        vars.put("user_type", acc.userType);
        vars.put("user_properties", "{}");
        vars.put("resolution_width", String.valueOf(config.getWidth()));
        vars.put("resolution_height", String.valueOf(config.getHeight()));
        vars.put("library_directory", librariesDir.toString());
        vars.put("libraries_directory", librariesDir.toString());
        // Logging config path (the log4j2 file we downloaded).
        String logPath;
        if (mc.logging != null && mc.logging.get("path") != null) {
            logPath = mc.logging.get("path").toString();
        } else {
            logPath = mc.workDir.resolve("log4j2.xml").toString();
        }
        vars.put("path", logPath);

        // JVM arguments from the version document (includes -cp ${classpath}).
        if (mc.jvmArgs != null) {
            for (String a : expand(mc.jvmArgs, vars)) c.add(a);
        }
        // Version-specific log4j config argument (e.g. -Dlog4j.configurationFile=${path}).
        if (mc.logging != null && mc.logging.get("argument") != null) {
            c.add(subst(mc.logging.get("argument").toString(), vars));
        }
        // Fallback JVM args for documents without an arguments.jvm section (older).
        if (mc.jvmArgs == null || mc.jvmArgs.isEmpty()) {
            c.add("-Djava.library.path=" + mc.nativeDir);
            c.add("-Dlog4j2.formatMsgNoLookups=true");
            c.add("-Dfile.encoding=UTF-8");
            c.add("-cp"); c.add(cp);
        }
        // Main class (may be an object {"client":"..."} on some versions).
        String main = mc.mainClass;
        c.add(main);
        // Game arguments.
        if (mc.gameArgs != null) {
            for (String a : expand(mc.gameArgs, vars)) c.add(a);
        } else {
            // Legacy minecraftArguments fallback.
            String legacy = Json.str(mc.versionDoc, "minecraftArguments");
            if (legacy != null) for (String a : expandLegacy(legacy, vars)) c.add(a);
        }
        return c;
    }

    /** Expand a list of argument specs (strings or {rules,value}) with var substitution. */
    private static List<String> expand(List<Object> args, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof String s) {
                out.add(subst(s, vars));
            } else if (a instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) a;
                if (!rulesAllow(Json.arr(m, "rules"))) continue;
                Object val = m.get("value");
                if (val instanceof String s) out.add(subst(s, vars));
                else if (val instanceof List) for (Object v : (List<?>) val) if (v instanceof String s) out.add(subst(s, vars));
            }
        }
        return out;
    }

    private static boolean rulesAllow(List<Object> rules) {
        if (rules == null || rules.isEmpty()) return true;
        // Mojang feature flags the launcher tracks (used by argument rules).
        Map<String, Boolean> features = new HashMap<>();
        features.put("is_demo_user", false);
        features.put("has_custom_resolution", true); // we honour the configured window size
        boolean allowed = false;
        for (Object r : rules) {
            if (!(r instanceof Map)) continue;
            Map<String, Object> rule = (Map<String, Object>) r;
            boolean applies = true;
            Map<String, Object> os = Json.map(rule, "os");
            if (os != null && !os.isEmpty()) {
                String name = Json.str(os, "name");
                if (name != null) applies = nameMatches(name);
                String version = Json.str(os, "version");
                if (applies && version != null) {
                    String actual = System.getProperty("os.version");
                    if (actual == null) applies = false;
                    else {
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile(version);
                        applies = p.matcher(actual).find();
                    }
                }
                String arch = Json.str(os, "arch");
                if (applies && arch != null) applies = archMatches(arch);
            }
            // Feature rules (e.g. is_demo_user / has_custom_resolution).
            Map<String, Object> feats = Json.map(rule, "features");
            if (applies && !feats.isEmpty()) {
                for (Object k : feats.keySet()) {
                    boolean want = Boolean.TRUE.equals(feats.get(k));
                    Boolean have = features.get(k.toString());
                    if (want) { if (!Boolean.TRUE.equals(have)) applies = false; }
                    else { if (have != null && have) applies = false; }
                }
            }
            if (applies) allowed = !"disallow".equals(Json.str(rule, "action"));
        }
        return allowed;
    }

    // The art-asset key registered in the Discord developer portal for the
    // launcher's icon. Upload src/main/resources/aurora-logo.png there.
    static final String DISCORD_ASSET_KEY = "aurora";

    private static boolean nameMatches(String name) {
        String cur = osName();
        if (name.equals(cur)) return true;
        // Mojang uses "osx"; we expose "osx". Tolerate "macos" aliases just in case.
        return (name.equals("macos") || name.equals("darwin")) && cur.equals("osx");
    }

    private static boolean archMatches(String arch) {
        if (arch == null || arch.isEmpty()) return true;
        String actual = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("x86")) return actual.contains("i386") || actual.contains("i686") || actual.equals("x86");
        if (arch.equals("x64")) return actual.contains("amd64") || actual.contains("x86_64");
        return arch.equals(actual);
    }

    private static String subst(String s, Map<String, String> vars) {
        if (s.indexOf('{') < 0 || s.indexOf('}') < 0) return s;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int b = s.indexOf("${", i);
            if (b < 0) { sb.append(s, i, s.length()); break; }
            if (b > i) sb.append(s, i, b);
            int e = s.indexOf('}', b + 2);
            if (e < 0) { sb.append(s, b, s.length()); break; }
            String key = s.substring(b + 2, e);
            String v = vars.get(key);
            sb.append(v == null ? "${" + key + "}" : v);
            i = e + 1;
        }
        return sb.toString();
    }

    private static List<String> expandLegacy(String legacy, Map<String, String> vars) {
        // Very naive whitespace split; sufficient for the legacy "%TOKEN% / ${token}" form.
        List<String> out = new ArrayList<>();
        for (String tok : legacy.split("(?=\\s)|(?=\\G\\s)", 0)) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(subst(t.replace("%", "${").replaceFirst("}$", "}"), vars));
        }
        if (out.isEmpty()) Collections.addAll(out, legacy.split("\\s+"));
        return out;
    }

    private boolean updateLauncher() {
        printer.section("Updater (no-op placeholder)");
        System.out.println("  AuroraLauncher is a single-file build; re-download for updates.");
        return true;
    }

    // ---- interactive menu ---------------------------------------------------

    private int interactive() throws Exception {
        Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8);
        // Advertise presence for the whole interactive session; the shutdown
        // hook (registered in run()) clears it when you quit (option 0 / Ctrl-C).
        setDefaultPresence("idle - interactive");
        while (true) {
            System.out.println("================= AuroraLauncher =================");
            System.out.println("  1) List downloadable versions");
            System.out.println("  2) List installed versions");
            System.out.println("  3) Install a version");
            System.out.println("  4) Launch a version");
            System.out.println("  5) Show install location");
            System.out.println("  6) Manage accounts (auth/cracked)");
            System.out.println("  7) Crack a client jar (strip signatures)");
            System.out.println("  8) Discord rich presence");
            System.out.println("  0) Quit");
            System.out.print("> ");
            String line = sc.hasNextLine() ? sc.nextLine() : "";
            char sel = line.isEmpty() ? ' ' : line.trim().charAt(0);
            try {
                switch (sel) {
                    case '1' -> listVersions();
                    case '2' -> listInstalled();
                    case '3' -> installVersion(promptVersion());
                    case '4' -> launchVersion(promptVersion(), new String[0]);
                    case '5' -> System.out.println("Root: " + root);
                    case '6' -> runAuth(new ArrayList<>());
                    case '7' -> runCrack(new ArrayList<>());
                    case '8' -> runDiscord(new ArrayList<>());
                    case '0' -> { System.out.println("Bye."); return 0; }
                    default -> System.out.println("  pick 0-8");
                }
            } catch (Exception e) {
                System.err.println("  error: " + e.getMessage());
            }
        }
    }

    private String promptVersion() {
        System.out.print("version id (e.g. 1.21.1): ");
        var s = new java.util.Scanner(System.in, StandardCharsets.UTF_8);
        return s.hasNextLine() ? s.nextLine().trim() : "";
    }

    // ---- helpers ------------------------------------------------------------

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static String osName() {
        String n = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (n.contains("win")) return "windows";
        if (n.contains("mac")) return "osx";
        return "linux";
    }

    static Path defaultRoot() {
        if (isWindows()) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) return Path.of(appdata, ".auroralauncher");
        }
        String home = System.getProperty("user.home");
        return Path.of(home, ".auroralauncher");
    }

    /** Copy the bundled logo into the data dir (idempotent) and return its path. */
    private Path installBundledLogo() {
        Path dest = root.resolve(config.getAppLogo());
        if (Files.exists(dest)) return dest;
        try (InputStream in = getClass().getResourceAsStream("/" + config.getAppLogo())) {
            if (in != null) {
                Files.copy(in, dest);
                System.out.println("  installed logo: " + dest);
            }
        } catch (IOException e) {
            System.err.println("  couldn't install logo: " + e.getMessage());
        }
        return dest;
    }

    /** On-disk path of the app logo (for tooling / future GUI icon). */
    Path logoPath() {
        return root.resolve(config.getAppLogo());
    }

    private void printHelp() {
        System.out.println("AuroraLauncher - usage:");
        System.out.println("  aurora                        interactive menu");
        System.out.println("  aurora list                   list remote versions");
        System.out.println("  aurora versions               list installed versions");
        System.out.println("  aurora install <ver>          download & verify a version");
        System.out.println("  aurora launch <ver> [opts]    launch it");
        System.out.println("  aurora <ver>                  launch a version directly");
        System.out.println("  aurora auth                   show accounts");
        System.out.println("  aurora auth login <name>      set an offline profile");
        System.out.println("  aurora auth crack <name>      set a cracked (non-premium) profile");
        System.out.println("  aurora crack <ver>            strip signatures from a client jar");
        System.out.println("  aurora discord [details]      set Discord rich presence");
        System.out.println("  aurora update                 check for launcher updates");
    }

    // ---- auth ----
    private int runAuth(List<String> argv) {
        if (argv.isEmpty() || "list".equalsIgnoreCase(argv.get(0))) {
            System.out.println("Accounts:");
            for (Auth.Account a : auth.all()) {
                System.out.println("  " + a.username + "  (" + a.mode + ", uuid=" + a.uuid + ")");
            }
            Auth.Account a = auth.active();
            System.out.println("  active -> " + a.username);
            return 0;
        }
        switch (argv.remove(0).toLowerCase(Locale.ROOT)) {
            case "login" -> {
                if (argv.isEmpty()) { System.err.println("usage: auth login <name>"); return 1; }
                String name = argv.remove(0);
                try { System.out.println("  offline: " + auth.login(name).username); }
                catch (Exception e) { System.err.println("  " + e.getMessage()); return 1; }
                return 0;
            }
            case "crack" -> {
                if (argv.isEmpty()) { System.err.println("usage: auth crack <name>"); return 1; }
                String name = argv.remove(0);
                try { System.out.println("  cracked: " + auth.crack(name).username); }
                catch (Exception e) { System.err.println("  " + e.getMessage()); return 1; }
                return 0;
            }
            case "rm" -> {
                if (argv.isEmpty()) { System.err.println("usage: auth rm <name>"); return 1; }
                auth.remove(argv.remove(0));
                return 0;
            }
            default -> { System.err.println("auth: unknown action"); return 1; }
        }
    }

    // ---- crack ----
    private int runCrack(List<String> argv) {
        String ver = argv.isEmpty() ? promptVersion() : argv.remove(0);
        Path client = versionsDir.resolve(ver).resolve(ver + ".jar");
        if (!Files.exists(client)) {
            System.err.println("  install " + ver + " first (client jar not found)");
            return 1;
        }
        try {
            printer.section("Cracking " + client.getFileName());
            boolean wasSigned = CrackedClient.isSigned(client);
            System.out.println("  signed: " + wasSigned);
            if (!wasSigned) { System.out.println("  already unsigned; nothing to do"); return 0; }
            int dropped = CrackedClient.crack(client);
            System.out.println("  dropped " + dropped + " signature entr(y/ies); backup at " + client + ".bak");
            return 0;
        } catch (Exception e) {
            System.err.println("  failed: " + e);
            return 1;
        }
    }

    // ---- discord -----
    // -- Discord presence helpers --

    /** Connect (if needed) and publish a default AuroraLauncher presence. */
    private boolean setDefaultPresence(String details, String state) {
        if (!discord.isConnected() && !discord.connect()) return false;
        return discord.setActivity(details, state, DISCORD_ASSET_KEY, "AuroraLauncher");
    }

    private boolean setDefaultPresence(String state) {
        return setDefaultPresence("AuroraLauncher", state);
    }

    private int runDiscord(List<String> argv) {
        printer.section("Discord Rich Presence");
        if (!discord.connect()) {
            System.out.println("  Discord is not running (or not found). Rich Presence is unavailable.");
            System.out.println("  You can still launch; the game starts without it.");
            return 0;
        }
        System.out.println("  connected to Discord IPC.");
        String details = argv.isEmpty() ? "AuroraLauncher" : String.join(" ", argv);
        boolean ok = setDefaultPresence(details, "idle");
        if (ok) {
            // Keep the launcher alive so the presence stays visible while the
            // launcher is running; it clears when you exit (Enter / Ctrl-C).
            if (System.console() != null) {
                System.out.println("  presence active; press Enter (or Ctrl-C) to exit and clear.");
                try (Scanner sc = new Scanner(System.in, StandardCharsets.UTF_8)) { sc.hasNextLine(); }
            } else {
                System.out.println("  presence active for the launcher's lifetime.");
            }
        }
        return 0;
    }

    // -- exposed to subsystems --
    public Path versionsDir() { return versionsDir; }
    public Path librariesDir() { return librariesDir; }
    public Path assetsDir() { return assetsDir; }
}
