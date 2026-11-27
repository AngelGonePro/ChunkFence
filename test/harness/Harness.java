package harness;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.cosmoscraft.chunkfence.ChunkFencePlugin;
import net.cosmoscraft.chunkfence.ChunkSet;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the compiled plugin through a fake server that follows Bukkit's dispatch rules:
 * per-event-class handler lists, priority order, ignoreCancelled, and "cancelled move = player
 * stays at 'from'".
 */
public final class Harness {

    // ------------------------------------------------------------------ fake server

    static class FakeScheduler implements BukkitScheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        public synchronized BukkitTask runTask(Plugin p, Runnable r) { queue.add(r); return new BukkitTask() { }; }
        void pump() { while (true) { Runnable r; synchronized (this) { r = queue.poll(); } if (r == null) return; r.run(); } }
    }

    static class FakePM implements PluginManager {
        record H(Object owner, Method m, org.bukkit.event.EventPriority prio, boolean ignoreCancelled) { }
        final Map<Class<?>, List<H>> handlers = new HashMap<>();
        public void registerEvents(Listener l, Plugin p) {
            for (Method m : l.getClass().getDeclaredMethods()) {
                EventHandler eh = m.getAnnotation(EventHandler.class);
                if (eh == null) continue;
                m.setAccessible(true);
                handlers.computeIfAbsent(m.getParameterTypes()[0], k -> new ArrayList<>()).add(new H(l, m, eh.priority(), eh.ignoreCancelled()));
            }
            for (List<H> hs : handlers.values()) hs.sort(Comparator.comparing(H::prio));
        }
        <T extends Event> T call(T e) {
            for (H h : handlers.getOrDefault(e.getClass(), List.of())) {
                if (h.ignoreCancelled && e instanceof Cancellable c && c.isCancelled()) continue;
                try { h.m.invoke(h.owner, e); } catch (Exception ex) { throw new RuntimeException(ex); }
            }
            return e;
        }
    }

    static class FakeServer implements Server {
        final FakePM pm = new FakePM(); final FakeScheduler sched = new FakeScheduler(); final List<World> worlds = new ArrayList<>();
        public PluginManager getPluginManager() { return pm; }
        public List<World> getWorlds() { return worlds; }
        public World getWorld(String n) { for (World w : worlds) if (w.getName().equals(n)) return w; return null; }
        public BukkitScheduler getScheduler() { return sched; }
        java.io.File container = new java.io.File("."); boolean shutdownCalled;
        public java.io.File getWorldContainer() { return container; }
        public String getMinecraftVersion() { return "1.21.6"; }
        public String getBukkitVersion() { return "1.21.6-R0.1-SNAPSHOT"; }
        public void shutdown() { shutdownCalled = true; }
    }

    static class FakeWorld implements World {
        final String name; final UUID uid = UUID.randomUUID(); final Path folder; final Environment env; final List<Player> players = new ArrayList<>();
        Location spawn; org.bukkit.generator.ChunkGenerator generator; final Set<Long> loaded = new HashSet<>();
        public boolean isChunkLoaded(int x, int z) { return loaded.contains(((long) x << 32) | (z & 0xFFFFFFFFL)); }
        void load(int x, int z) { loaded.add(((long) x << 32) | (z & 0xFFFFFFFFL)); }
        public org.bukkit.generator.ChunkGenerator getGenerator() { return generator; }
        int minHeight = -64, maxHeight = 320; Boolean autoSave;
        public int getMinHeight() { return minHeight; } public int getMaxHeight() { return maxHeight; }
        public void setAutoSave(boolean v) { autoSave = v; }
        FakeWorld(String n, Path f, Environment e) { name = n; folder = f; env = e; spawn = new Location(this, 8, 64, 8); }
        public String getName() { return name; } public UUID getUID() { return uid; }
        public java.io.File getWorldFolder() { return folder.toFile(); } public Environment getEnvironment() { return env; }
        public List<Player> getPlayers() { return players; } public int getHighestBlockYAt(int x, int z) { return 70; }
        public Location getSpawnLocation() { return spawn; }
    }

    static class FakeEntity implements Entity {
        Location loc; final UUID id = UUID.randomUUID(); final List<Entity> passengers = new ArrayList<>(); FakeEntity vehicle;
        public Location getLocation() { return new Location(loc.world, loc.x, loc.y, loc.z, loc.yaw, loc.pitch); }
        public World getWorld() { return loc.world; } public UUID getUniqueId() { return id; }
        public boolean teleport(Location l) { if (!passengers.isEmpty()) return false; loc = l; return true; }
        public List<Entity> getPassengers() { return new ArrayList<>(passengers); }
        public boolean eject() { boolean any = !passengers.isEmpty(); for (Entity e : passengers) ((FakeEntity) e).vehicle = null; passengers.clear(); return any; }
        public boolean isInsideVehicle() { return vehicle != null; }
        public boolean leaveVehicle() { if (vehicle == null) return false; vehicle.passengers.remove(this); vehicle = null; return true; }
    }

    static final class FakeVehicle extends FakeEntity implements Vehicle { }

    final class FakePlayer extends FakeEntity implements Player {
        final String name; final Set<String> perms = new HashSet<>(); final List<String> chat = new ArrayList<>(), bar = new ArrayList<>(); boolean online = true;
        FakePlayer(String n, Location l) { name = n; loc = l; }
        public void sendMessage(String m) { chat.add(m); } public boolean hasPermission(String p) { return perms.contains(p); }
        public String getName() { return name; } public boolean isOnline() { return online; } public void sendActionBar(String m) { bar.add(m); }
        /** Paper: a cancelled PlayerMoveEvent leaves the player at 'from'. */
        boolean move(Location to) {
            PlayerMoveEvent e = server.pm.call(new PlayerMoveEvent(this, getLocation(), to));
            if (!e.isCancelled()) loc = e.getTo();
            server.sched.pump();
            return !e.isCancelled();
        }
        @Override public boolean teleport(Location to) {
            if (vehicle != null) leaveVehicle();
            World before = loc.world;
            PlayerTeleportEvent e = server.pm.call(new PlayerTeleportEvent(this, getLocation(), to));
            if (e.isCancelled()) return false;
            loc = e.getTo();
            if (before != loc.world) { ((FakeWorld) before).players.remove(this); ((FakeWorld) loc.world).players.add(this); server.pm.call(new PlayerChangedWorldEvent(this)); }
            return true;
        }
    }

    // ------------------------------------------------------------------ test plumbing

    final FakeServer server = new FakeServer();
    int passed, failed;

    void check(boolean ok, String what) {
        if (ok) { passed++; System.out.println("  PASS " + what); } else { failed++; System.out.println("  FAIL " + what); }
    }

    static Location at(World w, int chunkX, int chunkZ) { return new Location(w, (chunkX << 4) + 8.5, 70, (chunkZ << 4) + 8.5, 0, 0); }

    ChunkFencePlugin enable(Path data) throws Exception {
        JavaPlugin.SERVER = server; JavaPlugin.DATA = data.toFile();
        ChunkFencePlugin p = new ChunkFencePlugin();
        p.enabled = true;
        p.onEnable();
        return p;
    }

    void waitIndexed(ChunkFencePlugin p) throws Exception {
        // indexing finishes on a background thread and hands the result to the main thread via runTask
        for (int i = 0; i < 600; i++) {
            server.sched.pump();
            FakePlayer probe = new FakePlayer("probe", new Location(server.worlds.get(0), 0, 0, 0));
            p.onCommand(probe, null, "chunkfence", new String[]{"status"});
            if (probe.chat.stream().noneMatch(s -> s.contains("indexing"))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("indexing did not finish");
    }

    static Set<String> readExpected(Path file) throws Exception {
        Set<String> s = new HashSet<>();
        for (String l : Files.readAllLines(file)) if (!l.isBlank()) s.add(l.trim());
        return s;
    }

    public static void main(String[] args) throws Exception {
        new Harness().run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
    }

    void run(Path worlds, Path expectedFile, Path defaultConfig) throws Exception {
        JavaPlugin.DEFAULT_CONFIG = defaultConfig;
        Path data = Files.createTempDirectory("cf-data");
        Files.createDirectories(data);
        // config: default + exclude new_world
        String cfg = Files.readString(defaultConfig).replace("excluded-worlds: []", "excluded-worlds: [new_world]");
        Files.writeString(data.resolve("config.yml"), cfg);

        String cfg2 = Files.readString(data.resolve("config.yml")).replace("frozen-worlds: []", "frozen-worlds: [frozen_by_config]").replace("disabled-worlds: []", "disabled-worlds: [disabled_world]");
        Files.writeString(data.resolve("config.yml"), cfg2);
        FakeWorld old = new FakeWorld("old_world", worlds.resolve("old_world"), World.Environment.NORMAL);
        FakeWorld nether = new FakeWorld("old_world_nether", worlds.resolve("old_world_nether"), World.Environment.NETHER);
        FakeWorld neu = new FakeWorld("new_world", worlds.resolve("new_world"), World.Environment.NORMAL);
        FakeWorld fresh = new FakeWorld("fresh", worlds.resolve("fresh"), World.Environment.NORMAL);
        FakeWorld byCfg = new FakeWorld("frozen_by_config", worlds.resolve("old_world_nether"), World.Environment.NETHER);
        FakeWorld disabled = new FakeWorld("disabled_world", worlds.resolve("new_world"), World.Environment.NORMAL);

        System.out.println("== load: STARTUP - plugin enables before worlds, provides the void generator");
        long t0 = System.nanoTime();
        ChunkFencePlugin plugin = enable(data);
        // bukkit.yml "generator: ChunkFence" -> server asks the plugin for the generator, then loads the world
        old.generator = plugin.getDefaultWorldGenerator("old_world", null);
        nether.generator = plugin.getDefaultWorldGenerator("old_world_nether", "");
        fresh.generator = plugin.getDefaultWorldGenerator("fresh", null);
        check(old.generator instanceof net.cosmoscraft.chunkfence.VoidGenerator, "plugin hands out its void generator");
        var biomes = old.generator.getDefaultBiomeProvider(null);
        check(biomes != null && biomes.getBiomes(null).size() == 1, "void generator supplies a single-biome (the_void) provider");
        for (FakeWorld w : List.of(old, nether, neu, fresh, byCfg, disabled)) { server.worlds.add(w); server.pm.call(new WorldLoadEvent(w)); }
        long tEnable = System.nanoTime() - t0;
        check(plugin.allowed(old) != null, "old_world fenced immediately from region headers (" + tEnable / 1_000_000 + " ms incl. world loads)");
        waitIndexed(plugin);
        System.out.println("  index done after " + (System.nanoTime() - t0) / 1_000_000 + " ms");

        Set<String> expected = readExpected(expectedFile);
        for (FakeWorld w : List.of(old, nether)) {
            ChunkSet set = plugin.allowed(w);
            int mismatches = 0, allowedCount = 0;
            for (int cx = -96; cx < 96; cx++) for (int cz = -96; cz < 96; cz++) {
                boolean exp = expected.contains(w.name + " " + cx + " " + cz);
                boolean got = set != null && set.contains(cx, cz);
                if (got) allowedCount++;
                if (exp != got) { if (mismatches++ < 5) System.out.println("    mismatch " + w.name + " " + cx + "," + cz + " expected=" + exp); }
            }
            check(mismatches == 0, w.name + ": allowed set equals Python reference (" + allowedCount + " chunks, region coords -3..2)");
        }
        check(plugin.allowed(neu) == null && plugin.fenceState(neu) != null, "new_world (normal generator) gets the live fence, no config needed");
        check(plugin.allowed(fresh) == null && plugin.fenceState(fresh) != null, "void world without region files falls back to the live fence");
        check(plugin.allowed(byCfg) != null && plugin.allowed(byCfg).chunkCount() == 1024, "world listed in frozen-worlds is frozen even without the generator");
        check(plugin.fenceState(disabled) == null, "disabled-worlds are left alone");
        Path snap = data.resolve("snapshots").resolve(old.uid + ".cfs");
        for (int i = 0; i < 50 && !Files.exists(snap); i++) Thread.sleep(50);
        check(Files.exists(snap), "snapshot written to " + data.relativize(snap));

        // pick concrete chunks from the reference
        ChunkSet oldSet = plugin.allowed(old);
        int[] inside = null, edgeOut = null;
        // find an allowed chunk with a disallowed +X neighbour that is NOT in the file (true edge)
        outer:
        for (int cx = -40; cx < 70; cx++) for (int cz = 0; cz < 32; cz++)
            if (oldSet.contains(cx, cz) && !oldSet.contains(cx + 1, cz)) { inside = new int[]{cx, cz}; edgeOut = new int[]{cx + 1, cz}; break outer; }
        // a partially generated chunk (present in file, excluded): r.-1.0 index 7 => chunk (-25, 0); void chunk index 4 => (-28, 0)
        System.out.println("== movement (edge chunk " + inside[0] + "," + inside[1] + " -> " + edgeOut[0] + "," + edgeOut[1] + ")");

        FakePlayer p = new FakePlayer("Steve", at(old, inside[0], inside[1]));
        old.players.add(p);
        server.pm.call(new PlayerJoinEvent(p)); server.sched.pump();
        check(p.loc.getBlockX() >> 4 == inside[0], "player joining inside the fence is not moved");

        Location before = p.getLocation();
        check(p.move(new Location(old, before.x + 1, 70, before.z, 0, 0)), "move within the same chunk is allowed");
        Location edge = new Location(old, ((inside[0] + 1) << 4) - 0.3, 70, before.z, 0, 0);
        p.loc = edge;
        boolean moved = p.move(new Location(old, ((inside[0] + 1) << 4) + 0.2, 70, before.z, 0, 0));
        check(!moved && (p.loc.getBlockX() >> 4) == inside[0], "crossing into a chunk outside the fence is cancelled, player stays in previous chunk");
        check(p.bar.size() == 1 && p.bar.get(0).contains("can't go any further"), "player gets the blocked message on the action bar");
        p.move(new Location(old, ((inside[0] + 1) << 4) + 0.2, 70, before.z, 0, 0));
        check(p.bar.size() == 1, "message cooldown prevents spam");

        p.loc = at(old, -27, 0);
        check(!p.move(at(old, -28, 0)), "void (all-air) chunk is outside the fence");
        p.loc = at(old, -26, 0);
        check(!p.move(at(old, -25, 0)), "partially generated chunk (status noise) is outside the fence");
        p.loc = at(old, -30, 0);
        check(p.move(at(old, -29, 0)), "oversized chunk stored in .mcc is inside the fence");
        p.loc = at(old, -32, 0);
        check(p.move(at(old, -31, 0)) && p.move(at(old, -30, 0)), "gzip and uncompressed chunks are inside the fence");
        p.loc = at(old, -26, 0);
        check(p.move(at(old, -27, 0)), "lz4 chunk is inside the fence");
        check(oldSet.contains(-26, 0), "unreadable chunk is kept (fail-open) rather than fenced off");

        System.out.println("== bypass");
        p.loc = edge; p.perms.add("chunkfence.bypass");
        check(p.move(new Location(old, ((inside[0] + 1) << 4) + 0.2, 70, before.z, 0, 0)), "chunkfence.bypass can leave the fence");
        p.perms.clear(); p.loc = at(old, inside[0], inside[1]);

        System.out.println("== teleports");
        check(!p.teleport(at(old, edgeOut[0], edgeOut[1])) && (p.loc.getBlockX() >> 4) == inside[0], "teleport to a chunk outside the fence is cancelled");
        check(p.teleport(at(old, inside[0], inside[1] )), "teleport inside the fence works");
        check(p.teleport(at(neu, 500, 500)), "teleport anywhere in a live world works (server loads the target)");
        check(!p.teleport(at(nether, 200, 200)), "teleport into a fenced nether outside its chunks is cancelled");
        check(p.teleport(at(nether, 5, 5)), "teleport into an existing nether chunk works");
        PlayerPortalEvent pe = server.pm.call(new PlayerPortalEvent(p, p.getLocation(), at(nether, 300, 300)));
        check(pe.isCancelled(), "nether portal whose search point is outside the fence is cancelled (before any search/generation)");
        pe = server.pm.call(new PlayerPortalEvent(p, p.getLocation(), at(nether, 3, 3)));
        check(!pe.isCancelled(), "nether portal into existing nether chunks is allowed");
        org.bukkit.event.entity.EntityPortalEvent ee = server.pm.call(new org.bukkit.event.entity.EntityPortalEvent(at(nether, 300, 300)));
        check(ee.isCancelled(), "item/mob portal travel to outside the fence is cancelled");
        ee = server.pm.call(new org.bukkit.event.entity.EntityPortalEvent(at(neu, 300, 300)));
        check(!ee.isCancelled(), "entity portal travel into a live world is untouched");

        System.out.println("== rescue");
        FakePlayer q = new FakePlayer("Alex", at(old, edgeOut[0] + 3, edgeOut[1]));
        old.players.add(q);
        server.pm.call(new PlayerJoinEvent(q)); server.sched.pump();
        int qx = q.loc.getBlockX() >> 4, qz = q.loc.getBlockZ() >> 4;
        check(oldSet.contains(qx, qz), "player who logs in outside the fence is moved to an allowed chunk (" + qx + "," + qz + ")");
        check(Math.abs(qx - (edgeOut[0] + 3)) <= 8, "... and it is a nearby one");
        check(q.bar.stream().anyMatch(s -> s.contains("moved back")), "... and is told about it");
        FakePlayer r = new FakePlayer("Rex", at(old, 5000, 5000));
        old.players.add(r);
        server.pm.call(new PlayerJoinEvent(r)); server.sched.pump();
        if (oldSet.contains(0, 0)) check(r.loc.getBlockX() == 8 && r.loc.getBlockZ() == 8, "nothing within search radius -> sent to world spawn (allowed)");
        else check(r.loc.getBlockX() >> 4 == 5000, "nothing within radius and spawn outside fence -> left in place (logged)");
        PlayerRespawnEvent re = server.pm.call(new PlayerRespawnEvent(q, at(old, edgeOut[0] + 2, edgeOut[1])));
        Location rl = re.getRespawnLocation();
        check(oldSet.contains(rl.getBlockX() >> 4, rl.getBlockZ() >> 4), "respawn point outside the fence is replaced with an allowed one");

        System.out.println("== vehicles");
        FakeVehicle boat = new FakeVehicle();
        Location vFrom = new Location(old, ((inside[0] + 1) << 4) - 0.3, 63, before.z, 0, 0);
        Location vTo = new Location(old, ((inside[0] + 1) << 4) + 0.3, 63, before.z, 0, 0);
        boat.loc = vTo; p.loc = vTo; boat.passengers.add(p); p.vehicle = boat;
        server.pm.call(new VehicleMoveEvent(boat, vFrom, vTo));
        check(boat.passengers.isEmpty() && (boat.loc.getBlockX() >> 4) == inside[0] && (p.loc.getBlockX() >> 4) == inside[0],
            "riding over the edge: rider dismounted, boat and rider put back in the previous chunk");
        FakeVehicle cart = new FakeVehicle(); cart.loc = vTo;
        server.pm.call(new VehicleMoveEvent(cart, vFrom, vTo));
        check(cart.loc == vTo, "empty vehicles are left alone");

        System.out.println("== live world (new Iris world)");
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 2; z++) neu.load(x, z);
        FakePlayer l = new FakePlayer("Live", at(neu, 0, 0)); neu.players.add(l);
        server.pm.call(new PlayerJoinEvent(l)); server.sched.pump();
        check(l.loc.getBlockX() >> 4 == 0, "joining a live world does not move the player");
        check(l.move(at(neu, 1, 0)), "walking into a generated, loaded chunk is allowed");
        Location lEdge = new Location(neu, (2 << 4) - 0.2, 70, 8.5, 0, 0); l.loc = lEdge;
        check(!l.move(new Location(neu, (2 << 4) + 0.2, 70, 8.5, 0, 0)) && (l.loc.getBlockX() >> 4) == 1, "walking into a chunk that is not generated/loaded yet is cancelled, player stays in previous chunk");
        check(l.bar.stream().anyMatch(m -> m.contains("still generating")), "player is told the area is still generating");
        neu.load(2, 0);
        check(l.move(new Location(neu, (2 << 4) + 0.2, 70, 8.5, 0, 0)), "once the server has generated/loaded it, the player can go in");
        FakeVehicle horse = new FakeVehicle(); Location hf = new Location(neu, (3 << 4) - 0.2, 70, 8.5, 0, 0), ht = new Location(neu, (3 << 4) + 0.2, 70, 8.5, 0, 0);
        horse.loc = ht; l.loc = ht; horse.passengers.add(l); l.vehicle = horse;
        server.pm.call(new VehicleMoveEvent(horse, hf, ht));
        check((horse.loc.getBlockX() >> 4) == 2 && (l.loc.getBlockX() >> 4) == 2, "riding into an ungenerated chunk puts horse and rider back");
        l.perms.add("chunkfence.bypass"); l.loc = lEdge; neu.loaded.remove((2L << 32));
        check(l.move(new Location(neu, (2 << 4) + 0.2, 70, 8.5, 0, 0)), "bypass also works in live worlds");

        System.out.println("== commands");
        FakePlayer admin = new FakePlayer("Angel", at(old, inside[0], inside[1]));
        plugin.onCommand(admin, null, "chunkfence", new String[]{"status"});
        System.out.println("    " + String.join("\n    ", admin.chat));
        check(admin.chat.stream().anyMatch(s -> s.contains("old_world") && s.contains("frozen")), "/chunkfence status lists fenced worlds");
        admin.chat.clear();
        plugin.onCommand(admin, null, "chunkfence", new String[]{"check"});
        check(admin.chat.get(0).contains("Allowed"), "/chunkfence check reports the current chunk");
        check(plugin.onTabComplete(admin, null, "chunkfence", new String[]{"re"}).containsAll(List.of("rescan", "reload")), "tab completion");

        System.out.println("== restart uses the snapshot (no rescan)");
        plugin.onDisable();
        // break the region folder: if the plugin re-scanned instead of loading the snapshot, results would differ
        Path movedDir = worlds.resolve("old_world/region_moved");
        Files.move(worlds.resolve("old_world/region"), movedDir);
        server.pm.handlers.clear();
        ChunkFencePlugin plugin2 = enable(data); // worlds already loaded: onEnable picks them up
        server.sched.pump();
        ChunkSet reloaded = plugin2.allowed(old);
        boolean same = reloaded != null && reloaded.chunkCount() == oldSet.chunkCount();
        for (int cx = -96; same && cx < 96; cx++) for (int cz = -96; cz < 96; cz++) if (reloaded.contains(cx, cz) != oldSet.contains(cx, cz)) { same = false; break; }
        check(same, "after restart the fence comes from the saved snapshot and is identical (" + (reloaded == null ? 0 : reloaded.chunkCount()) + " chunks)");
        Files.move(movedDir, worlds.resolve("old_world/region"));

        System.out.println("== world loaded later (Multiverse style)");
        FakeWorld late = new FakeWorld("late_world", worlds.resolve("old_world_nether/DIM-1").getParent(), World.Environment.NETHER);
        late.generator = plugin2.getDefaultWorldGenerator("late_world", null);
        server.worlds.add(late);
        server.pm.call(new WorldLoadEvent(late));
        waitIndexed(plugin2);
        check(plugin2.allowed(late) != null && plugin2.allowed(late).chunkCount() == 1024, "world loaded after startup gets fenced too");

        System.out.println("== rescan");
        admin.chat.clear();
        plugin2.onCommand(admin, null, "chunkfence", new String[]{"rescan", "old_world"});
        waitIndexed(plugin2);
        check(plugin2.allowed(old).chunkCount() == oldSet.chunkCount(), "/chunkfence rescan rebuilds the same fence from unchanged files");
        plugin2.onDisable();

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
