package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Persists the user's profile: display name, uuid, window size, etc.
 * This is a tiny JSON file (~/.auroralauncher/config.json). No real auth:
 * the launcher uses an offline "legacy" profile so it can run without a
 * Microsoft account. Replace the UUID logic if you wire up real auth.
 */
public final class Config {

    private final Path file;
    private String username;
    private String uuid;
    private String userType; // "legacy" (offline/crack) or "mojang" (premium)
    private String appLogo;  // filename of the launcher logo stored in the data dir
    private int width;
    private int height;

    private Config(Path file) {
        this.file = file;
        this.username = "Player";
        this.uuid = offlineUuid(username);
        this.userType = "legacy";
        this.appLogo = "aurora-logo.png";
        this.width = 854;
        this.height = 480;
    }

    static Config load(Path file) {
        Config c = new Config(file);
        if (Files.exists(file)) {
            try {
                Object o = Json.parse(file);
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                     if (Json.str(m, "username") != null) c.username = Json.str(m, "username");
                    if (Json.str(m, "uuid") != null) c.uuid = Json.str(m, "uuid");
                    Object w = m.get("width");
                    Object h = m.get("height");
                    if (w != null) c.width = ((Number) w).intValue();
                    if (h != null) c.height = ((Number) h).intValue();
                     Object ut = m.get("user_type");
                    if (ut instanceof String) c.userType = (String) ut;
                    Object logo = m.get("app_logo");
                    if (logo instanceof String) c.appLogo = (String) logo;
                }
            } catch (Exception ignored) {}
        }
        c.save(); // refresh file
        return c;
    }

    public void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", username);
        m.put("uuid", uuid);
        m.put("user_type", userType);
        m.put("app_logo", appLogo);
        m.put("width", width);
        m.put("height", height);
        try {
            Files.writeString(file, JsonWriter.write(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("  couldn't save config: " + e.getMessage());
        }
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getXuid() { return uuid.replace("-", ""); }
    public String getUserType() { return userType; }
    public String getAppLogo() { return appLogo; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setUsername(String username, String uuid) {
        this.username = username;
        this.uuid = uuid == null ? offlineUuid(username) : uuid;
    }

    public void setUserType(String userType) { this.userType = userType; }

    /**
     * Computes the deterministic offline-mode UUID Minecraft uses for a
     * given name (same algorithm as {@code com.mojang.util.UUID} offline).
     */
    public static String offlineUuid(String name) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8);
            md.update(bytes);
            byte[] digest = md.digest();
            // Set version 3 (name-based, MD5) and variant.
            digest[6] &= 0x0f;
            digest[6] |= 0x30;
            digest[8] &= 0x3f;
            digest[8] |= 0x80;
            java.util.UUID u = java.util.UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            return u.toString();
            // Use the canonical UUID from nameUUIDFromBytes above.
        } catch (Exception e) {
            return "00000000-0000-0000-0000-000000000000";
        }
    }
}
