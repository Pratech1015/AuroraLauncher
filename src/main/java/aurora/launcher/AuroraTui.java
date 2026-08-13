package aurora.launcher;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *Full-screen terminal UI for AuroraLauncher.
 *
 * Low-level Lanterna {@link Screen}/{@link TextGraphics} rendering: a left pane
 * lists versions (installed first, then the latest remote releases), a right
 * pane shows the selected version's details and quick actions, and a status bar
 * reports the active account and lifecycle. Runs until you quit; the launcher's
 * shutdown hook tears down Discord presence on exit.
 *
 * Keys:
 *   Up/Down         navigate
 *   / then text     filter versions
 *   Enter           launch (installed) or install (remote)
 *   I               install selected
 *   L               launch selected installed version
 *   R / F5          refresh
 *   A               manage accounts
 *   D               toggle Discord rich presence
 *   X               toggle installed-only / all
 *   Q / Esc         quit
 */
public final class AuroraTui {

    private final AuroraEngine engine;
    private final List<Entry> entries = new ArrayList<>();
    private List<Manifest.Version> remoteAll;      // full remote manifest (lazy-loaded once)
    private int remoteLoaded = 0;                  // how many remote entries have been appended to `entries`
    private int selected = 0;
    private int scroll = 0;                         // scroll offset into visible()
    private String filter = "";
    private boolean showInstalled = true;
    private boolean discordOn = true;
    private String status = "";
    private static final int REMOTE_PAGE = 16;       // remote versions loaded per scroll tick
    private static final int LOAD_MARGIN = 3;
    private int logScroll = 0;

    private record Entry(String id, String type, String releaseTime,
                         boolean installed, boolean remote) {}

    public AuroraTui(AuroraEngine engine) {
        this.engine = engine;
    }

    public void run() throws Exception {
        Terminal terminal = new DefaultTerminalFactory().createTerminal();
        try (Screen screen = new com.googlecode.lanterna.screen.TerminalScreen(terminal)) {
            screen.startScreen();
            refreshEntries();
            loop(screen);
        } finally {
            System.out.println("\u001B[?25h"); // restore cursor just in case
        }
    }

    private void loop(Screen screen) throws IOException, InterruptedException {
        while (true) {
            render(screen);
            KeyStroke k = screen.pollInput();
            if (k == null) { Thread.sleep(15); continue; }
            if (k.getKeyType() == KeyType.Character && k.getCharacter() != null) {
                char c = Character.toLowerCase(k.getCharacter());
                switch (c) {
                    case 'q' -> { return; }
                     case 'i' -> { handleInstallLog(screen); continue; }
                    case 'l' -> { handleLaunch(); continue; }
                    case 'r' -> { refreshEntries(); continue; }
                    case 'a' -> { accountsPopup(screen); continue; }
                    case 'd' -> { discordOn = !discordOn;
                        if (discordOn) {
                            status = engine.setDefaultPresence("AuroraLauncher", "idle - in TUI")
                                    ? "discord: on" : "discord: off (not running)";
                        } else {
                            engine.clearDiscordPresence();
                            status = "discord: off";
                        }
                        continue; }
                    case 'x' -> { showInstalled = !showInstalled; continue; }
                    case '/' -> { doFilter(screen); continue; }
                    default -> {}
                }
            } else {
                KeyType t = k.getKeyType();
                if (t == KeyType.Enter) { handleEnter(); continue; }
                if (t == KeyType.ArrowUp) { move(screen, -1); continue; }
                if (t == KeyType.ArrowDown) { move(screen, 1); continue; }
                if (t == KeyType.Escape || t == KeyType.Delete || t == KeyType.Backspace) {
                    if (t == KeyType.Escape) return;
                    if (!filter.isEmpty()) filter = filter.substring(0, filter.length() - 1);
                    continue;
                }
            }
        }
    }

