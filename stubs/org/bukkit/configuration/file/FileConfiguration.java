package org.bukkit.configuration.file;
public abstract class FileConfiguration {
  public String getString(String p, String d) { return d; } public java.util.List<String> getStringList(String p) { return null; }
  public boolean getBoolean(String p, boolean d) { return d; } public int getInt(String p, int d) { return d; }
  public void set(String p, Object v) {}
}
