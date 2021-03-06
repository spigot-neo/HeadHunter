package com.neo.headhunter.command;

import com.neo.headhunter.HeadHunter;
import com.neo.headhunter.manager.bounty.BountyListEntry;
import com.neo.headhunter.util.message.Message;
import com.neo.headhunter.util.message.Usage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;

import javax.annotation.Nonnull;
import java.util.*;

public final class BountyExecutor implements CommandExecutor, TabCompleter {
	private final HeadHunter plugin;
	private final Map<String, CooldownRunnable> cooldownTimers;
	
	public BountyExecutor(HeadHunter plugin) {
		this.plugin = plugin;
		this.cooldownTimers = new HashMap<>();
	}
	
	@Override
	public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command cmd, @Nonnull String label, @Nonnull String[] args) {
		if(args.length < 1) {
			Usage.BOUNTY.send(sender);
			return false;
		}
		
		if(args[0].equalsIgnoreCase("list")) {
			// list bounties
			
			// permission
			if(!sender.hasPermission("hunter.bounty.check")) {
				Message.PERMISSION.send(plugin, sender, "/bounty <TARGET>");
				return false;
			}
			
			// assert command is exactly /bounty list
			if(args.length != 1 && args.length != 2) {
				Usage.BOUNTY_LIST.send(sender);
				return false;
			}
			
			// get page number and assert it is valid
			int page = 1;
			if(args.length == 2) {
				if(!args[1].matches("\\d+")) {
					Message.BOUNTY_PAGE_INVALID.send(plugin, sender, args[1]);
					return false;
				}
				
				page = Integer.parseInt(args[1]);
				if(page <= 0) {
					Message.BOUNTY_PAGE_INVALID.send(plugin, sender, args[1]);
					return false;
				}
			}
			
			// assert page number is within range of bounty list
			List<BountyListEntry> bountyListPage = plugin.getBountyManager().getBountyListPage(page);
			if(bountyListPage == null) {
				Message.BOUNTY_PAGE_EMPTY.send(plugin, sender, page);
				return false;
			}
			
			// assert bounty list is not empty
			if(bountyListPage.isEmpty()) {
				Message.BOUNTY_LIST_EMPTY.send(plugin, sender);
				return true;
			}
			
			// messages for bounty list
			for(BountyListEntry entry : bountyListPage) {
				OfflinePlayer victim = entry.getVictim();
				double totalBounty = entry.getAmount();
				
				// get personal bounty
				double hunterBounty = 0;
				if(sender instanceof Player) {
					hunterBounty = plugin.getBountyManager().getBounty((Player) sender, victim);
				}
				
				// create message for personal bounty
				if(hunterBounty > 0) {
					Message.BOUNTY_TOTAL_OWNED.send(plugin, sender, victim.getName(), totalBounty, hunterBounty);
				} else {
					Message.BOUNTY_TOTAL.send(plugin, sender, victim.getName(), totalBounty);
				}
			}
			return true;
		} else {
			if(!(sender instanceof Player)) {
				Message.PLAYERS_ONLY.send(plugin, sender, "/bounty <TARGET> [AMOUNT/remove]");
				return false;
			}
			
			Player hunter = (Player) sender;
			OfflinePlayer victim = getPlayer(args[0]);
			if(victim == null) {
				Message.BOUNTY_TARGET_INVALID.send(plugin, sender, args[0]);
				return false;
			}
			
			String hName = hunter.getName(), vName = victim.getName();
			if(args.length == 1) {
				// check bounty
				
				// permission
				if(!sender.hasPermission("hunter.bounty.check")) {
					Message.PERMISSION.send(plugin, sender, "/bounty <TARGET>");
					return false;
				}
				
				double totalBounty = plugin.getBountyManager().getTotalBounty(victim);
				double hunterBounty = plugin.getBountyManager().getBounty(hunter, victim);
				
				// message for bounty check
				if(hunterBounty > 0) {
					Message.BOUNTY_TOTAL_OWNED.send(plugin, sender, victim.getName(), totalBounty, hunterBounty);
				} else {
					Message.BOUNTY_TOTAL.send(plugin, sender, victim.getName(), totalBounty);
				}
				return true;
			} else if(args.length == 2) {
				// set or remove bounty
				
				String bountyString = args[1];
				if(bountyString.equalsIgnoreCase("remove") || bountyString.equalsIgnoreCase("0")) {
					// remove bounty
					
					// permission
					if(!sender.hasPermission("hunter.bounty.set")) {
						Message.PERMISSION.send(plugin, sender, "/bounty <TARGET> <remove>");
						return false;
					}
					
					// assert bounty exists
					double amount = plugin.getBountyManager().removeBounty(hunter, victim);
					if(amount <= 0) {
						Message.BOUNTY_REMOVE_FAIL.send(plugin, sender, vName);
						return true;
					}
					
					// refund money and message for bounty remove
					plugin.getEconomy().depositPlayer(hunter, amount);
					if(plugin.getSettings().isBroadcastPlace()) {
						Message.BOUNTY_BROADCAST_REMOVE.broadcast(plugin, hName, amount, vName);
					} else {
						Message.BOUNTY_REMOVED.send(plugin, sender, vName);
					}
					plugin.getSignBlockManager().requestUpdate();
					return true;
				} else if(bountyString.matches("\\d+([.]\\d{0,2})?")){
					// set bounty
					
					// permission
					if(!sender.hasPermission("hunter.bounty.set")) {
						Message.PERMISSION.send(plugin, sender, "/bounty <TARGET> <AMOUNT>");
						return false;
					}
					
					// assert bounty target is not the command sender
					if(hunter.equals(victim) && !HeadHunter.DEBUG) {
						Message.BOUNTY_SET_SELF.send(plugin, sender);
						return true;
					}
					
					// assert hunter is off bounty cooldown
					if(cooldownTimers.containsKey(hunter.getUniqueId().toString())) {
						CooldownRunnable runnable = cooldownTimers.get(hunter.getUniqueId().toString());
						Message.BOUNTY_SET_COOLDOWN.send(plugin, sender, formatTime(runnable.cooldown));
						return false;
					}
					
					// assert new bounty is above minimum threshold
					double amount = Double.parseDouble(bountyString);
					if(amount < plugin.getSettings().getMinimumBounty()) {
						Message.BOUNTY_AMOUNT_LOW.send(plugin, sender, plugin.getSettings().getMinimumBounty());
						return true;
					}
					
					// assert hunter has enough money to set this bounty
					double current = plugin.getBountyManager().removeBounty(hunter, victim);
					double balance = plugin.getEconomy().getBalance(hunter);
					if(amount > balance + current) {
						Message.BOUNTY_SET_AFFORD.send(plugin, sender, amount);
						return true;
					}
					
					// set new bounty and message for bounty set
					plugin.getEconomy().depositPlayer(hunter, current);
					plugin.getBountyManager().setBounty(hunter, victim, amount);
					plugin.getEconomy().withdrawPlayer(hunter, amount);
					if (plugin.getSettings().isBroadcastPlace()) {
						Message.BOUNTY_BROADCAST_SET.broadcast(plugin, hName, amount, vName);
					} else {
						Message.BOUNTY_SET.send(plugin, sender, vName, amount);
					}
					
					// set bounty cooldown
					long defCooldown = plugin.getSettings().getBountyCooldown();
					if(defCooldown > 0) {
						CooldownRunnable runnable = new CooldownRunnable(hunter, defCooldown);
						cooldownTimers.put(hunter.getUniqueId().toString(), runnable);
						runnable.runTaskTimer(plugin, 0L, 20L);
					}
					
					// update bounty signs
					plugin.getSignBlockManager().requestUpdate();
					return true;
				} else {
					Message.BOUNTY_AMOUNT_INVALID.send(plugin, sender, bountyString);
					return false;
				}
			} else {
				Usage.BOUNTY.send(sender);
				return false;
			}
		}
	}
	
	@Override
	public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command cmd, @Nonnull String label, @Nonnull String[] args) {
		final List<String> result = new ArrayList<>();
		if(args.length == 1) {
			List<String> completions = new ArrayList<>();
			for(Player p : Bukkit.getOnlinePlayers())
				completions.add(p.getName());
			StringUtil.copyPartialMatches(args[0], completions, result);
			Collections.sort(result);
		} else if(args.length == 2) {
			Iterable<String> completions = Arrays.asList("remove", "0");
			StringUtil.copyPartialMatches(args[1], completions, result);
		}
		return result;
	}
	
	private class CooldownRunnable extends BukkitRunnable {
		private final String hunterID;
		private long cooldown;
		
		private CooldownRunnable(Player hunter, long cooldown) {
			this.hunterID = hunter.getUniqueId().toString();
			this.cooldown = cooldown;
		}
		
		@Override
		public void run() {
			cooldown--;
			if(cooldown <= 0) {
				cooldownTimers.remove(hunterID);
				cancel();
			}
		}
	}
	
	@SuppressWarnings("deprecation")
	public OfflinePlayer getPlayer(String name) {
		OfflinePlayer result = Bukkit.getPlayerExact(name);
		if(result == null)
			result = Bukkit.getPlayer(name);
		if(result == null)
			result = Bukkit.getOfflinePlayer(name);
		if(result.hasPlayedBefore())
			return result;
		for(Player p : Bukkit.getOnlinePlayers()) {
			if(p.getName().equalsIgnoreCase(name))
				return p;
		}
		return null;
	}
	
	private String formatTime(long seconds) {
		long days = seconds / 86400;
		long hours = seconds / 3600;
		long minutes = seconds / 60;
		long remaining = seconds % 60;
		
		StringBuilder builder = new StringBuilder();
		if(days > 0) {
			builder.append(days);
			builder.append(" day");
			if(days != 1)
				builder.append("s");
		} else if(hours > 0) {
			builder.append(hours);
			builder.append(" hour");
			if(hours != 1)
				builder.append("s");
		} else if(minutes > 0) {
			builder.append(minutes);
			builder.append(" minute");
			if(minutes != 1)
				builder.append("s");
		} else {
			builder.append(remaining);
			builder.append(" second");
			if(remaining != 1)
				builder.append("s");
		}
		
		return builder.toString();
	}
}
