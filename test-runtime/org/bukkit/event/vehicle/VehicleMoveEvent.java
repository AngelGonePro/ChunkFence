package org.bukkit.event.vehicle;
public class VehicleMoveEvent extends VehicleEvent { private final org.bukkit.Location f,t; public VehicleMoveEvent(org.bukkit.entity.Vehicle v, org.bukkit.Location f, org.bukkit.Location t){super(v);this.f=f;this.t=t;} public org.bukkit.Location getFrom() { return f; } public org.bukkit.Location getTo() { return t; } }
