package me.benrobson.blockvault.commands;

import me.benrobson.blockvault.util.FileUtil;
import me.benrobson.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static me.benrobson.blockvault.util.VaultUtil.*;

public class bvsubmit implements CommandExecutor {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;

    public bvsubmit(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return true;
        }

        Player player = (Player) sender;

        if (!vaultUtil.hasStarted()) {
            sender.sendMessage("§cYou cannot submit items as the Vault has not been opened yet.");
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            player.sendMessage("§cYou are not holding any item or block!");
            return true;
        }

        // Get item display name or fallback to material name
        ItemMeta meta = itemInHand.getItemMeta();
        String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : formatMaterialName(itemInHand.getType());
        String itemName = itemInHand.getType().name().toLowerCase();

        // Load configuration files
        File vaultDataFile = new File(plugin.getDataFolder(), "vault_data.yml");
        File vaultItemsFile = new File(plugin.getDataFolder(), "vault_items.yml");
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        YamlConfiguration vaultData = YamlConfiguration.loadConfiguration(vaultDataFile);
        YamlConfiguration vaultItems = YamlConfiguration.loadConfiguration(vaultItemsFile);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        // Check if item has already been collected globally
        String collector = vaultData.getString("vault_data.global_collected_items." + itemName);
        if (collector != null) {
            player.sendMessage("§cItem " + displayName + " has already been collected by " + collector + "!");
            return true;
        }

        // Add item to player's collected items
        List<String> playerItems = vaultData.getStringList("vault_data." + player.getName() + ".collected_items");
        if (playerItems.contains(itemName)) {
            player.sendMessage("§cYou have already collected " + displayName + "!");
            return true;
        }

        // Determine the item's point category and corresponding points
        String category = vaultItems.getString("items." + itemName, "COMMON"); // Default to COMMON
        int pointsPerCategory = config.getInt("points." + category, 1); // Default to 1 point

        playerItems.add(itemName);

        vaultData.set("vault_data." + player.getName() + ".collected_items", playerItems);

        // Update player points
        int currentPoints = vaultData.getInt("vault_data." + player.getName() + ".points", 0);
        vaultData.set("vault_data." + player.getName() + ".points", currentPoints + pointsPerCategory);

        // Update global collected items
        vaultData.set("vault_data.global_collected_items." + itemName, player.getName());

        try {
            vaultData.save(vaultDataFile);

            // Remove one item from the player's hand
            if (itemInHand.getAmount() > 1) {
                itemInHand.setAmount(itemInHand.getAmount() - 1);
            } else {
                player.getInventory().setItemInMainHand(null);
            }

            player.sendMessage("§aSuccessfully added " + displayName + " to your vault! (§b+" + pointsPerCategory + " points§a)");
        } catch (IOException e) {
            player.sendMessage("§cFailed to save collected item. Please try again.");
            e.printStackTrace();
        }

        return true;
    }
}
