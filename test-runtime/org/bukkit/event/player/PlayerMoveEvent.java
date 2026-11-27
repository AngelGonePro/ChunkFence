package org.bukkit.event.player;
import org.bukkit.Location;
public class PlayerMoveEvent extends PlayerEvent implements org.bukkit.event.Cancellable {
  private Location from, to; private boolean cancel;
  public PlayerMoveEvent(org.bukkit.entity.Player p, Location from, Location to) { super(p); this.from=from; this.to=to; }
  public Location getFrom() { return from; } public Location getTo() { return to; }
  public boolean isCancelled() { return cancel; } public void setCancelled(boolean c) { cancel=c; }
}
