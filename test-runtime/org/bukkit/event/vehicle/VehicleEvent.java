package org.bukkit.event.vehicle;
public abstract class VehicleEvent extends org.bukkit.event.Event { private final org.bukkit.entity.Vehicle v; protected VehicleEvent(org.bukkit.entity.Vehicle v){this.v=v;} public final org.bukkit.entity.Vehicle getVehicle() { return v; } }
