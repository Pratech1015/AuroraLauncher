package aurora.launcher;

import java.io.*;

/**
 * Probes a {@code java} executable to determine its major version, for the
 * launcher's "best Java on PATH" selection. No external deps; parses the
 * output of {@code java -version} which is printed to stderr.
 */
public final class ProbeJava {

    private ProbeJava() {}

    /**
     * Returns the major version number (e.g. 21, 17, 8) of the given java
     * binary, or {@code 0} if it cannot be determined.
     */
    public static int major(String javaBinary) {
        try {
            Process p = new ProcessBuilder(javaBinary, "-version").redirectErrorStream(false).start();
            String err;
            try (var r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                err = r.readLine(); // first line is usually the version line
            }
            p.waitFor();
            return parseMajor(err);
        } catch (Exception e) {
            return 0;
        }
    }

    static int parseMajor(String line) {
        if (line == null) return 0;
        // Formats:
        //   openjdk version "1.8.0_292"  -> 8
        //   openjdk version "25.0.4" ... -> 25
        //   java version "1.8.0_292"
        int q1 = line.indexOf('"');
        if (q1 < 0) return 0;
        int q2 = line.indexOf('"', q1 + 1);
        if (q2 < 0) return 0;
        String v = line.substring(q1 + 1, q2);
        String[] parts = v.split("\\.");
        if (parts.length >= 2 && parts[0].equals("1")) {
            try { return Integer.parseInt(parts[1]); } catch (Exception ignored) {}
        }
        try {
            // take first numeric token, trim trailing non-digits.
            String major = parts[0].replaceAll("[^0-9].*$", "");
            return Integer.parseInt(major);
        } catch (Exception e) {
            return 0;
        }
    }
}
