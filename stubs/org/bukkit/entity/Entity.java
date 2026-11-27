package org.bukkit.entity;
public interface Entity {
  org.bukkit.Location getLocation(); org.bukkit.World getWorld(); java.util.UUID getUniqueId();
  boolean teleport(org.bukkit.Location l); java.util.List<Entity> getPassengers(); boolean eject();
  boolean isInsideVehicle(); boolean leaveVehicle();
}