    private void doFilter(Screen screen) throws IOException, InterruptedException {
        TextGraphics g = screen.newTextGraphics();
        TerminalSize ts = screen.getTerminalSize();
        while (true) {
            g.fillRectangle(TerminalPosition.TOP_LEFT_CORNER, ts, ' ');
            g.putString(0, 0, "Filter versions: " + filter + "\u2588");
            screen.refresh();
            KeyStroke k = screen.readInput();
            if (k == null) continue;
            if (k.getKeyType() == KeyType.Enter || k.getKeyType() == KeyType.Escape) return;
            if (k.getKeyType() == KeyType.Character && k.getCharacter() != null) {
                char c = k.getCharacter();
                if (c == '\b' || c == 127) {
                    if (!filter.isEmpty()) filter = filter.substring(0, filter.length() - 1);
                } else if (c >= 32 && c < 127) {
                    filter += c;
                }
            } else if (k.getKeyType() == KeyType.Backspace) {
                if (!filter.isEmpty()) filter = filter.substring(0, filter.length() - 1);
            }
        }
    }

    private Entry current() {
        List<Entry> v = visible();
        if (v.isEmpty()) return null;
        int idx = Math.floorMod(selected, v.size());
        return v.get(idx);
    }

    private void handleEnter() {
        Entry e = current();
        if (e == null) { status = "nothing selected"; return; }
        try {
            if (e.installed) {
                status = "launching " + e.id + "...";
                engine.launchVersion(e.id, new String[0]);
                status = e.id + " exited";
            } else if (e.remote) {
                status = "installing " + e.id + "...";
                engine.installVersion(e.id);
                status = e.id + " installed";
                refreshEntries();
            }
        } catch (Exception ex) {
            status = "error: " + ex.getMessage();
        }
    }

    private void handleInstall() {
        Entry e = current();
        if (e == null) { status = "select a version"; return; }
        try { status = "installing " + e.id; engine.installVersion(e.id); status = e.id + " installed"; refreshEntries(); }
        catch (Exception ex) { status = "error: " + ex.getMessage(); }
    }

    private void handleInstallLog(Screen screen) throws IOException, InterruptedException {
        Entry e = current();
        if (e == null) { status = "select a version"; return; }
        if (e.installed) { status = e.id + " already installed"; return; }
        if (!e.remote) { status = "select a remote version"; return; }
        status = "";
        logScroll = 0;
        installLogOverlay(screen, e.id);
    }

