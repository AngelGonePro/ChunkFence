package org.bukkit.event.player;
import org.bukkit.Location;
public class PlayerMoveEvent extends PlayerEvent implements org.bukkit.event.Cancellable {
  public Location getFrom() { return null; } public Location getTo() { return null; }
  public boolean isCancelled() { return false; } public void setCancelled(boolean c) {}
}
