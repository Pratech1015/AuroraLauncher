package aurora.launcher;

import java.io.IOException;
import java.util.*;

/**
 * Microsoft (Premium) account login for AuroraLauncher, via the OAuth 2.0
 * device-code flow + the XBox Live -> XSTS -> Minecraft token chain.
 *
 * <p>No external dependencies: uses the launcher's own {@link Http} and {@link Json}.
 * The device-code flow is interactive but headless (prints a code + URL for the
 * user to open in any browser).
 *
 * <p>Uses the public Minecraft Launcher {@code client_id} tolerated by Microsoft
 * for open-source launchers. See {@code #CLIENT_ID}. If Microsoft ever revoke it,
 * register an Azure public-client app and supply its ID instead.
 *
 * @see Auth
 */
public final class MicrosoftAuth {

    /** Public Minecraft- Launcher app id shared by OSS launchers (Prism's default). */
    static final String CLIENT_ID = "17b47edd-c884-4997-926d-9e7f9a6b4647";

    /** Scope that yields a token usable for the XBL handoff. */
    private static final String[] SCOPES = {"xboxLive.signin", "offline_access"};
    private static final String AUTH_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";
    private static final String XBL_TOKEN   = "https://user.auth.xbox.com/xtokens?pid=0&cc=US&l=en-US&rights=2";
    private static final String XSTS_TOKEN  = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN    = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE  = "https://api.minecraftservices.com/minecraft/profile";

    private final Http http;
    private final Printer printer;

    MicrosoftAuth(Http http, Printer printer) {
        this.http = http;
        this.printer = printer;
    }

    /** Resolve the client id: env/property override, else the shared public id. */
    private static String clientId() {
        String v = System.getenv("AURORA_MS_CLIENT_ID");
        if (v == null || v.isBlank()) v = System.getProperty("aurora.ms.client_id");
        return (v == null || v.isBlank()) ? CLIENT_ID : v.trim();
    }

