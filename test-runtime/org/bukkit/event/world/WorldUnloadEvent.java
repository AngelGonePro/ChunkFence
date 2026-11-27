package org.bukkit.event.world;
public class WorldUnloadEvent extends WorldEvent implements org.bukkit.event.Cancellable { private boolean c; public WorldUnloadEvent(org.bukkit.World w){super(w);} public boolean isCancelled() { return c; } public void setCancelled(boolean c) {this.c=c;} }
