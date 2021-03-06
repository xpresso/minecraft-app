/**
 * Copyright 2013 Splunk, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"): you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * LogToSplunkPlugin provides additional logging functionality to the CraftBukkit
 * Minecraft server. The logs are written to the console and the Minecraft server
 * log, and to any number of Splunk TCP input ports declared in config.yml.
 * 
 * The messages conform to Splunk's logging best practices:
 * 
 *     http://dev.splunk.com/view/logging-best-practices/SP-CAAADP6
 */
package com.splunk.logtosplunk;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class LogToSplunkPlugin extends JavaPlugin implements Listener {
	// A list of sockets to log to.
	private List<Socket> splunkSockets;
	
	// Store the last message logged so we can avoid logging
	// multiple times if an event incorrectly fires multiple times.
	private String lastLoggedMessage;
	
	@Override
	public void onEnable() {
		// Register this class with CraftBukkit as an event handler.
		getServer().getPluginManager().registerEvents((Listener) this,  this);
		
		// Extract the Splunk TCP sockets to write to from the configuration.
		// The sockets are specified as a comma separated list of host:port
		// pairs, e.g.,
		//
		//     boris.local:10000, hilda:1234
		//
		// Whitespace around the commas are ignored.
		splunkSockets = new ArrayList<Socket>();
		String splunks = getConfig().getString("splunks");
		if (splunks != null) {
			String[] instances = splunks.split(",");
			for (int i = 0; i < instances.length; i++) {
				String[] pieces = instances[i].split(":");
				final String host = pieces[0].trim();
				final int port = Integer.valueOf(pieces[1].trim());
				new Thread(new Runnable() {
					public void run() { 
						try {
							splunkSockets.add(new Socket(host, port));
							getLogger().info("LogToSplunk added Splunk instance " + host + ":" + port);
						} catch (IOException err) {
							getLogger().severe("Could not connect to port " + port + " on Splunk server at " + host);
							err.printStackTrace();
						}
					}
				}).start();
			}
		}
		
		// Write a configuration file if none exists.
		saveDefaultConfig();
				
	}
	
	@Override
	public void onDisable() {
		// Close all the sockets when this plugin is disabled.
		for (Socket s : splunkSockets) {
			try {
				s.close();
			} catch (IOException e) {
				e.printStackTrace();			
			}
		}
	}
	
	private void writeMessage(String msg) {
		String stampedMsg = Calendar.getInstance().getTime().toString() + " " + msg + "\r\n\r\n";
		
		if (stampedMsg.equals(lastLoggedMessage)) {
			return;
		} else {
			lastLoggedMessage = stampedMsg;
		}
		
		for (Socket s : splunkSockets) {
			try {
				s.getOutputStream().write(stampedMsg.getBytes("UTF-8"));
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}
	
	// The types of wood are kept in a bitmask in an integer field.
	// Extract them to something usable for logging.
	private String woodDataToType(int data) {
		int woodType = data % 4;
		if (woodType == 0) {
			return "OAK";
		} else if (woodType == 1) {
			return "SPRUCE";
		} else if (woodType == 2) {
			return "BIRCH";
		} else if (woodType == 3) {
			return "JUNGLE";
		} else {
			return "UNKNOWN";
		}
		
	}

	private String blockName(Material material, int data) {
		// Certain wood items need to be handled specially so we can
		// log what *type* of wood they are (jungle, spruce, etc.).
		if (material.getId() == Material.LOG.getId()) {
			return woodDataToType(data) + "_LOG";
		} else if (material.getId() == Material.WOOD_STEP.getId()) {
			return woodDataToType(data) + "_SLAB";
		} else if (material.getId() == Material.WOOD_STAIRS.getId()) {
			return woodDataToType(data) + "_STAIRS";
		} else if (material.getId() == Material.WOOD_DOUBLE_STEP.getId()) {
			return woodDataToType(data) + "_SLAB";
		} else {
			// Everything else, we can just log directly.
			return material.toString();
		}
	}
		
	private String locationToString(Location location) {
		return "world=" + location.getWorld().getName() +
				" " + "x=" + location.getX() +
				" " + "y=" + location.getY() +
				" " + "z=" + location.getZ() +
				" " + "game_time=" + location.getWorld().getTime();
	}
	
	/////////////////////////////////////
	// Track breaking and placing blocks
	/////////////////////////////////////
	
	@EventHandler
	public void onBlockBreak(BlockBreakEvent evt) {
		Block b = evt.getBlock();
		StringBuilder msg = new StringBuilder();
		msg.append("action=block_broken player=" + evt.getPlayer().getDisplayName() +
				" " + locationToString(b.getLocation()) +
				" block_type=" + blockName(b.getType(), b.getData()));
		
		for (org.bukkit.inventory.ItemStack st : evt.getBlock().getDrops()) {
			msg.append(" dropped=" + st.getAmount() + "x" + blockName(st.getType(), st.getData().getData()));
		}
		writeMessage(msg.toString());
	}
	
	@EventHandler
	public void onBlockPlace(BlockPlaceEvent evt) {
		Block b = evt.getBlock();
		writeMessage("action=block_placed player=" + evt.getPlayer().getDisplayName() +
				" " + locationToString(b.getLocation()) +
				" block_type=" + blockName(b.getType(), b.getData()));
	}
	
	////////////////////
	// Track players
	////////////////////
	
	@EventHandler
	public void onPlayerMove(PlayerMoveEvent evt) {
		double fromX = evt.getFrom().getX();
		double fromY = evt.getFrom().getY();
		double fromZ = evt.getFrom().getZ();
		double toX = evt.getTo().getX();
		double toY = evt.getTo().getY();
		double toZ = evt.getTo().getZ();
		
		// To avoid spamming with too many events, only log when
		// a player cross a 1m boundary (i.e., from 2.6 to 3.1, but not
		// 3.1 to 3.5).
		if (Math.floor(fromX) != Math.floor(toX) ||
				Math.floor(fromY) != Math.floor(toY) ||
				Math.floor(fromZ) != Math.floor(toZ)) {
			writeMessage("action=player_moved player=" + evt.getPlayer().getDisplayName() +
					" world=" + evt.getFrom().getWorld().getName() +
					" from_x=" + evt.getFrom().getX() +
					" from_y=" + evt.getFrom().getY() +
					" from_z=" + evt.getFrom().getZ() +
					" to_x=" + evt.getTo().getX() +
					" to_y=" + evt.getTo().getY() +
					" to_z=" + evt.getTo().getZ() +
					" game_time=" + evt.getTo().getWorld().getTime());
		}
	}
	
	@EventHandler
	public void onPlayerChangedWorld(PlayerChangedWorldEvent evt) {
		writeMessage("action=player_changed_world player=" + evt.getPlayer().getDisplayName() + 
				" from_world=" + evt.getFrom().getName() +
				" to_world=" + evt.getPlayer().getLocation().getWorld().getName() +
				" to_x=" + evt.getPlayer().getLocation().getX() + 
				" to_y=" + evt.getPlayer().getLocation().getY() +
				" to_z=" + evt.getPlayer().getLocation().getZ() +
				" game_time=" + evt.getPlayer().getLocation().getWorld().getTime());
	}
	
	//////////////////////////
	// Login and logout events
	//////////////////////////
	
	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent evt) {
		writeMessage("action=player_connect player=" + evt.getPlayer().getDisplayName() +
				" " + locationToString(evt.getPlayer().getLocation()));
	}
	
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent evt) {
		writeMessage("action=player_disconnect player=" + evt.getPlayer().getDisplayName() +
				" reason=\"" + evt.getQuitMessage() + "\"" +
				" " + locationToString(evt.getPlayer().getLocation()));
	}
	
	@EventHandler
	public void onPlayerKicked(PlayerKickEvent evt) {
		writeMessage("action=player_kicked player=" + evt.getPlayer().getDisplayName() +
				" reason=\"" + evt.getReason() + "\"" +
				" " + locationToString(evt.getPlayer().getLocation()));
	}
	
	/////////////////
	// Slaughter
	/////////////////
	
	private String nameOfEntity(LivingEntity entity) {
		if (entity instanceof Player) {
			return ((Player)entity).getDisplayName();
		} else {
			return entity.getType().getName().toLowerCase();
		}
	}
	
	// onEntityDamaged is used to log deaths in the world.
	@EventHandler
	public void onEntityDamaged(final EntityDamageEvent evt) {
		// If an entity is going to die, it won't until after onEntityDamaged has already
		// fired. So we have to schedule a task to run afterwards to determine if the entity
		// actually died or not.
		Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
			public void run() {
				// Only log deaths of living things that have actually died.
				if (evt.getEntity() instanceof LivingEntity && evt.getEntity().isDead()) { 
					StringBuilder msg = new StringBuilder();
					
					if (evt.getEntity() instanceof Player) {
						msg.append("action=player_died");
					} else {
						msg.append("action=mob_died");
					}
					
					msg.append(" victim=" + nameOfEntity((LivingEntity)evt.getEntity()));
					
					if (evt instanceof EntityDamageByEntityEvent) {
						Entity damager = ((EntityDamageByEntityEvent)evt).getDamager();
						if (damager instanceof Projectile) {
							msg.append(" killer=" + nameOfEntity(((Projectile)damager).getShooter()));
						} else if (damager instanceof LivingEntity) {
							msg.append(" killer=" + nameOfEntity((LivingEntity)damager));
						}
					} else {
						msg.append(" killer=" + evt.getCause().toString().toLowerCase());
					}
					
					msg.append(" " + locationToString(evt.getEntity().getLocation()));
					writeMessage(msg.toString());
				}
			}
		});
	}
	
	@EventHandler
	public void onRespawn(PlayerRespawnEvent evt) {
		StringBuilder msg = new StringBuilder();
		msg.append("action=player_respawn player=" + evt.getPlayer().getDisplayName());
		msg.append(" " + locationToString(evt.getPlayer().getLocation()));
		writeMessage(msg.toString());
	}
	
	//////////////
	// Chat log
	//////////////
	@EventHandler
	public void onChat(AsyncPlayerChatEvent evt) {
		StringBuilder msg = new StringBuilder();
		msg.append("action=chat player=" + evt.getPlayer().getDisplayName());
		msg.append(" " + locationToString(evt.getPlayer().getLocation()));
		msg.append(" message=\"" + evt.getMessage() + "\"");
		writeMessage(msg.toString());
	}
	

	@EventHandler
	public void onPortalCreate(PortalCreateEvent evt) {
		StringBuilder msg = new StringBuilder();
		msg.append("action=portal_create reason=\"" + evt.getReason() + "\"");
		msg.append(" " + locationToString(evt.getBlocks().get(0).getLocation()));
		writeMessage(msg.toString());
	}
}
