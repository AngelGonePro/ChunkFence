package net.cosmoscraft.chunkfence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Work done in onLoad, before any world is loaded and while no region file is open:
 * recovery of cut-off chunk sections, then the world-height data pack.
 */
final class StartupMaintenance {

    private final Logger log;
    private final Path dataFolder;
    private final Path container;
    private final Path serverProperties;
    private final String minecraftVersion;
    private final Settings settings;
    private final ExecutorService pool;
    /** Saved-height probe results by normalized region folder; reused by the guard. */
    final Map<Path, HeightProbe.Result> probes = new ConcurrentHashMap<>();
    boolean heightsChanged;
    boolean datapacksChanged;
    boolean biomesChanged;
    int biomesRegistered;
    private final List<Path> uploadedIrisPacks = new ArrayList<>();
    final Map<HeightSync.Dim, HeightSync.Heights> appliedHeights = new EnumMap<>(HeightSync.Dim.class);
    /** Heights this start's data pack sets (vanilla dims included); null until syncHeights ran. */
    Map<HeightSync.Dim, HeightSync.Heights> targets;
    String dataVersion;
    String level;

    StartupMaintenance(Logger log, Path dataFolder, Path container, Path serverProperties, String minecraftVersion,
                       Settings settings, ExecutorService pool) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.container = container;
        this.serverProperties = serverProperties;
        this.minecraftVersion = minecraftVersion;
        this.settings = settings;
        this.pool = pool;
    }

    String levelName() {
        final Properties p = new Properties();
        try (var in = Files.newInputStream(this.serverProperties)) {
            p.load(in);
        } catch (IOException e) {
            return "world";
        }
        final String name = p.getProperty("level-name", "world").trim();
        return name.isEmpty() ? "world" : name;
    }

    /** Region folders of the main world's three dimensions (1.21.x layout and the newer dimensions/ layout). */
    Map<HeightSync.Dim, Path> mainRegionDirs(String level) {
        final Map<HeightSync.Dim, Path> out = new EnumMap<>(HeightSync.Dim.class);
        final Path base = this.container.resolve(level);
        out.put(HeightSync.Dim.OVERWORLD, firstExisting(
            base.resolve("region"), base.resolve("dimensions/minecraft/overworld/region")));
        out.put(HeightSync.Dim.NETHER, firstExisting(
            this.container.resolve(level + "_nether/DIM-1/region"), base.resolve("DIM-1/region"),
            base.resolve("dimensions/minecraft/the_nether/region")));
        out.put(HeightSync.Dim.END, firstExisting(
            this.container.resolve(level + "_the_end/DIM1/region"), base.resolve("DIM1/region"),
            base.resolve("dimensions/minecraft/the_end/region")));
        return out;
    }

    private static Path firstExisting(Path... candidates) {
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return candidates[0];
    }

    HeightProbe.Result probe(Path regionDir) {
        final Path key = regionDir.toAbsolutePath().normalize();
        final HeightProbe.Result cached = this.probes.get(key);
        if (cached != null || this.probes.containsKey(key)) {
            return cached;
        }
        HeightProbe.Result r = null;
        try {
            r = HeightProbe.probe(regionDir, 600);
        } catch (IOException e) {
            this.log.log(Level.WARNING, "Could not read " + regionDir, e);
        }
        if (r != null) {
            this.probes.put(key, r);
        }
        return r;
    }

    void run() {
        final String level = levelName();
        this.level = level;
        if (!this.settings.recoveryMode.equals("off")) {
            try {
                recoverAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                this.log.log(Level.SEVERE, "Chunk recovery failed; region files were left as they were", e);
            }
        }
        try {
            final DatapackSync.Outcome dp = DatapackSync.sync(this.dataFolder.resolve("datapacks"),
                this.container.resolve(level).resolve("datapacks"), this.log::warning);
            this.uploadedIrisPacks.addAll(dp.irisPacks());
            this.datapacksChanged = dp.changed();
            if (!dp.installed().isEmpty()) {
                this.log.info("Copied data pack(s) " + dp.installed() + " from plugins/ChunkFence/datapacks into "
                    + level + "/datapacks - they take effect on the NEXT start");
            }
            if (!dp.removed().isEmpty()) {
                this.log.info("Removed data pack(s) " + dp.removed() + " from " + level
                    + "/datapacks (deleted from plugins/ChunkFence/datapacks) - takes effect on the NEXT start");
            }
            for (Path p : dp.irisPacks()) {
                this.log.info("Iris pack in plugins/ChunkFence/datapacks: " + p.getFileName() + " (used for world heights)");
            }
        } catch (IOException | RuntimeException e) {
            this.log.log(Level.SEVERE, "Could not copy data packs from plugins/ChunkFence/datapacks", e);
        }
        if (!this.settings.heightsMode.equals("off")) {
            try {
                syncHeights(level);
            } catch (IOException | RuntimeException e) {
                this.log.log(Level.SEVERE, "Could not set up world heights", e);
            }
        }
        if (this.settings.biomesEnabled) {
            try {
                syncBiomes(level);
            } catch (IOException | RuntimeException e) {
                this.log.log(Level.SEVERE, "Could not set up custom biomes", e);
            }
        }
    }

    // ------------------------------------------------------------------ biomes

    private void syncBiomes(String level) throws IOException {
        String version = this.dataVersion;
        if (version == null) {
            version = HeightSync.pickVersion(this.minecraftVersion, HeightSync.bundledVersions());
        }
        if (version == null) {
            return;
        }
        final java.util.Set<String> used = new java.util.TreeSet<>();
        for (Path[] wr : allRegionDirs()) {
            used.addAll(BiomeSync.sampleCustomBiomes(wr[1], 400));
        }
        final java.util.Map<String, BiomeSync.Custom> defs = new java.util.TreeMap<>();
        final java.util.Set<String> wanted = new java.util.TreeSet<>(used);
        // packs saved to plugins/ChunkFence/datapacks: register all their biomes (the pack is kept for its old worlds)
        for (Path p : this.uploadedIrisPacks) {
            final java.util.Map<String, BiomeSync.Custom> m = BiomeSync.readIrisBiomes(p);
            wanted.addAll(m.keySet());
            m.forEach(defs::putIfAbsent);
        }
        // installed Iris packs: only used to look up settings for biomes found in saved chunks
        for (Path p : BiomeSync.irisPacks(java.util.List.of(), this.container.resolve("plugins/Iris/packs"))) {
            BiomeSync.readIrisBiomes(p).forEach(defs::putIfAbsent);
        }
        final Path worldDatapacks = this.container.resolve(level).resolve("datapacks");
        final java.util.Set<String> already = BiomeSync.definedByOtherPacks(worldDatapacks);
        final java.util.Map<String, BiomeSync.Custom> toWrite = new java.util.TreeMap<>();
        int unknown = 0;
        for (String id : wanted) {
            if (already.contains(id)) {
                continue;
            }
            BiomeSync.Custom c = defs.get(id);
            if (c == null) {
                c = BiomeSync.Custom.plain();
                unknown++;
            }
            toWrite.put(id, c);
        }
        final BiomeSync.Outcome out = BiomeSync.write(this.container.resolve(level), version, toWrite, this.log::info);
        out.messages().forEach(this.log::warning);
        this.biomesChanged = out.changed();
        this.biomesRegistered = out.biomes();
        if (out.biomes() > 0) {
            this.log.info("Custom biomes kept registered: " + out.biomes() + " (" + (used.size()) + " seen in saved chunks"
                + (unknown > 0 ? ", " + unknown + " without settings in any Iris pack -> registered like plains" : "")
                + (already.isEmpty() ? "" : ", " + already.size() + " already defined by other data packs") + ")");
        }
        if (out.changed()) {
            this.log.warning("Biome data pack " + level + "/datapacks/" + BiomeSync.PACK_NAME
                + " was created or updated. It takes effect on the NEXT start.");
        }
    }

    // ------------------------------------------------------------------ recovery

    private List<Path[]> allRegionDirs() throws IOException {
        final List<Path[]> out = new ArrayList<>(); // {worldDir, regionDir}
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(this.container)) {
            for (Path world : ds) {
                if (!Files.isDirectory(world) || !Files.isRegularFile(world.resolve("level.dat"))) {
                    continue;
                }
                for (String rel : new String[]{"region", "DIM-1/region", "DIM1/region"}) {
                    if (Files.isDirectory(world.resolve(rel))) {
                        out.add(new Path[]{world, world.resolve(rel)});
                    }
                }
                final Path dims = world.resolve("dimensions");
                if (Files.isDirectory(dims)) {
                    try (var walk = Files.walk(dims, 3)) {
                        walk.filter(p -> p.getFileName().toString().equals("region") && Files.isDirectory(p))
                            .forEach(p -> out.add(new Path[]{world, p}));
                    }
                }
            }
        }
        return out;
    }

    private void recoverAll() throws IOException, InterruptedException {
        final boolean repair = this.settings.recoveryMode.equals("repair");
        final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        final Path markers = this.dataFolder.resolve("recovery");
        Files.createDirectories(markers);
        for (Path[] wr : allRegionDirs()) {
            final Path world = wr[0];
            final Path regionDir = wr[1];
            final String rel = this.container.relativize(regionDir).toString().replace('\\', '/');
            final Path marker = markers.resolve(rel.replace('/', '_') + "." + this.settings.recoveryMode + ".done");
            if (Files.exists(marker)) {
                continue;
            }
            this.log.info("Checking " + rel + " for chunks that lost blocks to a height change (first start only)...");
            final long t0 = System.nanoTime();
            final Path backup = this.dataFolder.resolve("backups").resolve(stamp).resolve(rel);
            final Recovery.Summary s = Recovery.run(regionDir, repair, backup, this.pool, this.log::info);
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            final StringBuilder report = new StringBuilder();
            report.append("ChunkFence recovery report for ").append(rel).append(" (").append(stamp).append(")\n")
                .append("mode: ").append(this.settings.recoveryMode).append('\n')
                .append("region files: ").append(s.regionFiles()).append(", chunks checked: ").append(s.chunks()).append('\n')
                .append("chunks shorter than the rest of the world: ").append(s.damaged()).append('\n')
                .append(repair ? "restored from an older full-height copy: " : "restorable from an older full-height copy: ")
                .append(s.repaired()).append('\n')
                .append("no older full-height copy left: ").append(s.noCopyLeft()).append('\n');
            if (!s.changedFiles().isEmpty()) {
                report.append("\nRegion files rewritten (originals copied to ").append(backup).append("):\n");
                s.changedFiles().forEach(f -> report.append("  ").append(f).append('\n'));
            }
            if (!s.unrecovered().isEmpty()) {
                report.append("\nChunks without an older full-height copy (blocks above/below the cut could not be restored):\n");
                s.unrecovered().forEach(u -> report.append("  ").append(u).append('\n'));
            }
            final Path reportFile = this.dataFolder.resolve("recovery-report-" + stamp + "-" + rel.replace('/', '_') + ".txt");
            if (s.damaged() > 0) {
                Files.writeString(reportFile, report.toString(), StandardCharsets.UTF_8);
            }
            this.log.info(rel + ": checked " + s.chunks() + " chunks in " + ms + " ms - " + s.damaged() + " cut off, "
                + s.repaired() + (repair ? " restored" : " restorable") + ", " + s.noCopyLeft() + " without an older copy"
                + (s.damaged() > 0 ? " (details: " + this.dataFolder.relativize(reportFile) + ")" : ""));
            Files.writeString(marker, report.toString(), StandardCharsets.UTF_8);
            if (!s.changedFiles().isEmpty()) {
                this.probes.remove(regionDir.toAbsolutePath().normalize());
            }
        }
    }

    // ------------------------------------------------------------------ heights

    /** Heights the data pack sets for a dimension this start (vanilla if it sets none), or null if heights are off. */
    HeightSync.Heights planned(HeightSync.Dim dim) {
        if (this.targets == null) {
            return null;
        }
        return this.targets.get(dim);
    }

    /**
     * Main thread. Grows the height of a dimension type so the given saved range fits and rewrites the data pack.
     * Returns the new heights, or null if heights are not managed on this server.
     */
    HeightSync.Heights raise(HeightSync.Dim dim, int minY, int maxY) throws IOException {
        if (this.targets == null || this.dataVersion == null) {
            return null;
        }
        final HeightSync.Heights cur = this.targets.get(dim);
        final HeightSync.Heights raised = new HeightSync.Heights(Math.min(cur.minY(), minY), Math.max(cur.maxY(), maxY), cur.logical());
        final String problem = HeightSync.validate(raised);
        if (problem != null) {
            throw new IOException(dim.file + " " + raised + " is not allowed: " + problem);
        }
        this.targets.put(dim, raised);
        final HeightSync.Outcome out = HeightSync.write(this.container.resolve(this.level), this.dataVersion, this.targets, this.log::info);
        this.appliedHeights.putAll(out.applied());
        this.heightsChanged = true;
        return raised;
    }

    /**
     * Which Iris pack describes the main world: the one named in config (looked up in
     * plugins/ChunkFence/datapacks, then plugins/Iris/packs), else an Iris pack uploaded to
     * plugins/ChunkFence/datapacks, else bukkit.yml's "generator: Iris:&lt;pack&gt;", else the installed Iris pack
     * whose overworld height matches the saved chunks (or the only one installed).
     */
    Path resolveIrisPack(String level, HeightProbe.Result storedOverworld) throws IOException {
        final Path irisPacks = this.container.resolve("plugins/Iris/packs");
        final String configured = this.settings.irisPack;
        if (configured != null && !configured.isBlank() && !configured.equalsIgnoreCase("auto")) {
            final Path up = this.dataFolder.resolve("datapacks").resolve(configured.trim());
            if (DatapackSync.isIrisPack(up)) {
                return up;
            }
            return irisPacks.resolve(configured.trim());
        }
        final Path fromUpload = pickMatching(this.uploadedIrisPacks, storedOverworld);
        if (fromUpload != null) {
            return fromUpload;
        }
        final String gen = HeightSync.detectIrisPack(null, this.container.resolve("bukkit.yml"), level, this.container.resolve("no-such-folder"));
        if (gen != null && DatapackSync.isIrisPack(irisPacks.resolve(gen))) {
            return irisPacks.resolve(gen);
        }
        final List<Path> installed = new ArrayList<>();
        if (Files.isDirectory(irisPacks)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(irisPacks)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p.resolve("dimensions"))) {
                        installed.add(p);
                    }
                }
            }
        }
        return pickMatching(installed, storedOverworld);
    }

    /** The only pack, or the pack whose overworld height equals the saved chunks' height. */
    private Path pickMatching(List<Path> packs, HeightProbe.Result stored) {
        if (packs.isEmpty()) {
            return null;
        }
        if (packs.size() == 1 && stored == null) {
            return packs.get(0);
        }
        Path only = packs.size() == 1 ? packs.get(0) : null;
        if (stored != null) {
            for (Path p : packs) {
                try {
                    final HeightSync.Heights h = HeightSync.readIrisDir(p).get(HeightSync.Dim.OVERWORLD);
                    if (h != null && h.minY() == stored.minY() && h.maxY() == stored.maxY()) {
                        return p;
                    }
                } catch (IOException ignored) {
                    // not readable: skip
                }
            }
        }
        return only;
    }

    private void syncHeights(String level) throws IOException {
        final List<String> bundled = HeightSync.bundledVersions();
        final String version = HeightSync.pickVersion(this.minecraftVersion, bundled);
        if (version == null) {
            this.log.warning("Minecraft " + this.minecraftVersion + " is older than 1.20.5; world heights are not managed");
            return;
        }
        if (!version.equals(this.minecraftVersion)) {
            this.log.warning("No dimension data bundled for Minecraft " + this.minecraftVersion + "; using the " + version
                + " format. Update ChunkFence if the server fails to load the chunkfence-heights data pack.");
        }
        final Map<HeightSync.Dim, Path> dirs = mainRegionDirs(level);
        final HeightProbe.Result storedOverworld = Files.isDirectory(dirs.get(HeightSync.Dim.OVERWORLD))
            ? probe(dirs.get(HeightSync.Dim.OVERWORLD)) : null;
        Map<HeightSync.Dim, HeightSync.Heights> iris = Map.of();
        String pack = null;
        try {
            final Path packDir = resolveIrisPack(level, storedOverworld);
            if (packDir != null) {
                pack = packDir.getFileName().toString();
                iris = HeightSync.readIrisDir(packDir);
            }
        } catch (IOException e) {
            this.log.log(Level.WARNING, "Could not read the Iris pack" + (pack == null ? "" : " '" + pack + "'"), e);
        }
        final Map<HeightSync.Dim, HeightSync.Heights> targets = new EnumMap<>(HeightSync.Dim.class);
        for (HeightSync.Dim dim : HeightSync.Dim.values()) {
            final HeightProbe.Result stored = Files.isDirectory(dirs.get(dim)) ? probe(dirs.get(dim)) : null;
            final HeightSync.Heights vanilla = HeightSync.vanilla(version, dim);
            HeightSync.Heights t = HeightSync.choose(this.settings.heightsMode, dim, vanilla, stored, iris.get(dim),
                this.settings.manualHeights.get(dim));
            final HeightSync.Heights raised = this.settings.raisedHeights.get(dim);
            if (raised != null && (raised.minY() < t.minY() || raised.maxY() > t.maxY())) {
                t = new HeightSync.Heights(Math.min(t.minY(), raised.minY()), Math.max(t.maxY(), raised.maxY()), t.logical());
                this.log.info(dim.file + " raised to " + t + " by heights.raised." + dim.file + " in config.yml");
            }
            targets.put(dim, t);
            this.log.info("Height of " + level + " " + dim.file + ": " + t
                + (stored != null ? " | saved chunks: " + stored : " | no saved chunks")
                + (iris.get(dim) != null ? " | Iris pack '" + pack + "': " + iris.get(dim) : ""));
        }
        this.targets = targets;
        this.dataVersion = version;
        final HeightSync.Outcome out = HeightSync.write(this.container.resolve(level), version, targets, this.log::info);
        out.messages().forEach(this.log::warning);
        this.appliedHeights.putAll(out.applied());
        this.heightsChanged = out.changed();
        if (out.changed()) {
            this.log.warning("World height data pack " + level + "/datapacks/" + HeightSync.PACK_NAME
                + " was created or updated. It takes effect on the NEXT start.");
        }
    }
}
