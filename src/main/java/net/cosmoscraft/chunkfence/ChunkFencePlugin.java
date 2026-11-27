package net.cosmoscraft.chunkfence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkFencePlugin extends JavaPlugin {

    /** Main-thread only. Identity map: world objects are stable while loaded and this skips equals()/hashCode(). */
    private final Map<World, WorldFence> fences = new IdentityHashMap<>();
    private Settings settings;
    private ExecutorService workers;
    private ExecutorService coordinator;
    private FenceListener listener;

    private StartupMaintenance maintenance;
    private boolean lockdown;

    /** Runs before any world is loaded: chunk recovery and the world-height data pack. */
    @Override
    public void onLoad() {
        saveDefaultConfig();
        this.settings = new Settings(getConfig());
        startExecutors();
        // Nothing else runs yet, so the one-time startup work may use every core.
        final ExecutorService startupPool = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()), daemonFactory("ChunkFence-startup"));
        try {
            this.maintenance = new StartupMaintenance(getLogger(), getDataFolder().toPath(),
                getServer().getWorldContainer().toPath(), Path.of("server.properties"), minecraftVersion(), this.settings, startupPool);
            this.maintenance.run();
        } finally {
            startupPool.shutdownNow();
        }
    }

    String minecraftVersion() {
        try {
            return getServer().getMinecraftVersion();
        } catch (IncompatibleClassChangeError | UnsupportedOperationException e) {
            final String v = getServer().getBukkitVersion();
            final int dash = v.indexOf('-');
            return dash > 0 ? v.substring(0, dash) : v;
        }
    }

    /** Heights the vanilla dimension types have on this start, learned from the main world's dimensions. */
    private final java.util.Map<HeightSync.Dim, int[]> activeHeights = new java.util.EnumMap<>(HeightSync.Dim.class);

    private HeightSync.Dim mainDimension(World world) {
        final String level = this.maintenance.level;
        if (level == null) {
            return null;
        }
        if (world.getName().equals(level)) {
            return HeightSync.Dim.OVERWORLD;
        }
        if (world.getName().equals(level + "_nether")) {
            return HeightSync.Dim.NETHER;
        }
        if (world.getName().equals(level + "_the_end")) {
            return HeightSync.Dim.END;
        }
        return null;
    }

    private static HeightSync.Dim dimensionOf(World.Environment env) {
        return switch (env) {
            case NORMAL -> HeightSync.Dim.OVERWORLD;
            case NETHER -> HeightSync.Dim.NETHER;
            case THE_END -> HeightSync.Dim.END;
            default -> null;
        };
    }

    /**
     * Main thread, from WorldInitEvent (before the world loads any chunk). If the world's saved chunks are taller
     * than the height it is loading with, saving is switched off everywhere and the server is stopped, so no
     * chunk gets written back without its upper or lower part. When the world uses one of the vanilla dimension
     * types, the needed height is written to config.yml (heights.raised) and the height data pack first, so the
     * next start loads it with a height that fits.
     */
    void checkWorldHeight(World world) {
        if (this.lockdown) {
            world.setAutoSave(false);
            return;
        }
        if (this.settings == null || !this.settings.guardEnabled || this.maintenance == null) {
            return;
        }
        final HeightSync.Dim mainDim = mainDimension(world);
        if (mainDim != null) {
            this.activeHeights.put(mainDim, new int[]{world.getMinHeight(), world.getMaxHeight()});
        }
        if (this.maintenance.biomesChanged) {
            // Chunks loaded now would lose their custom biomes (replaced with a default when saved).
            this.lockdown = true;
            for (World w : getServer().getWorlds()) {
                w.setAutoSave(false);
            }
            world.setAutoSave(false);
            final String line = "=".repeat(78);
            getLogger().severe(line);
            getLogger().severe("The custom biome data pack (" + BiomeSync.PACK_NAME + ", " + this.maintenance.biomesRegistered
                + " biomes) was just installed/updated.");
            getLogger().severe("It only takes effect on the next start; chunks loaded now would have those biomes");
            getLogger().severe("replaced with a default one. Saving is OFF for every world and the server is stopping");
            getLogger().severe("WITHOUT saving anything. Start the server again.");
            getLogger().severe(line);
            getServer().shutdown();
            return;
        }
        final HeightProbe.Result stored = this.maintenance.probe(regionDir(world));
        if (stored == null) {
            return;
        }
        if (stored.minY() >= world.getMinHeight() && stored.maxY() <= world.getMaxHeight()) {
            return;
        }
        this.lockdown = true;
        for (World w : getServer().getWorlds()) {
            w.setAutoSave(false);
        }
        world.setAutoSave(false);

        // Does this world use a vanilla dimension type (whose height the data pack controls)?
        HeightSync.Dim dim = mainDim;
        if (dim == null) {
            final HeightSync.Dim byEnv = dimensionOf(world.getEnvironment());
            final int[] active = byEnv == null ? null : this.activeHeights.get(byEnv);
            if (active != null && active[0] == world.getMinHeight() && active[1] == world.getMaxHeight()) {
                dim = byEnv;
            }
        }
        final List<String> msg = new ArrayList<>();
        msg.add("World '" + world.getName() + "' loaded with height Y " + world.getMinHeight() + " to " + world.getMaxHeight()
            + ", but its saved chunks use Y " + stored.minY() + " to " + stored.maxY() + ".");
        if (dim != null && this.maintenance.planned(dim) != null) {
            final HeightSync.Heights planned = this.maintenance.planned(dim);
            if (planned.minY() <= stored.minY() && planned.maxY() >= stored.maxY()) {
                msg.add("The height data pack already sets " + dim.file + " to " + planned + "; it was installed/updated");
                msg.add("on this start and only takes effect on the next one.");
            } else {
                try {
                    final HeightSync.Heights raised = this.maintenance.raise(dim, stored.minY(), stored.maxY());
                    getConfig().set("heights.raised." + dim.file + ".min-y", raised.minY());
                    getConfig().set("heights.raised." + dim.file + ".max-y", raised.maxY());
                    saveConfig();
                    msg.add("ChunkFence raised the " + dim.file + " height to " + raised + ".");
                    msg.add("Saved to config.yml (heights.raised." + dim.file + ") and to the height data pack. This applies to");
                    msg.add("every world that uses the " + dim.file + " dimension type.");
                } catch (IOException | RuntimeException e) {
                    msg.add("Could not raise the " + dim.file + " height automatically: " + e.getMessage());
                    msg.add("Set it under 'heights' in config.yml.");
                }
            }
        } else {
            msg.add("This world uses its own dimension type (from its generator plugin), not a vanilla one, so ChunkFence");
            msg.add("cannot change its height. Install or fix the plugin/data pack that defines that dimension type.");
        }
        msg.add("Saving is OFF for every world and the server is stopping WITHOUT saving anything.");
        msg.add("Start the server again" + (dim != null ? " - it will load with the new height." : " after fixing the above."));
        final String line = "=".repeat(78);
        getLogger().severe(line);
        msg.forEach(getLogger()::severe);
        getLogger().severe(line);
        getServer().shutdown();
    }

    boolean lockedDown() {
        return this.lockdown;
    }

    @Override
    public void onEnable() {
        if (this.settings == null) {
            saveDefaultConfig();
            this.settings = new Settings(getConfig());
        }
        if (this.workers == null) {
            startExecutors();
        }
        this.listener = new FenceListener(this);
        getServer().getPluginManager().registerEvents(this.listener, this);
        for (World world : getServer().getWorlds()) {
            setupWorld(world, false);
        }
    }

    @Override
    public void onDisable() {
        if (this.coordinator != null) {
            this.coordinator.shutdownNow();
        }
        if (this.workers != null) {
            this.workers.shutdownNow();
        }
        this.fences.clear();
    }

    private void startExecutors() {
        this.workers = Executors.newFixedThreadPool(this.settings.threads, daemonFactory("ChunkFence-scan"));
        this.coordinator = Executors.newSingleThreadExecutor(daemonFactory("ChunkFence-index"));
    }

    private static ThreadFactory daemonFactory(String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return r -> {
            final Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }

    Settings settings() {
        return this.settings;
    }

    /** Main thread. Returns the allowed-chunk set for a world, or null if movement there is unrestricted. */
    public ChunkSet allowed(World world) {
        final WorldFence f = this.fences.get(world);
        return f == null ? null : f.allowed;
    }

    void forgetWorld(World world) {
        this.fences.remove(world);
    }

    // ------------------------------------------------------------------ indexing

    static Path regionDir(World world) {
        final Path base = world.getWorldFolder().toPath();
        final List<Path> candidates = new ArrayList<>(2);
        switch (world.getEnvironment()) {
            case NETHER -> candidates.add(base.resolve("DIM-1").resolve("region"));
            case THE_END -> candidates.add(base.resolve("DIM1").resolve("region"));
            default -> { }
        }
        candidates.add(base.resolve("region"));
        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return candidates.get(0);
    }

    private Path snapshotFile(WorldFence f) {
        return getDataFolder().toPath().resolve("snapshots").resolve(f.uid + ".cfs");
    }

    /** Which fence a world gets, or null for none. */
    WorldFence.Mode modeFor(World world) {
        if (this.settings.isDisabled(world.getName())) {
            return null;
        }
        if (world.getGenerator() instanceof VoidGenerator || this.settings.isListedFrozen(world.getName())) {
            return WorldFence.Mode.FROZEN;
        }
        return this.settings.liveEnabled ? WorldFence.Mode.LIVE : null;
    }

    /** Main thread. */
    public Object fenceState(World world) {
        return this.fences.get(world);
    }

    WorldFence fence(World world) {
        return this.fences.get(world);
    }

    /** Main thread. */
    void setupWorld(World world, boolean forceRescan) {
        final WorldFence.Mode mode = modeFor(world);
        if (mode == null) {
            this.fences.remove(world);
            return;
        }
        WorldFence fence = this.fences.get(world);
        if (fence == null || fence.mode != mode) {
            fence = new WorldFence(world.getUID(), world.getName(), mode);
            this.fences.put(world, fence);
        }
        if (mode == WorldFence.Mode.LIVE) {
            getLogger().info(world.getName() + ": live fence (players wait at chunks that are not generated/loaded yet)");
            return;
        }
        final Path snap = snapshotFile(fence);
        if (!forceRescan && Files.isRegularFile(snap)) {
            try {
                final ChunkSet set = readSnapshot(snap);
                fence.generation++;
                fence.indexing = false;
                applySet(world, fence, set, "snapshot");
                return;
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Snapshot for " + world.getName() + " is unreadable, re-indexing", e);
            }
        }
        index(world, fence);
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator();
    }

    private void index(World world, WorldFence fence) {
        final Path dir = regionDir(world);
        final int gen = ++fence.generation;
        final RegionScanner.Options options = this.settings.scanOptions(world.getEnvironment());

        // Header pass on the main thread: 4 KiB per region file, so even big worlds take well under a second.
        // It is a superset of the final result and fences the world right away while the deep pass runs.
        final ChunkSet provisional;
        try {
            provisional = RegionScanner.scanHeaders(dir);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot read region folder " + dir + " - world " + world.getName() + " is NOT fenced", e);
            fence.allowed = null;
            return;
        }
        if (provisional.chunkCount() == 0) {
            getLogger().warning("World " + world.getName() + " has no generated chunks in " + dir + " - using the live fence until /chunkfence rescan");
            fence.allowed = null;
            fence.indexing = false;
            return;
        }
        fence.allowed = provisional;
        if (!options.needsDeepScan()) {
            fence.indexing = false;
            applySet(world, fence, provisional, "region headers");
            saveSnapshotAsync(fence, provisional);
            return;
        }
        fence.indexing = true;
        getLogger().info("Indexing " + world.getName() + " (" + provisional.chunkCount() + " chunks in "
            + provisional.regionCount() + " region files) in the background...");
        final RegionScanner.Stats stats = new RegionScanner.Stats();
        final long start = System.nanoTime();
        this.coordinator.execute(() -> {
            final ChunkSet deep;
            try {
                deep = RegionScanner.scanDeep(dir, options, this.workers, stats);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "Indexing " + fence.name + " failed; keeping the header-based fence", e);
                runSync(() -> {
                    if (fence.generation == gen) {
                        fence.indexing = false;
                    }
                });
                return;
            }
            final long ms = (System.nanoTime() - start) / 1_000_000;
            runSync(() -> {
                if (fence.generation != gen || this.fences.get(world) != fence) {
                    return; // superseded by a rescan/reload or world unloaded
                }
                fence.indexing = false;
                getLogger().info("Indexed " + fence.name + " in " + ms + " ms: kept " + stats.kept + " of "
                    + stats.present + " stored chunks (" + stats.notFull + " partially generated, "
                    + stats.empty + " empty/void, " + stats.unreadable + " unreadable kept, "
                    + stats.uninspectable + " custom-compressed kept)");
                if (deep.chunkCount() == 0) {
                    getLogger().warning("No usable chunks found in " + fence.name + " - using the live fence");
                    fence.allowed = null;
                    return;
                }
                applySet(world, fence, deep, "index");
                saveSnapshotAsync(fence, deep);
            });
        });
    }

    private void applySet(World world, WorldFence fence, ChunkSet set, String source) {
        if (set.chunkCount() == 0) {
            fence.allowed = null;
            getLogger().warning("Snapshot for " + world.getName() + " is empty - using the live fence");
            return;
        }
        fence.allowed = set;
        if (!source.equals("index")) {
            getLogger().info("Fenced " + world.getName() + ": " + set.chunkCount() + " chunks allowed (from " + source + ")");
        }
        for (Player p : world.getPlayers()) {
            this.listener.checkAndRescue(p);
        }
    }

    private void runSync(Runnable r) {
        if (isEnabled()) {
            try {
                getServer().getScheduler().runTask(this, r);
            } catch (RuntimeException ignored) {
                // plugin disabled between the check and the call
            }
        }
    }

    private void saveSnapshotAsync(WorldFence fence, ChunkSet set) {
        final Path file = snapshotFile(fence);
        this.coordinator.execute(() -> {
            try {
                writeSnapshot(file, fence.name, set);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not save snapshot " + file, e);
            }
        });
    }

    static void writeSnapshot(Path file, String worldName, ChunkSet set) throws IOException {
        Files.createDirectories(file.getParent());
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeUTF(worldName);
            set.write(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static ChunkSet readSnapshot(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            in.readUTF();
            return ChunkSet.read(in);
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage("§6ChunkFence worlds:");
                for (World w : getServer().getWorlds()) {
                    final WorldFence f = this.fences.get(w);
                    final String state;
                    if (f == null) {
                        state = "§7off";
                    } else if (f.mode == WorldFence.Mode.LIVE || f.allowed == null) {
                        state = "§blive§7 (only fully generated, loaded chunks)";
                    } else if (f.indexing) {
                        state = "§eindexing§7 (provisional " + f.allowed.chunkCount() + " chunks)";
                    } else {
                        state = "§afrozen§7 (" + f.allowed.chunkCount() + " chunks, " + f.allowed.regionCount() + " regions)";
                    }
                    sender.sendMessage("§f " + w.getName() + "§7: " + state);
                }
            }
            case "check" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                final Location l = p.getLocation();
                final int cx = l.getBlockX() >> 4;
                final int cz = l.getBlockZ() >> 4;
                final WorldFence f = this.fences.get(p.getWorld());
                if (f == null) {
                    sender.sendMessage("§7ChunkFence is off in this world. Chunk " + cx + ", " + cz + ".");
                } else {
                    sender.sendMessage((this.listener.canEnter(p.getWorld(), cx, cz) ? "§aAllowed" : "§cBlocked")
                        + "§7: chunk " + cx + ", " + cz + " (" + (f.mode == WorldFence.Mode.FROZEN && f.allowed != null ? "frozen" : "live") + ")"
                        + (p.hasPermission(FenceListener.BYPASS) ? ", you have bypass" : ""));
                }
            }
            case "rescan" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /" + label + " rescan <world>");
                    return true;
                }
                final World w = getServer().getWorld(args[1]);
                if (w == null) {
                    sender.sendMessage("§cUnknown world " + args[1]);
                    return true;
                }
                if (modeFor(w) != WorldFence.Mode.FROZEN) {
                    sender.sendMessage("§c" + w.getName() + " is not a frozen world (not on the ChunkFence generator and not in frozen-worlds)");
                    return true;
                }
                setupWorld(w, true);
                sender.sendMessage("§aRe-indexing " + w.getName() + " from its current region files. Watch the console for the result.");
            }
            case "reload" -> {
                reloadConfig();
                final Settings old = this.settings;
                this.settings = new Settings(getConfig());
                if (old.threads != this.settings.threads) {
                    this.workers.shutdown();
                    this.workers = Executors.newFixedThreadPool(this.settings.threads, daemonFactory("ChunkFence-scan"));
                }
                for (World w : getServer().getWorlds()) {
                    final WorldFence current = this.fences.get(w);
                    final WorldFence.Mode wanted = modeFor(w);
                    if (wanted == null) {
                        this.fences.remove(w);
                    } else if (current == null || current.mode != wanted) {
                        setupWorld(w, false);
                    }
                }
                sender.sendMessage("§aChunkFence config reloaded. Existing snapshots were kept; use /" + label + " rescan <world> to rebuild one.");
            }
            default -> sender.sendMessage("§cUsage: /" + label + " <status|check|rescan <world>|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        final List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : new String[]{"status", "check", "rescan", "reload"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rescan")) {
            for (World w : getServer().getWorlds()) {
                if (w.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(w.getName());
                }
            }
        }
        return out;
    }
}
