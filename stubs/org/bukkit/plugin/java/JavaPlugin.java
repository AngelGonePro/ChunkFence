package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
  public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return null; }
  public void onLoad() {}
  public void onEnable() {} public void onDisable() {}
  public void saveDefaultConfig() {} public org.bukkit.configuration.file.FileConfiguration getConfig() { return null; } public void reloadConfig() {} public void saveConfig() {}
  public final org.bukkit.Server getServer() { return null; } public final java.util.logging.Logger getLogger() { return null; }
  public final java.io.File getDataFolder() { return null; } public final boolean isEnabled() { return false; }
  public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return false; }
  public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return null; }
}
