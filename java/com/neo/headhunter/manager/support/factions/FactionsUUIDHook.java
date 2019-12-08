package com.neo.headhunter.manager.support.factions;

import com.massivecraft.factions.*;
import com.neo.headhunter.HeadHunter;
import com.neo.headhunter.config.ConfigAccessor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class FactionsUUIDHook extends ConfigAccessor<HeadHunter> implements FactionsHook {
	public FactionsUUIDHook(HeadHunter plugin) {
		super(plugin, true, "config.yml");
	}
	
	@Override
	public boolean isValidTerritory(Player victim) {
		Faction faction = Board.getInstance().getFactionAt(new FLocation(victim));
		FPlayer fPlayer = FPlayers.getInstance().getByPlayer(victim);
		if(faction != null && fPlayer != null && fPlayer.hasFaction()) {
			Faction ownedFaction = fPlayer.getFaction();
			if(faction.equals(ownedFaction))
				return config.getBoolean(FactionsPath.DROP_HOME, false);
		}
		return true;
	}
	
	@Override
	public boolean isValidZone(Location location) {
		Faction faction = Board.getInstance().getFactionAt(new FLocation(location));
		if(faction != null) {
			if(faction.isWilderness())
				return config.getBoolean(FactionsPath.DROP_WILDERNESS, true);
			if(faction.isSafeZone())
				return config.getBoolean(FactionsPath.DROP_SAFEZONE, false);
			if(faction.isWarZone())
				return config.getBoolean(FactionsPath.DROP_WARZONE, false);
		}
		return true;
	}
	
	@Override
	public boolean isValidHunter(Player hunter, Player victim) {
		FPlayer hFPlayer = FPlayers.getInstance().getByPlayer(hunter);
		FPlayer vFPlayer = FPlayers.getInstance().getByPlayer(victim);
		if(hFPlayer != null && vFPlayer != null && hFPlayer.hasFaction() && vFPlayer.hasFaction()) {
			Faction hunterFaction = hFPlayer.getFaction();
			Faction victimFaction = vFPlayer.getFaction();
			if(hunterFaction.equals(victimFaction))
				return config.getBoolean(FactionsPath.DROP_FRIENDLY, false);
		}
		return false;
	}
}