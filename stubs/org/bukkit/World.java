package org.bukkit;
public interface World {
  String getName(); java.util.UUID getUID(); default java.io.File getWorldFolder() { return null; }
  Environment getEnvironment(); java.util.List<org.bukkit.entity.Player> getPlayers();
  int getHighestBlockYAt(int x, int z); Location getSpawnLocation();
  boolean isChunkLoaded(int x, int z); int getMinHeight(); int getMaxHeight(); void setAutoSave(boolean v); org.bukkit.generator.ChunkGenerator getGenerator();
  enum Environment { NORMAL, NETHER, THE_END, CUSTOM }
}
