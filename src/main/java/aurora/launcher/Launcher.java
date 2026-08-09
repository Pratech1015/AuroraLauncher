package aurora.launcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * AuroraLauncher - a full-featured, dependency-free Minecraft launcher.
 */
public final class Launcher {

    public static void main(String[] args) throws Exception {
        // Minimal bootstrap: parse args, then hand off to the engine.
        // We keep main tiny and let {@link AuroraEngine} run the real loop.
        AuroraEngine engine = new AuroraEngine();
        int code = engine.run(args);
        System.exit(code);
    }
}
