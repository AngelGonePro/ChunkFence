package org.bukkit.entity;
public interface Player extends Entity, org.bukkit.command.CommandSender { boolean isOnline(); void sendActionBar(String message); }
