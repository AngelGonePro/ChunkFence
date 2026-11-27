package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * plugins/ChunkFence/datapacks/ is an upload folder: every data pack put there (a folder with pack.mcmeta,
 * or a .zip) is copied into the main world's datapacks folder, kept up to date, and removed from the world
 * again when it is deleted from the upload folder. Iris packs put there (a folder with a dimensions/ folder)
 * are not copied; they are read for the world heights instead.
 */
final class DatapackSync {

    static final String STATE_FILE = ".installed-by-chunkfence";

    record Outcome(boolean changed, List<String> installed, List<String> removed, List<Path> irisPacks) {
    }

    private DatapackSync() {
    }

    static void ensureFolder(Path upload) throws IOException {
        Files.createDirectories(upload);
        final Path readme = upload.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme, """
                Put data packs here: a folder that contains pack.mcmeta, or a .zip data pack.
                On every start ChunkFence copies them into the main world's datapacks folder
                (<level-name>/datapacks), updates them when you change them here, and removes them
                from the world when you delete them here. Data packs only take effect on the start
                after they were copied, so start the server once more after adding or changing one.

                An Iris pack (a folder with a 'dimensions' folder, e.g. the contents of
                plugins/Iris/packs/<pack>) can also be put here: ChunkFence then reads the world
                heights from it (see 'heights' in config.yml). It is not copied into the world.
                """, StandardCharsets.UTF_8);
        }
    }

    static boolean isIrisPack(Path p) {
        return Files.isDirectory(p) && Files.isDirectory(p.resolve("dimensions")) && !Files.exists(p.resolve("pack.mcmeta"));
    }

    static Outcome sync(Path upload, Path worldDatapacks, Consumer<String> log) throws IOException {
        ensureFolder(upload);
        final List<Path> packs = new ArrayList<>();
        final List<Path> iris = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(upload)) {
            for (Path p : ds) {
                final String name = p.getFileName().toString();
                if (name.startsWith(".") || name.equalsIgnoreCase("README.txt")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    if (Files.isRegularFile(p.resolve("pack.mcmeta"))) {
                        packs.add(p);
                    } else if (isIrisPack(p)) {
                        iris.add(p);
                    } else {
                        log.accept("datapacks/" + name + " has no pack.mcmeta (not a data pack) and no dimensions folder (not an Iris pack) - ignored");
                    }
                } else if (name.toLowerCase().endsWith(".zip")) {
                    if (zipHasMcmeta(p)) {
                        packs.add(p);
                    } else {
                        log.accept("datapacks/" + name + " has no pack.mcmeta at its top level - ignored");
                    }
                }
            }
        }

        final Path state = upload.resolve(STATE_FILE);
        final Set<String> before = new LinkedHashSet<>();
        if (Files.isRegularFile(state)) {
            for (String l : Files.readAllLines(state, StandardCharsets.UTF_8)) {
                if (!l.isBlank()) {
                    before.add(l.trim());
                }
            }
        }
        final Set<String> now = new LinkedHashSet<>();
        final List<String> installed = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        boolean changed = false;
        Files.createDirectories(worldDatapacks);
        for (Path src : packs) {
            final String name = src.getFileName().toString();
            now.add(name);
            final Path dst = worldDatapacks.resolve(name);
            if (Files.exists(dst) && hash(dst).equals(hash(src))) {
                continue;
            }
            deleteTree(dst);
            copy(src, dst);
            installed.add(name);
            changed = true;
        }
        for (String old : before) {
            if (!now.contains(old)) {
                final Path dst = worldDatapacks.resolve(old);
                if (Files.exists(dst)) {
                    deleteTree(dst);
                    removed.add(old);
                    changed = true;
                }
            }
        }
        Files.writeString(state, String.join("\n", now) + "\n", StandardCharsets.UTF_8);
        return new Outcome(changed, installed, removed, iris);
    }

    private static boolean zipHasMcmeta(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.getEntry("pack.mcmeta") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Content hash of a file or folder tree (relative paths + bytes). */
    static String hash(Path root) throws IOException {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (Files.isRegularFile(root)) {
            try (InputStream in = Files.newInputStream(root)) {
                md.update(in.readAllBytes());
            }
        } else {
            final List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            files.sort(null);
            for (Path f : files) {
                md.update(root.relativize(f).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void copy(Path src, Path dst) throws IOException {
        if (Files.isRegularFile(src)) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        if (Files.isDirectory(p)) {
            try (Stream<Path> walk = Files.walk(p)) {
                final List<Path> all = new ArrayList<>();
                walk.forEach(all::add);
                for (int i = all.size() - 1; i >= 0; i--) {
                    Files.deleteIfExists(all.get(i));
                }
            }
        } else {
            Files.deleteIfExists(p);
        }
    }
}
