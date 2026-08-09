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
import java.util.List;

/**
 * Full-screen terminal UI for AuroraLauncher, inspired by opencode's TUI.
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
    private int selected = 0;
    private String filter = "";
    private boolean showInstalled = true;
    private boolean discordOn = true;
    private String status = "";

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
                    case 'i' -> { handleInstall(); continue; }
                    case 'l' -> { handleLaunch(); continue; }
                    case 'r' -> { refreshEntries(); continue; }
                    case 'a' -> { engine.runAuth(new ArrayList<>()); continue; }
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
                if (t == KeyType.ArrowUp) { move(-1); continue; }
                if (t == KeyType.ArrowDown) { move(1); continue; }
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

    private void move(int delta) {
        List<Entry> v = visible();
        if (v.isEmpty()) return;
        selected = Math.floorMod(selected + delta, v.size());
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
        try {
            int n = 0;
            for (var v : engine.remoteVersions()) {
                if (entries.stream().anyMatch(e -> e.id.equals(v.id))) continue;
                entries.add(new Entry(v.id, v.type, v.releaseTime, false, true));
                if (++n >= 12) break;
            }
        } catch (Exception e) {
            status = "network error: " + e.getMessage();
        }
        if (selected >= entries.size()) selected = entries.isEmpty() ? 0 : entries.size() - 1;
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
        g.putString(0, 0, " AuroraLauncher TUI  (opencode-style)");
        g.drawLine(0, 1, cols - 1, 1, '-');
        g.setForegroundColor(TextColor.ANSI.YELLOW);
        g.putString(0, 2, " Versions  [installed=" + showInstalled + "]  filter=/" + filter + "  [/]=filter [x]=toggle [r]=refresh [a]=accounts [d]=discord [q]=quit");

        int listTop = 4;
        int listBottom = rows - 4;
        List<Entry> v = visible();
        for (int i = 0; i < listBottom - listTop && i < v.size(); i++) {
            Entry e = v.get(i);
            boolean sel = (i == Math.floorMod(selected, Math.max(1, v.size())));
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
}