    /** One round of the device-code grant: start the flow, getting user_code + URL. */
    public DeviceCode start() throws Exception {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId());
        f.put("scope", String.join(" ", SCOPES));
        Map<String, Object> m = parse(http.postForm(AUTH_BASE + "devicecode", f));
        String err = Json.str(m, "error");
        if (err != null) throw new IOException("microsoft start failed: "
                + Json.str(m, "error_description", err)
                + (err.contains("700016") || "unauthorized_client".equals(err)
                    ? " — set AURORA_MS_CLIENT_ID to a registered Azure public-client app id" : ""));
        String userCode = require(m, "user_code");
        String uri = require(m, "verification_uri");
        String message = Json.str(m, "message");
        int expiresIn = toInt(m, "expires_in", 900);
        int interval = toInt(m, "interval", 1);
        return new DeviceCode(userCode, uri, message == null ? ("Open " + uri + " and enter code " + userCode) : message,
                System.currentTimeMillis() + expiresIn * 1000L, Math.max(interval, 1) * 1000L);
    }

    /** Poll the token endpoint until the user completes sign-in; returns the MSA access_token. */
    public String poll(DeviceCode dc) throws Exception {
        long deadline = dc.expiresAt;
        long intervalMs = dc.intervalMs;
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId());
        f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        f.put("device_code", dc.deviceCode);
        while (true) {
            if (System.currentTimeMillis() > deadline) throw new IllegalStateException("sign-in timed out");
            Map<String, Object> m = parse(http.postForm(AUTH_BASE + "token", f));
            String token = Json.str(m, "access_token");
            if (token != null) return token;
            String err = Json.str(m, "error");
            if (err != null) {
                switch (err) {
                    case "authorization_pending" -> { /* keep polling */ }
                    case "slow_down" -> intervalMs += 1000L;
                    case "expired_token" -> throw new IllegalStateException("sign-in expired; restart login");
                    case "authorization_declined" -> throw new IllegalStateException("sign-in declined");
                    default -> throw new IOException("oauth error: " + err + " " + Json.str(m, "error_description"));
                }
            } else {
                throw new IOException("unexpected oauth response: " + m);
            }
            Thread.sleep(intervalMs);
        }
    }

    /** Complete the chain (XBL -> XSTS -> Minecraft) for a Microsoft access_token. */
    public Auth.Account exchange(String msAccessToken) throws Exception {
        String xbl = xbl(msAccessToken);
        String userHash = xsts(xbl);
        Map<String, Object> mc = mcLogin(userHash, xbl);
        String accessToken = require(mc, "access_token");
        Map<String, Object> profile = mcProfile(accessToken);
        String name = require(profile, "name");
        String uuid = require(profile, "id");
        return new Auth.Account(Auth.Mode.PREMIUM, name, uuid, accessToken, "xbox");
    }

    /** End-to-end console flow: print instructions, poll, exchange. */
    public Auth.Account signIn(boolean printInstructions) throws Exception {
        DeviceCode dc = start();
        if (printInstructions) {
            System.out.println(dc.message);
            System.out.print("waiting for sign-in in browser... ");
        }
        String msToken = poll(dc);
        Auth.Account a = exchange(msToken);
        if (printInstructions) System.out.println("signed in as " + a.username + " (" + a.uuid + ")");
        return a;
    }

    // -- low-level exchanges --

    private String xbl(String msAccessToken) throws Exception {
        String body = "{\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"RPS\",\"RpsTicket\":\"d="
                + msAccessToken + "\"}";
        Map<String, Object> m = parse(http.post(XBL_TOKEN, body, "application/json"));
        String token = Json.str(m, "Token");
        if (token == null) throw new IOException("XBL token error: "
                + Json.str(m, "error_description", "unknown"));
        return token;
    }

    private String xsts(String xblToken) throws Exception {
        String body = "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + escape(xblToken)
                + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
        Map<String, Object> m = parse(http.post(XSTS_TOKEN, body, "application/json"));
        String token = Json.str(m, "Token");
        if (token == null) throw new IOException("XSTS token error: "
                + Json.str(m, "error_description", "unknown"));
        String userHash = uhs(m);
        if (userHash == null) throw new IOException("XSTS: no user hash (uhs) returned");
        return userHash + ";" + token; // uhs;XSTS token
    }

    private Map<String, Object> mcLogin(String userHashAndXsts, String xblToken) throws Exception {
        // userHashAndXsts = "uhs;xsts" -> identityToken = "XBL3.0 x=<uhs>;<xsts>"
        int semi = userHashAndXsts.indexOf(';');
        String userHash = userHashAndXsts.substring(0, semi);
        String xsts = userHashAndXsts.substring(semi + 1);
        String body = "{\"identityToken\":\"XBL3.0 x=" + userHash + ";" + escape(xsts) + "\"}";
        Map<String, Object> m = parse(http.post(MC_LOGIN, body, "application/json"));
        if (!m.containsKey("access_token")) throw new IOException("Minecraft login failed: "
                + Json.str(m, "error_description", Json.str(m, "errorMessage", "unknown")));
        return m;
    }

    private Map<String, Object> mcProfile(String accessToken) throws Exception {
        return parse(http.getWithHeader(MC_PROFILE, "Authorization", "Bearer " + accessToken));
    }

    // -- small helpers --

    /** One round of the device-code grant: start the flow, getting user_code + URL. */
    public static final class DeviceCode {
        public final String deviceCode, verificationUri, message;
        public final long expiresAt, intervalMs;
        DeviceCode(String deviceCode, String verificationUri, String message, long expiresAt, long intervalMs) {
            this.deviceCode = deviceCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.expiresAt = expiresAt;
            this.intervalMs = intervalMs;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) throws IOException {
        if (body == null || body.isEmpty()) throw new IOException("empty response");
        Object o = Json.parse(body);
        if (!(o instanceof Map)) throw new IOException("expected JSON object: " + body);
        return (Map<String, Object>) o;
    }

    private static String require(Map<String, Object> m, String key) {
        String v = Json.str(m, key);
        if (v == null) throw new IllegalStateException("missing '" + key + "' in: " + m);
        return v;
    }

    private static int toInt(Map<String, Object> m, String key, int def) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    @SuppressWarnings("unchecked")
    private static String uhs(Map<String, Object> m) {
        Object dc = m.get("DisplayClaims");
        if (!(dc instanceof Map)) return null;
        List<Object> xui = Json.arr((Map<String, Object>) dc, "xui");
        if (xui == null || xui.isEmpty()) return null;
        Object first = xui.get(0);
        if (!(first instanceof Map)) return null;
        return Json.str((Map<String, Object>) first, "uhs");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
