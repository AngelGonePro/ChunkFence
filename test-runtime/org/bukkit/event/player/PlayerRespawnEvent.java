package org.bukkit.event.player;
public class PlayerRespawnEvent extends PlayerEvent { private org.bukkit.Location l; public PlayerRespawnEvent(org.bukkit.entity.Player p, org.bukkit.Location l){super(p);this.l=l;} public org.bukkit.Location getRespawnLocation() { return l; } public void setRespawnLocation(org.bukkit.Location l) { this.l=l; } }
