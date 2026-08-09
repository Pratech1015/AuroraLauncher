package aurora.launcher;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Cracked-mode helper for the Minecraft client jar.
 *
 * Mojang ships the client jar signed (with {@code META-INF/*.SF}, {@code *.DSA},
 * {@code *.RSA}); the JVM refuses to load classes from a jar whose signatures
 * no longer match if those metadata entries linger. Cracked launchers remove the
 * signature metadata so the jar can be freely patched/modded.
 *
 * This class:
 * <ul>
 *   <li>{@link #isSigned(Path)} - checks if a jar carries signatures.</li>
 *   <li>{@link #crack(Path)}      - rewrites the jar without signature entries.</li>
 * </ul>
 *
 * Note: stripping signatures does <b>not</b> bypass Mojang's online-mode auth;
 * this launcher pairs it with an offline/crack profile ({@code accessToken=0,
 * userType=legacy}) so no account is needed. We do not distribute modified
 * clients, we only expose the tooling so the user can patch jars they already
 * own and launch them offline.
 */
public final class CrackedClient {

    private static final String[] SIGNATURE_PREFIXES = {
            "META-INF/MANIFEST.MF",   // kept (MANIFEST stays)
    };

    private CrackedClient() {}

    /** True if the jar contains any signature-related entries. */
    public static boolean isSigned(Path jar) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (isSignatureEntry(e.getName())) return true;
            }
        }
        return false;
    }

    private static boolean isSignatureEntry(String name) {
        if (name == null) return false;
        String u = name.toUpperCase(Locale.ROOT);
        // Signature files: *.SF, *.DSA, *.RSA, plus the per-cert dirs.
        return u.endsWith(".SF") || u.endsWith(".DSA") || u.endsWith(".RSA")
                || u.endsWith(".EC") || u.endsWith(".EL");
    }

    /**
     * Rewrite {@code jar} in place without signature entries. The original is
     * moved to {@code jar.bak} first; the cracked copy replaces {@code jar}.
     * Returns the number of entries dropped.
     */
    public static int crack(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new FileNotFoundException(jar.toString());
        }
        Path backup = Files.move(jar, Paths.get(jar.toString() + ".bak"));
        int dropped = 0;
        try (var in = new ZipInputStream(Files.newInputStream(backup));
             var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            // Copy the manifest first (required to be the first entry).
            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            boolean hasManifest = false;
            ZipEntry e;
            // Two passes aren't needed; build manifest as we encounter it.
            List<EntryBuf> kept = new ArrayList<>();
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("META-INF/MANIFEST.MF")) {
                    hasManifest = true;
                    manifest.read(in);
                    kept.add(new EntryBuf(e, readAll(in)));
                    continue;
                }
                if (isSignatureEntry(e.getName())) {
                    dropped++;
                    in.skip(e.getSize() < 0 ? Long.MAX_VALUE : e.getSize());
                    continue;
                }
                kept.add(new EntryBuf(e, readAll(in)));
            }
            // Write manifest (cleaned of signature-related attributes).
            cleanManifest(manifest, manifest.getMainAttributes());
            ZipEntry me = new ZipEntry("META-INF/MANIFEST.MF");
            out.putNextEntry(me);
            manifest.write(out);
            out.closeEntry();
            // Write remaining kept entries.
            for (EntryBuf b : kept) {
                if (b.entry.getName().equals("META-INF/MANIFEST.MF")) continue;
                ZipEntry ne = new ZipEntry(b.entry.getName());
                ne.setCompressedSize(b.data.length);
                out.putNextEntry(ne);
                out.write(b.data);
                out.closeEntry();
            }
        }
        // If something went wrong, restore the backup.
        if (!Files.isRegularFile(jar) || Files.size(jar) == 0) {
            Files.move(backup, jar, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("cracking failed, restored original");
        }
        Files.deleteIfExists(backup);
        return dropped;
    }

    private static void cleanManifest(java.util.jar.Manifest m, java.util.jar.Attributes main) {
        // Drop signature / signer info attributes that reference removed certs.
        for (Object k : new ArrayList<>(main.keySet())) {
            String ks = ((java.util.jar.Attributes.Name) k).toString().toUpperCase(Locale.ROOT);
            if (ks.startsWith("SIGNED") || ks.contains("SIGN") || ks.contains("CERT")) {
                main.remove(k);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private record EntryBuf(ZipEntry entry, byte[] data) {}
}
