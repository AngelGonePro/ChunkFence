package harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cosmoscraft.chunkfence.ChunkFencePlugin;
import org.bukkit.World;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Full startup sequence as Paper runs it for a STARTUP plugin:
 * onLoad (recovery + height data pack) -> onEnable -> WorldInitEvent per world (guard) -> WorldLoadEvent.
 * Run with the working directory set to the server folder (server.properties is read from there).
 */
public final class HeightHarness {

    int passed, failed;

    void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    Harness.FakeServer server;

    ChunkFencePlugin boot(Path container, Path data, List<Harness.FakeWorld> worlds) {
        server = new Harness.FakeServer();
        server.container = container.toFile();
        JavaPlugin.SERVER = server;
        JavaPlugin.DATA = data.toFile();
        final ChunkFencePlugin p = new ChunkFencePlugin();
        p.enabled = true;
        p.onLoad();
        p.onEnable();
        for (Harness.FakeWorld w : worlds) {
            if (w.name.endsWith("_nether") || w.name.endsWith("_the_end") || w.name.equals("main") || w.name.equals("new_world")) {
                w.generator = null;
            }
            server.worlds.add(w);
            server.pm.call(new WorldInitEvent(w));
            if (!server.shutdownCalled) {
                server.pm.call(new WorldLoadEvent(w));
            }
        }
        server.sched.pump();
        return p;
    }

    public static void main(String[] args) throws Exception {
        new HeightHarness().run(Path.of(args[0]), Path.of(args[1]));
    }

