package org.bukkit.event.entity;
public class EntityTeleportEvent extends EntityEvent implements org.bukkit.event.Cancellable { private final org.bukkit.Location to; private boolean c; public EntityTeleportEvent(org.bukkit.Location to){this.to=to;} public org.bukkit.Location getTo() { return to; } public boolean isCancelled() { return c; } public void setCancelled(boolean c) {this.c=c;} }