    private void installLogOverlay(Screen screen, String version) throws IOException, InterruptedException {
        TerminalSize ts = screen.getTerminalSize();
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        lines.add("installing " + version);
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                boolean ok = engine.installVersion(version, lines::add);
                result.set(ok ? "installed: " + version : "failed: " + version);
            } catch (Exception ex) {
                result.set("error: " + ex.getMessage());
            } finally {
                done.set(true);
            }
        });
        t.setDaemon(true);
        t.start();
        boolean everDone = false;
        while (true) {
            drawInstallLog(screen, version, lines, ts);
            if (done.get()) {
                everDone = true;
                lines.add(result.get());
                drawInstallLog(screen, version, lines, ts);
                // any key dismisses the log overlay
                KeyStroke k = screen.readInput();
                if (k != null) break;
            } else {
                KeyStroke k = screen.pollInput();
                if (k != null) {
                    KeyType kt = k.getKeyType();
                    if (kt == KeyType.Escape) break; // close overlay; install keeps running
                    if (kt == KeyType.ArrowDown) logScroll += 3;
                    else if (kt == KeyType.ArrowUp) logScroll = Math.max(0, logScroll - 3);
                    else if (kt == KeyType.PageDown) logScroll += 20;
                    else if (kt == KeyType.PageUp) logScroll = Math.max(0, logScroll - 20);
                }
                Thread.sleep(150);
            }
        }
        if (everDone) {
            refreshEntries();
            status = result.get();
        } else {
            status = "install running in background: " + version;
        }
    }

    private void drawInstallLog(Screen screen, String version, List<String> lines, TerminalSize ts) throws IOException {
        int rows = ts.getRows();
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (int y = 0; y < rows; y++) g.fillRectangle(new TerminalPosition(0, y), new TerminalSize(ts.getColumns(), 1), ' ');
        g.putString(0, 0, " Installing " + version + " ");
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        int start = Math.min(logScroll, Math.max(0, lines.size() - 1));
        int y = 2;
        synchronized (lines) {
            for (int i = start; i < lines.size() && y < rows - 2; i++) {
                String ln = lines.get(i);
                g.putString(0, y, " " + ln);
                y++;
            }
        }
        if (lines.size() > start + (rows - 4)) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(0, rows - 2, "   \u25BC scroll");
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        screen.refresh();
    }

    private void handleLaunch() {
        Entry e = current();
        if (e == null || !e.installed) { status = "select an installed version"; return; }
        try { status = "launching " + e.id; engine.launchVersion(e.id, new String[0]); status = e.id + " exited"; }
        catch (Exception ex) { status = "error: " + ex.getMessage(); }
    }

    private void refreshEntries() {
        entries.clear();
        for (String id : engine.installedVersions()) {
            entries.add(new Entry(id, "", "", true, false));
        }
        remoteAll = null;
        remoteLoaded = 0;
        loadRemotePage();
        scroll = 0;
        List<Entry> v = visible();
        if (selected >= v.size()) selected = v.isEmpty() ? 0 : v.size() - 1;
    }

    /** Append one page of remote versions to `entries` (de-dup'd, filtered by already-installed). */
    private void loadRemotePage() {
        if (remoteAll == null) {
            try { remoteAll = engine.remoteVersions(); }
            catch (Exception e) { status = "network error: " + e.getMessage(); remoteAll = List.of(); }
        }
        if (remoteAll.isEmpty()) return;
        int take = Math.min(REMOTE_PAGE, remoteAll.size() - remoteLoaded);
        for (int i = 0; i < take; i++) {
            Manifest.Version v = remoteAll.get(remoteLoaded + i);
            if (v == null) continue;
            if (entries.stream().anyMatch(e -> e.id.equals(v.id))) continue; // already installed
            entries.add(new Entry(v.id, v.type, v.releaseTime, false, true));
        }
        remoteLoaded += take;
    }

    private boolean canLoadMore() {
        return remoteAll != null && !remoteAll.isEmpty() && remoteLoaded < remoteAll.size();
    }

    private void move(Screen screen, int delta) {
        List<Entry> v = visible();
        if (v.isEmpty()) return;
        if (delta > 0 && filter.isEmpty() && v.size() - selected <= LOAD_MARGIN && canLoadMore()) {
            loadRemotePage();
            v = visible();
            if (v.isEmpty()) return;
        }
        selected = Math.floorMod(selected + delta, v.size());
        int rows = screen.getTerminalSize().getRows();
        int window = Math.max(1, rows - 8);
        if (selected < scroll) scroll = selected;
        else if (selected >= scroll + window) scroll = selected - window + 1;
    }

    private List<Entry> visible() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!showInstalled && e.installed) continue;
            if (!filter.isEmpty() && !e.id.toLowerCase().contains(filter.toLowerCase())) continue;
            out.add(e);
        }
        return out;
    }

    private void render(Screen screen) throws IOException {
        TerminalSize ts = screen.getTerminalSize();
        int cols = ts.getColumns();
        int rows = ts.getRows();
        TextGraphics g = screen.newTextGraphics();
        g.fillRectangle(TerminalPosition.TOP_LEFT_CORNER, ts, ' ');

        g.setForegroundColor(TextColor.ANSI.CYAN);
        g.putString(0, 0, " AuroraLauncher TUI");
        g.drawLine(0, 1, cols - 1, 1, '-');
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        g.putString(0, 2, " Versions  [installed=" + showInstalled + "]  filter=/" + filter + "  [/]=filter [x]=toggle [r]=refresh [a]=accounts [d]=discord [q]=quit");

        int listTop = 4;
        int listBottom = rows - 4;
        int window = Math.max(1, listBottom - listTop);
        List<Entry> v = visible();
        int start = Math.min(scroll, Math.max(0, v.size() - window));
        if (start < 0) start = 0;
        for (int i = 0; i < Math.min(window, v.size()); i++) {
            Entry e = v.get(start + i);
            boolean sel = (start + i == selected);
            StringBuilder sb = new StringBuilder();
            sb.append(e.installed ? "[i] " : "[ ] ");
            sb.append(e.id);
            if (!e.installed && e.type != null && !e.type.isEmpty()) sb.append(" (").append(e.type).append(")");
            if (sel) {
                g.setForegroundColor(TextColor.ANSI.BLACK);
                g.setBackgroundColor(TextColor.ANSI.CYAN);
            } else {
                g.setForegroundColor(e.installed ? TextColor.ANSI.GREEN : TextColor.ANSI.WHITE);
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            g.putString(2, listTop + i, sb.toString());
        }
        // scroll hint
        if (v.size() > start + window) {
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.putString(2, listBottom - 1, "\u25BC more \u2026 scroll down");
        }

        // Right pane: details
        int rc = listTop;
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.putString(cols / 2 + 1, rc, " Details");
        Entry e = current();
        if (e == null) {
            g.putString(cols / 2 + 1, rc + 2, "(no version selected)");
        } else {
            g.setForegroundColor(TextColor.ANSI.WHITE);
            g.putString(cols / 2 + 1, rc + 2, "id:        " + e.id);
            g.putString(cols / 2 + 1, rc + 3, "type:      " + (e.type == null || e.type.isEmpty() ? "-" : e.type));
            g.putString(cols / 2 + 1, rc + 4, "released:  " + (e.releaseTime == null || e.releaseTime.isEmpty() ? "-" : e.releaseTime));
            g.putString(cols / 2 + 1, rc + 5, "state:     " + (e.installed ? "installed" : e.remote ? "remote" : "-"));
            g.putString(cols / 2 + 1, rc + 6, "account:   " + engine.activeAccountName());
            g.putString(cols / 2 + 1, rc + 8, "Enter = launch/install  I = install  L = launch");
            g.putString(cols / 2 + 1, rc + 9, "A = accounts  D = discord  R = refresh  Q = quit");
        }

        // Status bar (bottom)
        g.setBackgroundColor(TextColor.ANSI.WHITE);
        g.setForegroundColor(TextColor.ANSI.BLACK);
        g.fillRectangle(new TerminalPosition(0, rows - 1), new TerminalSize(cols, 1), ' ');
        g.putString(0, rows - 1, " " + (status == null || status.isEmpty() ? "ready" : status));
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        screen.refresh();
    }

    // -- Accounts popup (modal, drawn inside the TUI) --

    private void accountsPopup(Screen screen) throws IOException, InterruptedException {
        int sel = 0;
        while (true) {
            drawAccountsOverlay(screen, sel);
            KeyStroke k = screen.readInput();
            if (k == null) continue;
            KeyType t = k.getKeyType();
            if (t == KeyType.Escape) { status = ""; break; }
            if (t == KeyType.Character && k.getCharacter() != null) {
                char c = Character.toLowerCase(k.getCharacter());
                switch (c) {
                    case 'q' -> { status = ""; return; }
                    case 'j' -> sel = step(sel, 1);
                    case 'k' -> sel = step(sel, -1);
                    case 'a' -> addCrackedAccount(screen);
                    case 'm' -> addMicrosoftAccount(screen, sel);
                    case 'r' -> { if (removeAccount(sel)) refreshEntries(); }
                    case 'x' -> { status = ""; return; }
                    default -> {}
                }
            } else if (t == KeyType.ArrowDown) { sel = step(sel, 1); }
            else if (t == KeyType.ArrowUp) { sel = step(sel, -1); }
            else if (t == KeyType.Enter) { activateAccount(sel); }
        }
    }

    private int step(int sel, int d) {
        var list = engine.accounts();
        if (list.isEmpty()) return 0;
        return Math.floorMod(sel + d, list.size());
    }

    private void addCrackedAccount(Screen screen) throws IOException, InterruptedException {
        String name = promptText(screen, "cracked username: ");
        if (name == null || name.trim().isEmpty()) { status = "add cancelled"; return; }
        try {
            engine.crackAccount(name.trim()); status = "added cracked: " + name;
            refreshEntries();
        } catch (Exception e) { status = "error: " + e.getMessage(); }
    }

    private void addMicrosoftAccount(Screen screen, int sel) throws IOException, InterruptedException {
        MicrosoftAuth ma = engine.microsoftAuth();
        status = "microsoft: contacting Microsoft...";
        drawAccountsOverlay(screen, sel);
        MicrosoftAuth.DeviceCode dc;
        try { dc = ma.start(); } catch (Exception e) { status = "microsoft start failed: " + e.getMessage(); return; }
        status = "microsoft: sign in at the URL above with code " + dc.userCode;
        showDeviceCode(screen, dc);
        String token = null;
        long next = dc.intervalMs;
        while (true) {
            // Give the user a chance to cancel while we wait for them to finish in-browser.
            if (waitCancelable(screen, next, "microsoft: waiting for sign-in... (q/Esc to cancel)")) {
                status = "microsoft sign-in cancelled";
                return;
            }
            showDeviceCode(screen, dc);
            MicrosoftAuth.PollResult pr;
            try { pr = ma.pollOnce(dc); } catch (Exception e) { status = "microsoft poll error: " + e.getMessage(); return; }
            if (pr.done()) { token = pr.token; break; }
            if (pr.error()) { status = "microsoft: " + pr.message; return; }
            next = pr.nextIntervalMs;
        }
        Auth.Account a;
        try { a = ma.exchange(token); } catch (Exception e) { status = "microsoft exchange failed: " + e.getMessage(); return; }
        try {
            engine.microsoftAccount(a.username, a.uuid, a.accessToken);
            refreshEntries();
            status = "signed in as " + a.username;
        } catch (Exception e) { status = "microsoft save failed: " + e.getMessage(); }
    }

    /** Sleep up to {@code ms} while watching for q/Esc to cancel. Returns true if cancelled. */
    private boolean waitCancelable(Screen screen, long ms, String waitStatus) throws IOException, InterruptedException {
        status = waitStatus;
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            KeyStroke k = screen.pollInput();
            if (k != null) {
                KeyType t = k.getKeyType();
                if (t == KeyType.Escape) return true;
                if (t == KeyType.Character && k.getCharacter() != null
                        && Character.toLowerCase(k.getCharacter()) == 'q') return true;
                // swallow other keys during the wait
            }
            Thread.sleep(80);
        }
        return false;
    }

    private void showDeviceCode(Screen screen, MicrosoftAuth.DeviceCode dc) throws IOException {
        TerminalSize ts = screen.getTerminalSize();
        TextGraphics g = screen.newTextGraphics();
        int h = 8;
        int x = 2, y = ts.getRows() - h - 1;
        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (int i = 0; i < h; i++) g.fillRectangle(new TerminalPosition(x, y + i), new TerminalSize(ts.getColumns() - x - 1, 1), ' ');
        g.putString(x, y,   " Microsoft sign-in required ");
        g.putString(x, y + 2, " Open: " + dc.verificationUri);
        g.putString(x, y + 3, " Enter code: " + dc.userCode);
        g.putString(x, y + 4, " Waiting for sign-in... (q / Esc to cancel)");
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        screen.refresh();
    }
    private boolean removeAccount(int sel) {
        var list = engine.accounts();
        if (list.isEmpty()) { status = "no accounts"; return false; }
        if (list.size() <= 1) { status = "keep at least one account"; return false; }
        var a = list.get(Math.floorMod(sel, list.size()));
        engine.removeAccount(a.username);
        status = "removed " + a.username;
        return true;
    }

    private void activateAccount(int sel) {
        var list = engine.accounts();
        if (list.isEmpty()) { status = "no accounts"; return; }
        var a = list.get(Math.floorMod(sel, list.size()));
        engine.setActiveAccount(a);
        status = "active: " + a.username;
    }

    private String promptText(Screen screen, String label) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        TerminalSize ts = screen.getTerminalSize();
        TextGraphics g = screen.newTextGraphics();
        while (true) {
            g.fillRectangle(new TerminalPosition(0, ts.getRows() - 2), new TerminalSize(ts.getColumns(), 2), ' ');
            g.putString(0, ts.getRows() - 2, label + sb + "\u2588");
            screen.refresh();
            KeyStroke k = screen.readInput();
            if (k == null) continue;
            if (k.getKeyType() == KeyType.Enter) {
                g.fillRectangle(new TerminalPosition(0, ts.getRows() - 2), new TerminalSize(ts.getColumns(), 2), ' ');
                screen.refresh();
                return sb.toString();
            }
            if (k.getKeyType() == KeyType.Escape) {
                g.fillRectangle(new TerminalPosition(0, ts.getRows() - 2), new TerminalSize(ts.getColumns(), 2), ' ');
                screen.refresh();
                return null;
            }
            if (k.getKeyType() == KeyType.Backspace || k.getKeyType() == KeyType.Delete) {
                if (sb.length() > 0) sb.setLength(sb.length() - 1);
            } else if (k.getKeyType() == KeyType.Character && k.getCharacter() != null) {
                char c = k.getCharacter();
                if (c >= 32 && c < 127) sb.append(c);
            }
        }
    }

    private void drawAccountsOverlay(Screen screen, int sel) throws IOException {
        TerminalSize ts = screen.getTerminalSize();
        int cols = ts.getColumns();
        int rows = ts.getRows();
        TextGraphics g = screen.newTextGraphics();
        int w = Math.max(50, cols / 2);
        int h = Math.min(16, rows - 4);
        int x = (cols - w) / 2;
        int y = (rows - h) / 2 - 1;
        for (int yy = 0; yy < rows; yy++) {
            g.fillRectangle(new TerminalPosition(0, yy), new TerminalSize(cols, 1), ' ');
        }
        g.setBackgroundColor(TextColor.ANSI.WHITE);
        g.setForegroundColor(TextColor.ANSI.BLACK);
        for (int i = 0; i < w; i++) { g.setCharacter(x + i, y, ' '); g.setCharacter(x + i, y + h - 1, ' '); }
        for (int i = 0; i < h; i++) { g.setCharacter(x, y + i, '|'); g.setCharacter(x + w - 1, y + i, '|'); }
        g.setCharacter(x, y, '+'); g.setCharacter(x + w - 1, y, '+');
        g.setCharacter(x, y + h - 1, '+'); g.setCharacter(x + w - 1, y + h - 1, '+');
        g.putString(x + 1, y, " Accounts ");
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.putString(x + 2, y + 2, "  >  username               mode      uuid  <active>");
        var list = engine.accounts();
        String active = engine.activeAccountName();
        int row = y + 3;
        int i = 0;
        for (var a : list) {
            if (row >= y + h - 2) break;
            boolean isSel = (i == sel);
            boolean isActive = a.username.equals(active);
            if (isSel) {
                g.setBackgroundColor(TextColor.ANSI.CYAN);
                g.setForegroundColor(TextColor.ANSI.BLACK);
            } else if (isActive) {
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                g.setForegroundColor(TextColor.ANSI.GREEN);
            } else {
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                g.setForegroundColor(TextColor.ANSI.WHITE);
            }
            String mark = isSel ? "> " : "  ";
            String mode = a.mode == Auth.Mode.CRACKED ? "cracked" : "premium";
            g.putString(x + 2, row, mark + pad(a.username, 20) + "  " + pad(mode, 7) + "  " + a.uuid
                    + (isActive ? "  <active>" : ""));
            row++; i++;
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.putString(x + 2, y + h - 1, "[a]dd cracked  [m]icrosoft  [r]emove  Enter=set active  [q]/[Esc]=close");
        if (status != null && !status.isEmpty()) {
            g.setBackgroundColor(TextColor.ANSI.BLACK);
            g.setForegroundColor(TextColor.ANSI.YELLOW);
            g.putString(x + 2, y + h, " " + status);
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        screen.refresh();
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        return s.length() >= w ? s.substring(0, w) : s + " ".repeat(w - s.length());
    }
}
