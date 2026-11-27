package net.cosmoscraft.chunkfence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

final class FenceListener implements Listener {

    static final String BYPASS = "chunkfence.bypass";

    private final ChunkFencePlugin plugin;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    FenceListener(ChunkFencePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ movement

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        final int toX = to.getBlockX() >> 4;
        final int toZ = to.getBlockZ() >> 4;
        // Fast path: almost every move event stays inside the same chunk.
        if (toX == from.getBlockX() >> 4 && toZ == from.getBlockZ() >> 4) {
            return;
        }
        final World world = to.getWorld();
        if (canEnter(world, toX, toZ)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        // Cancelling puts the player back at 'from', i.e. in the chunk they came from.
        event.setCancelled(true);
        final ChunkSet frozen = this.plugin.allowed(world);
        if (frozen == null) {
            // live world: the chunk is not generated/loaded yet; the server keeps working on it
            notify(player, this.plugin.settings().msgGenerating);
        } else if (frozen.contains(from.getBlockX() >> 4, from.getBlockZ() >> 4)) {
            notify(player, this.plugin.settings().msgBlocked);
        } else {
            // Already outside (e.g. was there before the fence existed): bring them in.
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> checkAndRescue(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handleTeleport(event);
    }

    /**
     * Portal events have their own handler list, so they need their own handler. They fire before the
     * destination portal is searched for or built, so cancelling here also keeps the target world from
     * loading or generating anything around that spot.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        handleTeleport(event);
    }

    /** Items and mobs going through portals would otherwise make the target world generate chunks. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        final Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        final ChunkSet allowed = this.plugin.allowed(to.getWorld());
        if (allowed != null && !allowed.contains(to.getBlockX() >> 4, to.getBlockZ() >> 4)) {
            event.setCancelled(true);
        }
    }

    private void handleTeleport(PlayerTeleportEvent event) {
        final Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        final ChunkSet allowed = this.plugin.allowed(to.getWorld());
        if (allowed == null || allowed.contains(to.getBlockX() >> 4, to.getBlockZ() >> 4)) {
            return;
        }
        final Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        event.setCancelled(true);
        notify(player, this.plugin.settings().msgBlockedTeleport);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        final int toX = to.getBlockX() >> 4;
        final int toZ = to.getBlockZ() >> 4;
        if (toX == from.getBlockX() >> 4 && toZ == from.getBlockZ() >> 4) {
            return;
        }
        if (canEnter(to.getWorld(), toX, toZ)) {
            return;
        }
        final Vehicle vehicle = event.getVehicle();
        final List<Entity> passengers = vehicle.getPassengers();
        Player rider = null;
        for (Entity e : passengers) {
            if (e instanceof Player p && !p.hasPermission(BYPASS)) {
                rider = p;
                break;
            }
        }
        if (rider == null) {
            return; // empty or bypass-only vehicles are not our business
        }
        // VehicleMoveEvent cannot be cancelled: dismount, then put vehicle and riders back where they came from.
        vehicle.eject();
        vehicle.teleport(from);
        for (Entity e : passengers) {
            e.teleport(from);
        }
        notify(rider, this.plugin.allowed(to.getWorld()) == null ? this.plugin.settings().msgGenerating : this.plugin.settings().msgBlocked);
    }

    // ------------------------------------------------------------------ arrivals

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> checkAndRescue(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> checkAndRescue(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        final Location loc = event.getRespawnLocation();
        final ChunkSet allowed = this.plugin.allowed(loc.getWorld());
        if (allowed == null || allowed.contains(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
            || event.getPlayer().hasPermission(BYPASS)) {
            return;
        }
        final Location safe = nearestAllowed(loc, allowed);
        if (safe != null) {
            event.setRespawnLocation(safe);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.lastMessage.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent event) {
        this.plugin.checkWorldHeight(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        this.plugin.setupWorld(event.getWorld(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        this.plugin.forgetWorld(event.getWorld());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Main thread. Frozen worlds: the chunk must be in the snapshot. Live worlds: the chunk must be fully
     * generated and loaded right now (a non-blocking lookup; never triggers a load or generation).
     */
    boolean canEnter(World world, int chunkX, int chunkZ) {
        final WorldFence f = this.plugin.fence(world);
        if (f == null) {
            return true;
        }
        final ChunkSet set = f.allowed;
        if (f.mode == WorldFence.Mode.FROZEN && set != null) {
            return set.contains(chunkX, chunkZ);
        }
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    /** Main thread. Moves the player into the nearest allowed chunk if they are outside the fence. */
    void checkAndRescue(Player player) {
        if (!player.isOnline() || player.hasPermission(BYPASS)) {
            return;
        }
        final Location loc = player.getLocation();
        final ChunkSet allowed = this.plugin.allowed(loc.getWorld());
        if (allowed == null || allowed.contains(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        final Location safe = nearestAllowed(loc, allowed);
        if (safe == null) {
            this.plugin.getLogger().warning(player.getName() + " is outside the fence in " + loc.getWorld().getName()
                + " and no allowed chunk was found within " + this.plugin.settings().rescueRadius + " chunks");
            return;
        }
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.teleport(safe);
        notify(player, this.plugin.settings().msgRescued);
    }

    /** Spiral search outward from the location's chunk; returns a standing spot in the closest allowed chunk. */
    Location nearestAllowed(Location loc, ChunkSet allowed) {
        final World world = loc.getWorld();
        final int cx = loc.getBlockX() >> 4;
        final int cz = loc.getBlockZ() >> 4;
        final int radius = this.plugin.settings().rescueRadius;
        for (int r = 0; r <= radius; r++) {
            int bestX = 0, bestZ = 0;
            long bestD = Long.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue; // ring only
                    }
                    if (allowed.contains(cx + dx, cz + dz)) {
                        final long d = (long) dx * dx + (long) dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            bestX = cx + dx;
                            bestZ = cz + dz;
                        }
                    }
                }
            }
            if (bestD != Long.MAX_VALUE) {
                final int bx = (bestX << 4) + 8;
                final int bz = (bestZ << 4) + 8;
                final int y = world.getHighestBlockYAt(bx, bz) + 1;
                return new Location(world, bx + 0.5, y, bz + 0.5, loc.getYaw(), loc.getPitch());
            }
        }
        final Location spawn = world.getSpawnLocation();
        if (allowed.contains(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) {
            return spawn;
        }
        return null;
    }

    private void notify(Player player, String message) {
        if (message.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = this.lastMessage.get(player.getUniqueId());
        if (last != null && now - last < this.plugin.settings().messageCooldownMs) {
            return;
        }
        this.lastMessage.put(player.getUniqueId(), now);
        if (this.plugin.settings().actionBar) {
            player.sendActionBar(message);
        } else {
            player.sendMessage(message);
        }
    }
}