    void run(Path container, Path defaultConfig) throws Exception {
        JavaPlugin.DEFAULT_CONFIG = defaultConfig;
        final Path data = container.resolve("plugins/ChunkFence");
        final Path pack = container.resolve("main/datapacks/chunkfence-heights");

        System.out.println("== first start after the Iris update (worlds still load at vanilla height)");
        Harness.FakeWorld main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        Harness.FakeWorld nether = new Harness.FakeWorld("main_nether", container.resolve("main_nether"), World.Environment.NETHER);
        Harness.FakeWorld newWorld = new Harness.FakeWorld("new_world", container.resolve("new_world"), World.Environment.NORMAL);
        nether.minHeight = 0;
        nether.maxHeight = 256;
        boot(container, data, List.of(main, nether, newWorld));

        check(Files.isRegularFile(pack.resolve("pack.mcmeta")), "height data pack written to main/datapacks/chunkfence-heights");
        final String ow = Files.readString(pack.resolve("data/minecraft/dimension_type/overworld.json"));
        check(ow.contains("\"min_y\": -64") && ow.contains("\"height\": 1088") && ow.contains("\"logical_height\": 1024"),
            "overworld set to the saved/Iris height: min_y -64, height 1088, logical 1024");
        check(server.shutdownCalled, "guard stopped the server because 'main' loaded shorter than its chunks");
        check(Boolean.FALSE.equals(main.autoSave), "saving switched off for 'main' before any chunk was loaded");
        check(Boolean.FALSE.equals(nether.autoSave) && Boolean.FALSE.equals(newWorld.autoSave), "saving switched off for every other world too");
        check(Files.list(data.resolve("backups")).findAny().isPresent(), "original region file backed up before repair");
        final boolean report = Files.list(data).anyMatch(p -> p.getFileName().toString().startsWith("recovery-report-"));
        check(report, "recovery report written");

        final Path bio = container.resolve("main/datapacks/chunkfence-biomes/data");
        check(Files.isRegularFile(bio.resolve("rrtspecial/worldgen/biome/hot_beach.json")) && Files.isRegularFile(bio.resolve("rrtspecial/worldgen/biome/tropical_plains.json")),
            "custom biomes found in the old world's chunks are registered (rrtspecial:hot_beach, rrtspecial:tropical_plains)");
        check(Files.readString(bio.resolve("rrtspecial/worldgen/biome/hot_beach.json")).contains("\"temperature\": 0.95"), "hot_beach uses the Iris pack's settings (temperature 0.95)");
        check(Files.isRegularFile(bio.resolve("otherpack/worldgen/biome/mystery.json")), "biome with no Iris pack settings is still registered (plains-like)");
        final Path wdp = container.resolve("main/datapacks");
        check(Files.isRegularFile(wdp.resolve("testpack/data/test/function/hi.mcfunction")), "uploaded data pack folder copied into main/datapacks");
        check(Files.isRegularFile(wdp.resolve("zippack.zip")), "uploaded .zip data pack copied into main/datapacks");
        check(Files.isRegularFile(data.resolve("datapacks/README.txt")), "upload folder plugins/ChunkFence/datapacks has a README");

        System.out.println("== second start (data pack now active: main loads with Y -64..1024)");
        main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        main.maxHeight = 1024;
        nether = new Harness.FakeWorld("main_nether", container.resolve("main_nether"), World.Environment.NETHER);
        nether.minHeight = -64;
        nether.maxHeight = 448;
        newWorld = new Harness.FakeWorld("new_world", container.resolve("new_world"), World.Environment.NORMAL);
        final long backupsBefore = Files.list(data.resolve("backups")).count();
        final String owBefore = ow;
        boot(container, data, List.of(main, nether, newWorld));
        check(!server.shutdownCalled, "server keeps running");
        check(main.autoSave == null && nether.autoSave == null, "saving left on");
        check(Files.readString(pack.resolve("data/minecraft/dimension_type/overworld.json")).equals(owBefore), "data pack unchanged on the second start");
        check(Files.list(data.resolve("backups")).count() == backupsBefore, "recovery not run again (done once)");

        System.out.println("== data pack deleted from the upload folder, then changed");
        java.nio.file.Files.delete(data.resolve("datapacks/zippack.zip"));
        Files.writeString(data.resolve("datapacks/testpack/data/test/function/hi.mcfunction"), "say changed\n");
        main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        main.maxHeight = 1024;
        boot(container, data, List.of(main));
        check(!Files.exists(wdp.resolve("zippack.zip")), "deleted upload -> removed from main/datapacks");
        check(Files.readString(wdp.resolve("testpack/data/test/function/hi.mcfunction")).contains("changed"), "changed upload -> updated in main/datapacks");
        check(!server.shutdownCalled, "heights still correct -> server keeps running (data pack change applies next start)");

        System.out.println("== extra world (e.g. Multiverse) whose chunks are taller than the shared overworld height");
        main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        main.maxHeight = 1024;
        Harness.FakeWorld extra = new Harness.FakeWorld("extra", container.resolve("extra"), World.Environment.NORMAL);
        extra.maxHeight = 1024;
        boot(container, data, List.of(main, extra));
        final String cfg = Files.readString(data.resolve("config.yml"));
        check(server.shutdownCalled && Boolean.FALSE.equals(extra.autoSave) && Boolean.FALSE.equals(main.autoSave),
            "stopped without saving");
        check(cfg.contains("raised:") && cfg.replace(" ", "").contains("max-y:\"1280\""), "needed height written to config.yml heights.raised.overworld (max-y 1280)");
        final String ow2 = Files.readString(pack.resolve("data/minecraft/dimension_type/overworld.json"));
        check(ow2.contains("\"height\": 1344") && ow2.contains("\"min_y\": -64"), "height data pack raised to -64..1280 (height 1344)");

        System.out.println("== next start: everything loads with the raised height");
        main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        main.maxHeight = 1280;
        extra = new Harness.FakeWorld("extra", container.resolve("extra"), World.Environment.NORMAL);
        extra.maxHeight = 1280;
        boot(container, data, List.of(main, extra));
        check(!server.shutdownCalled && extra.autoSave == null, "server keeps running, saving on");
        check(Files.readString(pack.resolve("data/minecraft/dimension_type/overworld.json")).equals(ow2), "data pack keeps the raised height (read back from config)");

        System.out.println("== world with its own dimension type (e.g. an Iris world) that is too short");
        main = new Harness.FakeWorld("main", container.resolve("main"), World.Environment.NORMAL);
        main.maxHeight = 1280;
        Harness.FakeWorld custom = new Harness.FakeWorld("iris_world", container.resolve("extra"), World.Environment.NORMAL);
        custom.maxHeight = 512;
        final String cfgBefore = Files.readString(data.resolve("config.yml"));
        boot(container, data, List.of(main, custom));
        check(server.shutdownCalled && Boolean.FALSE.equals(custom.autoSave), "stopped without saving");
        check(Files.readString(data.resolve("config.yml")).equals(cfgBefore), "config not changed (a vanilla height cannot fix a custom dimension type)");

        System.out.println("== a world whose chunks are taller than its height (no data pack can fix it): guard");
        server.shutdownCalled = false;
        Harness.FakeWorld other = new Harness.FakeWorld("tall_custom", container.resolve("main"), World.Environment.CUSTOM);
        other.maxHeight = 320;
        server.worlds.add(other);
        server.pm.call(new WorldInitEvent(other));
        check(Boolean.FALSE.equals(other.autoSave) && Boolean.FALSE.equals(main.autoSave),
            "while stopping, worlds loaded later (e.g. by Multiverse) also get saving switched off");

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
