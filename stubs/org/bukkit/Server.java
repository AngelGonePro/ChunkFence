package org.bukkit;
public interface Server {
  org.bukkit.plugin.PluginManager getPluginManager(); java.util.List<World> getWorlds(); World getWorld(String name);
  org.bukkit.scheduler.BukkitScheduler getScheduler();
  java.io.File getWorldContainer(); String getMinecraftVersion(); String getBukkitVersion(); void shutdown();
}
