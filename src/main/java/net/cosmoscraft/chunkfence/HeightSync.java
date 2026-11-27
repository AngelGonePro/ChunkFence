package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Installs a data pack that sets the height of the main world's overworld, nether and end, so they load
 * with the height their chunks were saved with (or the height an Iris pack declares).
 * <p>
 * The dimension type files are the exact vanilla files of the running Minecraft version (bundled in the jar,
 * taken from the game's own data) with only min_y, height and logical_height changed.
 */
final class HeightSync {

    enum Dim {
        OVERWORLD("overworld", "dimensionHeight", "logicalHeight"),
        NETHER("the_nether", "dimensionHeightNether", "logicalHeightNether"),
        END("the_end", "dimensionHeightEnd", "logicalHeightEnd");

        final String file;
        final String irisHeightKey;
        final String irisLogicalKey;

        Dim(String file, String irisHeightKey, String irisLogicalKey) {
            this.file = file;
            this.irisHeightKey = irisHeightKey;
            this.irisLogicalKey = irisLogicalKey;
        }
    }

    /** min_y, exclusive top, logical_height. */
    record Heights(int minY, int maxY, int logical) {
        int height() {
            return this.maxY - this.minY;
        }

        @Override
        public String toString() {
            return "Y " + this.minY + " to " + this.maxY + " (height " + height() + ", logical " + this.logical + ")";
        }
    }

    record Outcome(boolean changed, Map<Dim, Heights> applied, List<String> messages) {
    }

    static final String PACK_NAME = "chunkfence-heights";

    private HeightSync() {
    }

    // ------------------------------------------------------------------ bundled vanilla data

    static List<String> bundledVersions() throws IOException {
        try (InputStream in = HeightSync.class.getResourceAsStream("/vanilla-dimension-types/index")) {
            if (in == null) {
                throw new IOException("vanilla-dimension-types/index missing from jar");
            }
            final List<String> out = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
            return out;
        }
    }

    static int compareVersions(String a, String b) {
        final String[] x = a.split("\\.");
        final String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final int p = i < x.length ? parseIntSafe(x[i]) : 0;
            final int q = i < y.length ? parseIntSafe(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        final StringBuilder d = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                d.append(c);
            } else {
                break;
            }
        }
        return d.length() == 0 ? 0 : Integer.parseInt(d.toString());
    }

    /** Exact match, else the newest bundled version that is not newer than the running one; null if older than all. */
    static String pickVersion(String running, List<String> bundled) {
        String best = null;
        for (String v : bundled) {
            if (compareVersions(v, running) <= 0 && (best == null || compareVersions(v, best) > 0)) {
                best = v;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> template(String version, Dim dim) throws IOException {
        try (InputStream in = HeightSync.class.getResourceAsStream("/vanilla-dimension-types/" + version + "/" + dim.file + ".json")) {
            if (in == null) {
                throw new IOException("No bundled " + dim.file + " dimension type for " + version);
            }
            return (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String packFormat(String version) throws IOException {
        try (InputStream in = HeightSync.class.getResourceAsStream("/vanilla-dimension-types/" + version + "/pack_format")) {
            if (in == null) {
                throw new IOException("No pack format for " + version);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    static Heights vanilla(String version, Dim dim) throws IOException {
        final Map<String, Object> t = template(version, dim);
        final int min = num(t.get("min_y"));
        return new Heights(min, min + num(t.get("height")), num(t.get("logical_height")));
    }

    private static int num(Object o) {
        if (o instanceof Json.Num n) {
            return (int) Math.round(n.doubleValue());
        }
        throw new IllegalArgumentException("Not a number: " + o);
    }

    // ------------------------------------------------------------------ Iris pack

    /** Finds the Iris pack used by the main world: config value, bukkit.yml "generator: Iris:<pack>", or the only installed pack. */
    static String detectIrisPack(String configured, Path bukkitYml, String levelName, Path irisPacks) throws IOException {
        if (configured != null && !configured.isBlank() && !configured.equalsIgnoreCase("auto")) {
            return configured.trim();
        }
        if (Files.isRegularFile(bukkitYml)) {
            final String gen = bukkitGenerator(Files.readAllLines(bukkitYml, StandardCharsets.UTF_8), levelName);
            if (gen != null && gen.toLowerCase().startsWith("iris")) {
                final int colon = gen.indexOf(':');
                if (colon > 0 && colon < gen.length() - 1) {
                    return gen.substring(colon + 1).trim();
                }
            }
        }
        if (Files.isDirectory(irisPacks)) {
            final List<String> packs = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(irisPacks)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p.resolve("dimensions"))) {
                        packs.add(p.getFileName().toString());
                    }
                }
            }
            if (packs.size() == 1) {
                return packs.get(0);
            }
        }
        return null;
    }

    /** Reads worlds.&lt;level&gt;.generator from bukkit.yml (simple indentation-based lookup). */
    static String bukkitGenerator(List<String> lines, String levelName) {
        boolean inWorlds = false;
        boolean inLevel = false;
        int levelIndent = -1;
        for (String raw : lines) {
            final String line = raw.replaceAll("\\s+#.*$", "");
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            final int indent = line.length() - line.stripLeading().length();
            final String t = line.trim();
            if (indent == 0) {
                inWorlds = t.equals("worlds:");
                inLevel = false;
                continue;
            }
            if (!inWorlds) {
                continue;
            }
            if (!inLevel) {
                final String key = t.endsWith(":") ? unquote(t.substring(0, t.length() - 1)) : null;
                if (key != null && key.equals(levelName)) {
                    inLevel = true;
                    levelIndent = indent;
                }
                continue;
            }
            if (indent <= levelIndent) {
                inLevel = false;
                final String key = t.endsWith(":") ? unquote(t.substring(0, t.length() - 1)) : null;
                if (key != null && key.equals(levelName)) {
                    inLevel = true;
                    levelIndent = indent;
                }
                continue;
            }
            if (t.startsWith("generator:")) {
                return unquote(t.substring("generator:".length()).trim());
            }
        }
        return null;
    }

    private static String unquote(String s) {
        final String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** Heights declared by an Iris dimension file, per vanilla dimension. Missing entries are left out. */
    @SuppressWarnings("unchecked")
    static Map<Dim, Heights> readIris(Path irisPacks, String pack) throws IOException {
        return readIrisDir(irisPacks.resolve(pack));
    }

    /** Reads an Iris pack folder (the one that contains 'dimensions'). */
    @SuppressWarnings("unchecked")
    static Map<Dim, Heights> readIrisDir(Path packDir) throws IOException {
        final String pack = packDir.getFileName().toString();
        final Path irisPacks = packDir.getParent();
        Path file = irisPacks.resolve(pack).resolve("dimensions").resolve(pack + ".json");
        if (!Files.isRegularFile(file)) {
            // fall back to the only dimension file of the pack
            final List<Path> dims = new ArrayList<>();
            final Path dir = irisPacks.resolve(pack).resolve("dimensions");
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                    ds.forEach(dims::add);
                }
            }
            if (dims.size() != 1) {
                throw new IOException("Cannot find the dimension file of Iris pack '" + pack + "' in " + dir);
            }
            file = dims.get(0);
        }
        final Map<String, Object> d = (Map<String, Object>) Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        final Map<Dim, Heights> out = new EnumMap<>(Dim.class);
        for (Dim dim : Dim.values()) {
            final Object h = d.get(dim.irisHeightKey);
            if (h instanceof Map<?, ?> m && m.get("min") instanceof Json.Num mn && m.get("max") instanceof Json.Num mx) {
                final int min = (int) Math.round(mn.doubleValue());
                final int max = (int) Math.round(mx.doubleValue());
                int logical = max - min;
                if (d.get(dim.irisLogicalKey) instanceof Json.Num l) {
                    logical = (int) Math.round(l.doubleValue());
                }
                out.put(dim, new Heights(min, max, logical));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ choosing and writing

    static String validate(Heights h) {
        if (h.minY() % 16 != 0 || h.maxY() % 16 != 0) {
            return "min and max must be multiples of 16";
        }
        if (h.height() < 16 || h.height() > 4064) {
            return "height must be between 16 and 4064";
        }
        if (h.minY() < -2032 || h.maxY() > 2032) {
            return "Y must stay between -2032 and 2032";
        }
        if (h.logical() < 0 || h.logical() > h.height()) {
            return "logical height must be between 0 and the height";
        }
        return null;
    }

    /**
     * Picks the heights for one dimension. The result never cuts into what the saved chunks already use.
     *
     * @param mode   auto (saved chunks, else Iris), iris (Iris pack), manual (config)
     * @param stored range found in the saved chunks, or null
     */
    static Heights choose(String mode, Dim dim, Heights vanilla, HeightProbe.Result stored, Heights iris, Heights manual) {
        Heights base;
        switch (mode) {
            case "iris" -> base = iris;
            case "manual" -> base = manual;
            default -> {
                if (stored == null) {
                    base = iris;
                } else if (stored.minY() == vanilla.minY() && stored.maxY() == vanilla.maxY()) {
                    base = vanilla; // saved with the vanilla height: keep vanilla
                } else if (iris != null && iris.minY() == stored.minY() && iris.maxY() == stored.maxY()) {
                    base = iris; // saved chunks match the Iris pack exactly
                } else {
                    final int logical = iris != null ? iris.logical()
                        : dim == Dim.NETHER ? vanilla.logical() : stored.maxY() - stored.minY();
                    base = new Heights(stored.minY(), stored.maxY(), logical);
                }
            }
        }
        if (base == null) {
            base = vanilla;
        }
        int min = base.minY();
        int max = base.maxY();
        if (stored != null) { // never smaller than the saved chunks
            min = Math.min(min, stored.minY());
            max = Math.max(max, stored.maxY());
        }
        int logical = Math.min(base.logical(), max - min);
        if (logical <= 0) {
            logical = Math.min(vanilla.logical(), max - min);
        }
        return new Heights(min, max, logical);
    }

    /**
     * Writes (or removes) the data pack. Returns whether anything on disk changed: if so, the heights only take
     * effect after the next server start.
     */
    static Outcome write(Path levelDir, String version, Map<Dim, Heights> targets, Consumer<String> log) throws IOException {
        final Path pack = levelDir.resolve("datapacks").resolve(PACK_NAME);
        final List<String> messages = new ArrayList<>();
        final Map<Dim, Heights> applied = new EnumMap<>(Dim.class);
        final Map<Path, String> files = new LinkedHashMap<>();
        for (Map.Entry<Dim, Heights> e : targets.entrySet()) {
            final Dim dim = e.getKey();
            final Heights h = e.getValue();
            final Heights v = vanilla(version, dim);
            if (h.minY() == v.minY() && h.maxY() == v.maxY() && h.logical() == v.logical()) {
                continue; // vanilla already: no override needed
            }
            final String problem = validate(h);
            if (problem != null) {
                messages.add(dim.file + ": " + h + " is not allowed (" + problem + "), left as is");
                continue;
            }
            final Map<String, Object> t = template(version, dim);
            t.put("min_y", Json.Num.of(h.minY()));
            t.put("height", Json.Num.of(h.height()));
            t.put("logical_height", Json.Num.of(h.logical()));
            files.put(pack.resolve("data/minecraft/dimension_type/" + dim.file + ".json"), Json.write(t));
            applied.put(dim, h);
        }
        boolean changed = false;
        if (files.isEmpty()) {
            if (Files.exists(pack)) {
                deleteTree(pack);
                changed = true;
            }
            return new Outcome(changed, applied, messages);
        }
        files.put(pack.resolve("pack.mcmeta"), packMcmeta(packFormat(version)));
        // remove dimension files we no longer write
        final Path dimDir = pack.resolve("data/minecraft/dimension_type");
        if (Files.isDirectory(dimDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dimDir)) {
                for (Path p : ds) {
                    if (!files.containsKey(p)) {
                        Files.delete(p);
                        changed = true;
                    }
                }
            }
        }
        for (Map.Entry<Path, String> f : files.entrySet()) {
            final byte[] want = f.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(f.getKey()) && java.util.Arrays.equals(Files.readAllBytes(f.getKey()), want)) {
                continue;
            }
            Files.createDirectories(f.getKey().getParent());
            final Path tmp = f.getKey().resolveSibling(f.getKey().getFileName() + ".tmp");
            Files.write(tmp, want);
            Files.move(tmp, f.getKey(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            changed = true;
        }
        return new Outcome(changed, applied, messages);
    }

    static String packMcmeta(String format) {
        final String[] parts = format.split("\\.");
        final int major = Integer.parseInt(parts[0]);
        final Map<String, Object> packObj = new LinkedHashMap<>();
        packObj.put("description", "ChunkFence: keeps world heights matching the saved chunks");
        if (major >= 82) {
            // 1.21.9+ (incl. 26.x): min_format/max_format only. An integer max_format accepts any minor version.
            packObj.put("min_format", Json.Num.of(major));
            packObj.put("max_format", Json.Num.of(major));
        } else {
            packObj.put("pack_format", Json.Num.of(major));
            packObj.put("supported_formats", List.of(Json.Num.of(major), Json.Num.of(major)));
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("pack", packObj);
        return Json.write(root);
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteTree(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
