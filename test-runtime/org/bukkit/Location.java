package org.bukkit;
public class Location implements Cloneable {
  public World world; public double x, y, z; public float yaw, pitch;
  public Location(World w, double x, double y, double z, float yaw, float pitch) { this.world=w; this.x=x; this.y=y; this.z=z; this.yaw=yaw; this.pitch=pitch; }
  public Location(World w, double x, double y, double z) { this(w,x,y,z,0,0); }
  public World getWorld() { return world; }
  public int getBlockX() { return (int)Math.floor(x); } public int getBlockZ() { return (int)Math.floor(z); }
  public float getYaw() { return yaw; } public float getPitch() { return pitch; }
  public String toString() { return (world==null?"null":world.getName())+"("+x+","+y+","+z+")"; }
}
