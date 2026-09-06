package me.benrobson.blockvault.commands;

import me.benrobson.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;

public class bvleaderboard implements CommandExecutor {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;

    public bvleaderboard(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can view the leaderboard!");
            return true;
        }

        if (!vaultUtil.hasStarted()) {
            sender.sendMessage("§cYou cannot check the leaderboard as the Vault has not been opened yet.");
            return true;
        }

        // Load the player data file
        File vaultDataFile = new File(plugin.getDataFolder(), "vault_data.yml");
        YamlConfiguration vaultData = YamlConfiguration.loadConfiguration(vaultDataFile);

        // Check if 'vault_data' section exists
        if (vaultData.getConfigurationSection("vault_data") == null) {
            sender.sendMessage("§cNo player data found! Cannot produce leaderboard.");
            return true;
        }

        // Get all player points data, excluding "global_collected_items"
        Set<String> playerNames = vaultData.getConfigurationSection("vault_data").getKeys(false);
        Map<String, Integer> playerPoints = new HashMap<>();

        for (String playerName : playerNames) {
            if (!playerName.equalsIgnoreCase("global_collected_items")) { // Exclude global_collected_items
                int points = vaultData.getInt("vault_data." + playerName + ".points");
                playerPoints.put(playerName, points);
            }
        }

        // Sort players by points in descending order
        List<Map.Entry<String, Integer>> sortedPlayers = new ArrayList<>(playerPoints.entrySet());
        sortedPlayers.sort((entry1, entry2) -> Integer.compare(entry2.getValue(), entry1.getValue()));

        // Display top 10 players
        sender.sendMessage("§aVault Leaderboard:");
        sender.sendMessage("§a=================================");

        int topPlayersCount = Math.min(5, sortedPlayers.size());
        for (int i = 0; i < topPlayersCount; i++) {
            Map.Entry<String, Integer> entry = sortedPlayers.get(i);
            String playerName = entry.getKey();
            int points = entry.getValue();
            sender.sendMessage("§f" + (i + 1) + ". " + playerName + " - " + points + " points");
        }

        return true;
    }
}