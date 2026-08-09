package aurora.launcher;

import java.util.Locale;

/**
 * Tiny styled output helper (keeps main classes focused on logic).
 */
public final class Printer {
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD  = "\u001B[1m";
    private static final String ANSI_CYAN  = "\u001B[36m";
    private static final boolean SUPPORTS_ANSI = supportsAnsi();

    public void section(String title) {
        if (SUPPORTS_ANSI) {
            System.out.println();
            System.out.println(ANSI_BOLD + ANSI_CYAN + "=== " + title + " ===" + ANSI_RESET);
        } else {
            System.out.println();
            System.out.println("=== " + title + " ===");
        }
    }

    private static boolean supportsAnsi() {
        // Heuristic: assume ANSI is supported on most modern terminals.
        String term = System.getenv("TERM");
        if (term != null && term.equals("dumb")) return false;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("nix") || os.contains("nux") || os.contains("mac");
    }
}
