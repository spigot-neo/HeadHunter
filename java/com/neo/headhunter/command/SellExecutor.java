package com.neo.headhunter.command;

import com.neo.headhunter.HeadHunter;
import com.neo.headhunter.manager.block.HeadBlockManager;
import com.neo.headhunter.manager.head.HeadData;
import com.neo.headhunter.util.message.Message;
import com.neo.headhunter.util.message.Usage;
import com.neo.headhunter.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class SellExecutor implements CommandExecutor, TabCompleter {
	private final HeadHunter plugin;
	
	public SellExecutor(HeadHunter plugin) {
		this.plugin = plugin;
	}
	
	@Override
	public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command cmd, @Nonnull String label, @Nonnull String[] args) {
		if(args.length == 0) {
			// permission
			if(!sender.hasPermission("hunter.sellhead.hand")) {
				Message.PERMISSION.send(plugin, sender, "/sellhead");
				return false;
			}
			
			// assert sender is a player
			if(!(sender instanceof Player)) {
				Message.PLAYERS_ONLY.send(plugin, sender, "/sellhead");
				return false;
			}
			
			// sell held stack of heads
			sellHeldStack((Player) sender);
			return true;
		} else if(args.length == 1 && args[0].equalsIgnoreCase("all")) {
			// permission
			if(!sender.hasPermission("hunter.sellhead.all")) {
				Message.PERMISSION.send(plugin, sender, "/sellhead all");
				return false;
			}
			
			// assert sender is a player
			if(!(sender instanceof Player)) {
				Message.PLAYERS_ONLY.send(plugin, sender, "/sellhead all");
				return false;
			}
			
			// sell all heads in inventory
			sellAllStacks((Player) sender);
			return true;
		} else {
			Usage.SELLHEAD.send(sender);
		}
		return false;
	}
	
	@Override
	public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command cmd, @Nonnull String label, @Nonnull String[] args) {
		final List<String> result = new ArrayList<>();
		if(args.length == 1) {
			Iterable<String> completions = Collections.singleton("all");
			StringUtil.copyPartialMatches(args[0], completions, result);
		}
		return result;
	}
	
	public void sellAllStacks(Player hunter) {
		PlayerInventory inv = hunter.getInventory();
		HeadStackValue total = null;
		for(int slot = 0; slot < 36; slot++) {
			HeadStackValue current = getStackValue(inv, slot);
			if(current != null) {
				double currentTotalValue = current.balanceValue + current.bountyValue;
				if(currentTotalValue > 0) {
					if (total == null) {
						total = current;
					} else {
						total.add(current);
					}
					
					if (current.withdraw && current.headOwner != null && current.balanceValue > 0) {
						plugin.getEconomy().withdrawPlayer(current.headOwner, current.balanceValue);
					}
					plugin.getEconomy().depositPlayer(hunter, currentTotalValue);
					inv.clear(slot);
				}
			}
		}
		
		if(total != null) {
			double totalValue = total.balanceValue + total.bountyValue;
			sendSellMessage(hunter, total.stackName, total.totalAmount, totalValue);
		} else {
			Message.SELL_FAIL.send(plugin, hunter);
		}
	}
	
	public void sellHeldStack(Player hunter) {
		PlayerInventory inv = hunter.getInventory();
		int slot = inv.getHeldItemSlot();
		HeadStackValue value = getStackValue(inv, slot);
		if(value != null) {
			double totalValue = value.balanceValue + value.bountyValue;
			if(value.stackName != null && totalValue > 0) {
				if(value.withdraw && value.headOwner != null && value.balanceValue > 0)
					plugin.getEconomy().withdrawPlayer(value.headOwner, value.balanceValue);
				plugin.getEconomy().depositPlayer(hunter, totalValue);
				inv.clear(slot);
				
				sendSellMessage(hunter, value.stackName, value.individualAmount, totalValue);
			} else {
				Message.SELL_FAIL.send(plugin, hunter);
			}
		} else {
			Message.SELL_FAIL.send(plugin, hunter);
		}
	}
	
	private void sendSellMessage(Player hunter, String itemName, int amount, double value) {
		if(itemName != null) {
			if (plugin.getSettings().isBroadcastSell()) {
				Message.SELL_SINGLE_BROADCAST.send(plugin, hunter, hunter.getName(), itemName, amount, value);
			} else {
				Message.SELL_SINGLE.send(plugin, hunter, itemName, amount, value);
			}
		} else if (plugin.getSettings().isBroadcastSell()) {
			Message.SELL_MULTIPLE_BROADCAST.send(plugin, hunter, hunter.getName(), amount, value);
		} else {
			Message.SELL_MULTIPLE.send(plugin, hunter, amount, value);
		}
	}
	
	private HeadStackValue getStackValue(PlayerInventory inv, int slot) {
		ItemStack item = inv.getItem(slot);
		if(HeadBlockManager.isHead(item))
			return new HeadStackValue(item);
		return null;
	}
	
	private class HeadStackValue {
		private final int individualAmount;
		private int totalAmount;
		private double balanceValue, bountyValue;
		private boolean withdraw;
		private OfflinePlayer headOwner;
		private String stackName;
		
		private HeadStackValue(ItemStack head) {
			this.individualAmount = head.getAmount();
			this.totalAmount = this.individualAmount;
			
			this.withdraw = false;
			
			ItemMeta meta = head.getItemMeta();
			if(meta != null)
				this.stackName = meta.getDisplayName();
			
			HeadData data = new HeadData(plugin, head);
			if(!data.isMobHead() && data.getOwnerString() != null)
				this.headOwner = Bukkit.getOfflinePlayer(UUID.fromString(data.getOwnerString()));
			String balanceString = data.getBalanceString();
			if(balanceString != null) {
				if (balanceString.endsWith("%")) {
					balanceString = balanceString.replace("%", "");
					this.withdraw = true;
					double balance = plugin.getEconomy().getBalance(headOwner);
					double stealBalance = Utils.valueOf(balanceString) / 100.0;
					this.balanceValue = 0;
					for(int i = 0; i < this.individualAmount; i++) {
						double stolenValue = balance * stealBalance;
						this.balanceValue += stolenValue;
						balance -= stolenValue;
					}
				} else {
				    this.balanceValue = Utils.valueOf(balanceString);
					this.balanceValue *= this.individualAmount;
				}
			} else
				this.balanceValue = 0;
			
			String bountyString = data.getBountyString();
			if(bountyString != null)
				this.bountyValue = Utils.valueOf(bountyString);
			this.bountyValue *= this.individualAmount;
		}
		
		private void add(HeadStackValue value) {
			this.totalAmount += value.individualAmount;
			this.balanceValue += value.balanceValue;
			this.bountyValue += value.bountyValue;
			this.withdraw = false;
			if(stackName != null && !stackName.equals(value.stackName))
				this.stackName = null;
		}
	}
}
