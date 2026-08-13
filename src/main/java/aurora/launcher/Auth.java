package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Account management for AuroraLauncher.
 *
 * Two kinds of accounts are supported:
 * <ul>
 *   <li>{@link Mode#CRACKED}   - local, non-premium profile (username -> deterministic
 *       offline UUID). Runs without a Microsoft account ({@code accessToken=0, userType=legacy}).
 *   <li>{@link Mode#PREMIUM}   - Microsoft/Xbox Live account obtained via device-code
 *       OAuth. Carries a real Minecraft bearer token, {@code userType=xbox}.
 * </ul>
 *
 * Accounts are persisted to <root>/accounts.json. The active account's username
 * is mirrored back into config.json so it survives across runs.
 */
public final class Auth {

    /** Valid Minecraft username: 3-16 chars, alphanumeric + underscore. */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public enum Mode { CRACKED, PREMIUM }

    /** A saved account. */
    public static final class Account {
        public final Mode mode;
        public final String username;
        public final String uuid;
        public final String accessToken;
        public final String userType;   // "legacy" (cracked) or "xbox" (premium)

        public Account(Mode mode, String username, String uuid, String accessToken, String userType) {
            this.mode = mode;
            this.username = username;
            this.uuid = uuid;
            this.accessToken = accessToken;
            this.userType = userType;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mode", mode.name());
            m.put("username", username);
            m.put("uuid", uuid);
            m.put("accessToken", accessToken);
            m.put("userType", userType);
            return m;
        }

        static Account fromMap(Map<String, Object> m) {
            if (m == null) return null;
            try {
                Mode mode = Mode.valueOf(Json.str(m, "mode"));
                String u = Json.str(m, "username");
                String id = Json.str(m, "uuid");
                if (u == null || id == null) return null;
                String tok = Json.str(m, "accessToken");
                String ut = Json.str(m, "userType");
                return new Account(mode, u, id, tok == null ? "0" : tok,
                        ut == null ? "legacy" : ut);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private final Path file;
    private final Config config;
    private final List<Account> accounts = new ArrayList<>();
    private int active = -1;

    Auth(Path root, Config config) {
        this.file = root.resolve("accounts.json");
        this.config = config;
        load();
    }

    /** Return true if {@code username} is a syntactically valid Minecraft name. */
    public static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    /** Create/select a cracked (local, non-premium) account. Mirrors name into config.json. */
    public Account crack(String username) {
        if (!validUsername(username)) throw new IllegalArgumentException("invalid username: " + username);
        Account a = lookup(username, Mode.CRACKED);
        setActive(a);
        save();
        return a;
    }

    /** Create/overwrite a premium (Microsoft) account record and make it active. */
    public Account microsoft(String username, String uuid, String accessToken) {
        if (username == null || uuid == null || accessToken == null)
            throw new IllegalArgumentException("microsoft account fields required");
        Account a = lookup(username, Mode.PREMIUM);
        int idx = accounts.indexOf(a);
        if (idx >= 0) {
            a = new Account(Mode.PREMIUM, username, uuid, accessToken, "xbox");
            accounts.set(idx, a);
        } else {
            a = new Account(Mode.PREMIUM, username, uuid, accessToken, "xbox");
            accounts.add(a);
        }
        setActive(a);
        save();
        return a;
    }

    private Account lookup(String username, Mode mode) {
        for (Account a : accounts) {
            if (a.username.equals(username) && a.mode == mode) return a;
        }
        String uuid = Config.offlineUuid(username);
        Account a = new Account(mode, username, uuid, "0", "legacy");
        accounts.add(a);
        return a;
    }

    public void setActive(Account a) {
        int idx = accounts.indexOf(a);
        if (idx >= 0) {
            active = idx;
            config.setUsername(a.username, a.uuid);
            try { config.save(); } catch (Exception ignored) {}
        }
    }

    public Account active() {
        if (active < 0 || active >= accounts.size()) {
            // Default to a cracked "Player" account.
            Account a = crack("Player");
            return a;
        }
        return accounts.get(active);
    }

    public List<Account> all() {
        return new ArrayList<>(accounts);
    }

    public void remove(String username) {
        accounts.removeIf(a -> a.username.equals(username));
        if (active >= accounts.size()) active = accounts.isEmpty() ? -1 : 0;
        save();
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                Object o = Json.parse(file);
                if (o instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    List<Object> as = Json.arr(m, "accounts");
                    for (Object e : as) {
                        if (e instanceof Map) {
                            Account a = Account.fromMap((Map<String, Object>) e);
                            if (a != null) accounts.add(a);
                        }
                    }
                    active = ((Number) m.getOrDefault("active", 0)).intValue();
                }
            } catch (Exception ignored) {}
        }
        if (accounts.isEmpty()) active = -1;
    }

    private void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> as = new ArrayList<>();
        for (Account a : accounts) as.add(a.toMap());
        m.put("accounts", as);
        m.put("active", active);
        try {
            Files.writeString(file, JsonWriter.write(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("  couldn't save accounts: " + e.getMessage());
        }
    }
}
