package org.bukkit.plugin.java;
public abstract class JavaPlugin implements org.bukkit.plugin.Plugin {
  public static org.bukkit.Server SERVER; public static java.io.File DATA; public static java.nio.file.Path DEFAULT_CONFIG;
  private org.bukkit.configuration.file.FileConfiguration cfg; public boolean enabled;
  private final java.util.logging.Logger log = java.util.logging.Logger.getLogger("ChunkFence");
  public org.bukkit.generator.ChunkGenerator getDefaultWorldGenerator(String worldName, String id) { return null; }
  public void onLoad() {}
  public void onEnable() {} public void onDisable() {}
  public void saveDefaultConfig() { try { java.nio.file.Path t = DATA.toPath().resolve("config.yml"); java.nio.file.Files.createDirectories(DATA.toPath()); if (!java.nio.file.Files.exists(t)) java.nio.file.Files.copy(DEFAULT_CONFIG, t); } catch (Exception e) { throw new RuntimeException(e); } }
  public org.bukkit.configuration.file.FileConfiguration getConfig() { if (cfg == null) reloadConfig(); return cfg; }
  public void reloadConfig() { cfg = new org.bukkit.configuration.file.FileConfiguration(); try { cfg.load(DATA.toPath().resolve("config.yml")); } catch (Exception e) { throw new RuntimeException(e); } }
  public void saveConfig() { try { getConfig().save(DATA.toPath().resolve("config.yml")); } catch (Exception e) { throw new RuntimeException(e); } }
  public final org.bukkit.Server getServer() { return SERVER; } public final java.util.logging.Logger getLogger() { return log; }
  public final java.io.File getDataFolder() { return DATA; } public final boolean isEnabled() { return enabled; }
  public boolean onCommand(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return false; }
  public java.util.List<String> onTabComplete(org.bukkit.command.CommandSender s, org.bukkit.command.Command c, String l, String[] a) { return null; }
}
