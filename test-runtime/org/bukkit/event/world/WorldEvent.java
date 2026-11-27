package org.bukkit.event.world;
public abstract class WorldEvent extends org.bukkit.event.Event { private final org.bukkit.World w; protected WorldEvent(org.bukkit.World w){this.w=w;} public org.bukkit.World getWorld() { return w; } }
