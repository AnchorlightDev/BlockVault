package me.benrobson.blockvault.commands;

import me.benrobson.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.List;

public class bvprogress implements CommandExecutor {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;

    public bvprogress(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return true;
        }

        if (!vaultUtil.hasStarted()) {
            sender.sendMessage("§cYou cannot check progress as the Vault has not been opened yet.");
            return true;
        }

        Player player = (Player) sender;

        // Load vault items and vault data
        File vaultItemsFile = new File(plugin.getDataFolder(), "vault_items.yml");
        File vaultDataFile = new File(plugin.getDataFolder(), "vault_data.yml");

        YamlConfiguration vaultItems = YamlConfiguration.loadConfiguration(vaultItemsFile);
        YamlConfiguration vaultData = YamlConfiguration.loadConfiguration(vaultDataFile);

        // Get the total number of items in the vault
        int totalItems = vaultItems.getConfigurationSection("items").getKeys(false).size();

        // Get the player's progress percentage and progress bar
        String progress = vaultUtil.getProgress(vaultData, totalItems);

        // Display progress
        player.sendMessage("§aVault Progress");
        player.sendMessage("==================================");
        player.sendMessage(progress);

        return true;
    }
}