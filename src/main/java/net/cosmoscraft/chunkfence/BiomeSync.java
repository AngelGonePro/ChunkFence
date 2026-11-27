package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Keeps custom biomes (e.g. Iris's "rrtspecial:hot_beach") registered after the plugin that made them is gone.
 * <p>
 * Without them, every chunk section that uses one logs "Unknown registry key ... using default" while loading
 * (which floods the console and slows chunk loading down) and the biome is replaced with a default one when the
 * chunk is saved. ChunkFence writes the data pack &lt;level&gt;/datapacks/chunkfence-biomes registering each custom
 * biome as a copy of Minecraft's own file for its base biome (the Iris "derivative"), with the colours,
 * temperature and rain settings from the Iris pack.
 */
final class BiomeSync {

    static final String PACK_NAME = "chunkfence-biomes";

    record Outcome(boolean changed, int biomes, List<String> messages) {
    }

    /** Settings for one custom biome. Colours are "#rrggbb" or null. */
    record Custom(String derivative, String grass, String foliage, String water, String waterFog, String sky, String fog,
                  Double temperature, Double humidity, String downfallType) {
        static Custom plain() {
            return new Custom("plains", null, null, null, null, null, null, null, null, null);
        }
    }

    private BiomeSync() {
    }

    // ------------------------------------------------------------------ Iris pack

    /** Custom biome ids ("pack:id") declared in an Iris pack's biomes folder, with their settings. */
    @SuppressWarnings("unchecked")
    static Map<String, Custom> readIrisBiomes(Path packDir) throws IOException {
        final String ns = packDir.getFileName().toString().toLowerCase(Locale.ROOT);
        final Map<String, Custom> out = new TreeMap<>();
        final Path biomes = packDir.resolve("biomes");
        if (!Files.isDirectory(biomes)) {
            return out;
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(biomes)) {
            walk.filter(p -> p.toString().endsWith(".json")).sorted().forEach(files::add);
        }
        for (Path f : files) {
            final Object parsed;
            try {
                parsed = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                continue; // a broken file in the pack: skip it
            }
            if (!(parsed instanceof Map<?, ?> m)) {
                continue;
            }
            final String derivative = m.get("derivative") instanceof String s ? s.toLowerCase(Locale.ROOT) : "plains";
            if (!(m.get("customDerivitives") instanceof List<?> list)) {
                continue;
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> cd) || !(cd.get("id") instanceof String id) || id.isBlank()) {
                    continue;
                }
                final String key = ns + ":" + id.toLowerCase(Locale.ROOT);
                if (out.containsKey(key)) {
                    continue; // first definition wins, like Iris
                }
                out.put(key, new Custom(derivative, str(cd, "grassColor"), str(cd, "foliageColor"), str(cd, "waterColor"),
                    str(cd, "waterFogColor"), str(cd, "skyColor"), str(cd, "fogColor"), num(cd, "temperature"), num(cd, "humidity"),
                    str(cd, "downfallType")));
            }
        }
        return out;
    }

    private static String str(Map<?, ?> m, String k) {
        return m.get(k) instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static Double num(Map<?, ?> m, String k) {
        return m.get(k) instanceof Json.Num n ? n.doubleValue() : null;
    }

    // ------------------------------------------------------------------ biomes used by saved chunks

    /** Non-vanilla biome ids found in a sample of chunks of a region folder (reads only the sampled chunks). */
    static Set<String> sampleCustomBiomes(Path regionDir, int maxChunks) {
        final Set<String> out = new TreeSet<>();
        final List<RegionScanner.RegionFile> regions;
        try {
            regions = RegionScanner.listRegions(regionDir);
        } catch (IOException e) {
            return out;
        }
        if (regions.isEmpty()) {
            return out;
        }
        final int perRegion = Math.max(1, Math.min(16, maxChunks / regions.size()));
        for (RegionScanner.RegionFile rf : regions) {
            try (FileChannel ch = FileChannel.open(rf.path(), StandardOpenOption.READ)) {
                final long size = ch.size();
                if (size < 8192) {
                    continue;
                }
                final java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(RegionIO.SECTOR);
                while (header.hasRemaining() && ch.read(header, header.position()) >= 0) {
                    // location table
                }
                final int step = Math.max(1, 1024 / perRegion);
                int taken = 0;
                for (int start = 0; start < step && taken < perRegion; start++) {
                    for (int i = start; i < 1024 && taken < perRegion; i += step) {
                        final int entry = header.getInt(i * 4);
                        if (entry == 0 || (entry >>> 8) < 2) {
                            continue;
                        }
                        final long pos = (long) (entry >>> 8) * RegionIO.SECTOR;
                        final long want = Math.min((long) Math.max(entry & 0xFF, 1) * RegionIO.SECTOR, size - pos);
                        if (want < 5) {
                            continue;
                        }
                        final java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate((int) want);
                        while (buf.hasRemaining() && ch.read(buf, pos + buf.position()) >= 0) {
                            // chunk bytes
                        }
                        try {
                            collect(RegionIO.readChunkAt(rf.path(), buf.array(), 0, i).compound(), out);
                            taken++;
                        } catch (IOException | RuntimeException ignored) {
                            // unreadable chunk: skip
                        }
                    }
                }
            } catch (IOException ignored) {
                // unreadable region file: skip
            }
        }
        return out;
    }

    /** Biome ids already defined by other data packs of the world (e.g. Iris's own), as "ns:id". */
    static Set<String> definedByOtherPacks(Path worldDatapacks) {
        final Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(worldDatapacks)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(worldDatapacks)) {
            for (Path p : ds) {
                final String name = p.getFileName().toString();
                if (name.startsWith("chunkfence-")) {
                    continue;
                }
                if (Files.isDirectory(p.resolve("data"))) {
                    try (Stream<Path> walk = Files.walk(p.resolve("data"))) {
                        walk.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                            final Path rel = p.resolve("data").relativize(f);
                            if (rel.getNameCount() >= 4 && rel.getName(1).toString().equals("worldgen") && rel.getName(2).toString().equals("biome")) {
                                final String id = rel.subpath(3, rel.getNameCount()).toString().replace('\\', '/');
                                out.add(rel.getName(0) + ":" + id.substring(0, id.length() - 5));
                            }
                        });
                    }
                } else if (name.endsWith(".zip")) {
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                        z.stream().forEach(en -> {
                            final String[] parts = en.getName().split("/");
                            if (parts.length >= 5 && parts[0].equals("data") && parts[2].equals("worldgen") && parts[3].equals("biome")
                                && en.getName().endsWith(".json")) {
                                final String id = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
                                out.add(parts[1] + ":" + id.substring(0, id.length() - 5));
                            }
                        });
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return out;
    }

    private static void collect(Nbt.Compound chunk, Set<String> out) {
        final Nbt.NbtList sections = chunk.getList("sections");
        if (sections == null) {
            return;
        }
        for (Nbt.Tag t : sections.items) {
            if (t.type() != Nbt.COMPOUND) {
                continue;
            }
            final Nbt.Compound biomes = t.compound().getCompound("biomes");
            final Nbt.NbtList palette = biomes == null ? null : biomes.getList("palette");
            if (palette == null) {
                continue;
            }
            for (Nbt.Tag p : palette.items) {
                if (p.type() == Nbt.STRING) {
                    final String id = ((String) p.value()).toLowerCase(Locale.ROOT);
                    if (id.contains(":") && !id.startsWith("minecraft:")) {
                        out.add(id);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ writing

    static Set<String> vanillaBiomes(String version) throws IOException {
        try (InputStream in = BiomeSync.class.getResourceAsStream("/vanilla-dimension-types/" + version + "/biome/index")) {
            if (in == null) {
                throw new IOException("No bundled biomes for " + version);
            }
            final Set<String> out = new TreeSet<>();
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!l.isBlank()) {
                    out.add(l.trim());
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> vanillaBiome(String version, String name) throws IOException {
        try (InputStream in = BiomeSync.class.getResourceAsStream("/vanilla-dimension-types/" + version + "/biome/" + name + ".json")) {
            if (in == null) {
                throw new IOException("No bundled biome " + name + " for " + version);
            }
            return (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** The biome file for one custom biome: vanilla base biome + the Iris settings, in the running version's format. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> build(String version, Set<String> vanilla, Custom c) throws IOException {
        String base = c.derivative() == null ? "plains" : c.derivative().replace("minecraft:", "");
        if (!vanilla.contains(base)) {
            base = "plains";
        }
        final Map<String, Object> b = vanillaBiome(version, base);
        final Map<String, Object> effects = b.get("effects") instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
        final Map<String, Object> attributes = b.get("attributes") instanceof Map<?, ?> a ? (Map<String, Object>) a : null;
        // newer versions write colours as "#rrggbb" strings and keep sky/fog colours in "attributes"
        final boolean hexFormat = attributes != null;
        if (effects != null) {
            putColor(effects, "grass_color", c.grass(), hexFormat);
            putColor(effects, "foliage_color", c.foliage(), hexFormat);
            putColor(effects, "water_color", c.water(), hexFormat);
            if (!hexFormat) {
                putColor(effects, "water_fog_color", c.waterFog(), false);
                putColor(effects, "sky_color", c.sky(), false);
                putColor(effects, "fog_color", c.fog(), false);
            }
        }
        if (attributes != null) {
            putColor(attributes, "minecraft:visual/water_fog_color", c.waterFog(), true);
            putColor(attributes, "minecraft:visual/sky_color", c.sky(), true);
            putColor(attributes, "minecraft:visual/fog_color", c.fog(), true);
        }
        if (c.temperature() != null && b.containsKey("temperature")) {
            b.put("temperature", new Json.Num(Double.toString(c.temperature())));
        }
        if (c.humidity() != null && b.containsKey("downfall")) {
            b.put("downfall", new Json.Num(Double.toString(Math.max(0, Math.min(1, c.humidity())))));
        }
        if (c.downfallType() != null && b.containsKey("has_precipitation")) {
            b.put("has_precipitation", !c.downfallType().equalsIgnoreCase("none"));
        }
        return b;
    }

    private static void putColor(Map<String, Object> target, String key, String hex, boolean hexFormat) {
        final Integer rgb = parseColor(hex);
        if (rgb == null) {
            return;
        }
        target.put(key, hexFormat ? String.format("#%06x", rgb) : Json.Num.of(rgb));
    }

    static Integer parseColor(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (t.length() == 8) {
            t = t.substring(2); // ARGB -> RGB
        }
        if (t.length() != 6) {
            return null;
        }
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes (or removes) the biome data pack. Returns whether anything on disk changed. */
    static Outcome write(Path levelDir, String version, Map<String, Custom> biomes, Consumer<String> log) throws IOException {
        final Path pack = levelDir.resolve("datapacks").resolve(PACK_NAME);
        final List<String> messages = new ArrayList<>();
        if (biomes.isEmpty()) {
            if (Files.exists(pack)) {
                DatapackSync.deleteTree(pack);
                return new Outcome(true, 0, messages);
            }
            return new Outcome(false, 0, messages);
        }
        final Set<String> vanilla = vanillaBiomes(version);
        final Map<Path, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, Custom> e : biomes.entrySet()) {
            final String[] nsId = e.getKey().split(":", 2);
            if (nsId.length != 2 || !nsId[0].matches("[a-z0-9_.-]+") || !nsId[1].matches("[a-z0-9_./-]+")) {
                messages.add("skipped biome id with characters Minecraft does not allow: " + e.getKey());
                continue;
            }
            files.put(pack.resolve("data/" + nsId[0] + "/worldgen/biome/" + nsId[1] + ".json"), Json.write(build(version, vanilla, e.getValue())));
        }
        files.put(pack.resolve("pack.mcmeta"), HeightSync.packMcmeta(HeightSync.packFormat(version))
            .replace("keeps world heights matching the saved chunks", "keeps custom biomes of old worlds registered"));
        boolean changed = false;
        // remove files we no longer write
        if (Files.isDirectory(pack)) {
            final List<Path> existing = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(pack)) {
                walk.filter(Files::isRegularFile).forEach(existing::add);
            }
            for (Path p : existing) {
                if (!files.containsKey(p)) {
                    Files.delete(p);
                    changed = true;
                }
            }
        }
        for (Map.Entry<Path, String> f : files.entrySet()) {
            final byte[] want = f.getValue().getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(f.getKey()) && java.util.Arrays.equals(Files.readAllBytes(f.getKey()), want)) {
                continue;
            }
            Files.createDirectories(f.getKey().getParent());
            Files.write(f.getKey(), want);
            changed = true;
        }
        return new Outcome(changed, files.size() - 1, messages);
    }

    /** All Iris packs ChunkFence knows about: uploaded ones first, then plugins/Iris/packs. */
    static List<Path> irisPacks(List<Path> uploaded, Path irisPacksDir) throws IOException {
        final List<Path> out = new ArrayList<>(uploaded);
        if (Files.isDirectory(irisPacksDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(irisPacksDir)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p.resolve("biomes"))) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }
}
