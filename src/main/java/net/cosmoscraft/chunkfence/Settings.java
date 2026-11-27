package net.cosmoscraft.chunkfence;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml. */
final class Settings {

    final Set<String> frozenWorlds;
    final Set<String> disabledWorlds;
    final boolean liveEnabled;
    final boolean requireFull;
    final boolean ignoreEmpty;
    final boolean ignoreEmptyInEnd;
    final int threads;
    final int rescueRadius;
    final boolean actionBar;
    final long messageCooldownMs;
    final String msgBlocked;
    final String msgGenerating;
    final String msgBlockedTeleport;
    final String msgRescued;
    final String heightsMode;
    final String irisPack;
    final java.util.Map<HeightSync.Dim, HeightSync.Heights> manualHeights = new java.util.EnumMap<>(HeightSync.Dim.class);
    final java.util.Map<HeightSync.Dim, HeightSync.Heights> raisedHeights = new java.util.EnumMap<>(HeightSync.Dim.class);
    final boolean guardEnabled;
    final boolean biomesEnabled;
    final String recoveryMode;

    Settings(FileConfiguration c) {
        this.frozenWorlds = lower(c.getStringList("frozen-worlds"));
        this.disabledWorlds = lower(c.getStringList("disabled-worlds"));
        this.liveEnabled = c.getBoolean("live.enabled", true);
        this.requireFull = c.getBoolean("scan.require-full-status", true);
        this.ignoreEmpty = c.getBoolean("scan.ignore-empty-chunks", true);
        this.ignoreEmptyInEnd = c.getBoolean("scan.ignore-empty-chunks-in-end", false);
        final int t = c.getInt("scan.threads", 0);
        this.threads = t > 0 ? t : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.rescueRadius = Math.max(1, c.getInt("rescue.search-radius-chunks", 64));
        this.actionBar = c.getBoolean("messages.use-action-bar", true);
        this.messageCooldownMs = Math.max(0, c.getInt("messages.cooldown-ms", 1000));
        this.msgBlocked = color(c.getString("messages.blocked", "&cYou can't go any further - this area is closed."));
        this.msgGenerating = color(c.getString("messages.generating", "&eThis area is still generating - give it a moment."));
        this.msgBlockedTeleport = color(c.getString("messages.blocked-teleport", "&cThat location is outside the playable area."));
        this.msgRescued = color(c.getString("messages.rescued", "&eYou were moved back inside the playable area."));
        this.heightsMode = c.getString("heights.mode", "auto").toLowerCase(Locale.ROOT);
        this.irisPack = c.getString("heights.iris-pack", "auto");
        for (HeightSync.Dim d : HeightSync.Dim.values()) {
            final String base = "heights.manual." + d.file + ".";
            final int min = c.getInt(base + "min-y", Integer.MIN_VALUE);
            final int max = c.getInt(base + "max-y", Integer.MIN_VALUE);
            if (min != Integer.MIN_VALUE && max != Integer.MIN_VALUE) {
                this.manualHeights.put(d, new HeightSync.Heights(min, max, c.getInt(base + "logical-height", max - min)));
            }
        }
        for (HeightSync.Dim d : HeightSync.Dim.values()) {
            final String base = "heights.raised." + d.file + ".";
            final int min = c.getInt(base + "min-y", Integer.MIN_VALUE);
            final int max = c.getInt(base + "max-y", Integer.MIN_VALUE);
            if (min != Integer.MIN_VALUE && max != Integer.MIN_VALUE && max > min) {
                this.raisedHeights.put(d, new HeightSync.Heights(min, max, max - min));
            }
        }
        this.guardEnabled = c.getBoolean("guard.enabled", true);
        this.biomesEnabled = c.getBoolean("biomes.enabled", true);
        this.recoveryMode = c.getString("recovery.mode", "repair").toLowerCase(Locale.ROOT);
    }

    boolean isDisabled(String worldName) {
        return this.disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    boolean isListedFrozen(String worldName) {
        return this.frozenWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    RegionScanner.Options scanOptions(World.Environment env) {
        final boolean empty = this.ignoreEmpty && (env != World.Environment.THE_END || this.ignoreEmptyInEnd);
        return new RegionScanner.Options(this.requireFull, empty);
    }

    private static Set<String> lower(List<String> in) {
        final Set<String> out = new HashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Translates '&' colour codes to section signs. */
    static String color(String s) {
        if (s == null) {
            return "";
        }
        final char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}
