package org.bukkit.event.entity;
public class EntityTeleportEvent extends EntityEvent implements org.bukkit.event.Cancellable { public org.bukkit.Location getTo() { return null; } public boolean isCancelled() { return false; } public void setCancelled(boolean c) {} }
