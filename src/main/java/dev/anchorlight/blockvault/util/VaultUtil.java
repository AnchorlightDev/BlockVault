package me.benrobson.blockvault.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VaultUtil {
    private final Plugin plugin;
    private final FileUtil fileUtil;

    public VaultUtil(Plugin plugin) {
        this.plugin = plugin;
        this.fileUtil = new FileUtil(plugin);
    }

    // Method to check if the vault has started
    public boolean hasStarted() {
        return plugin.getConfig().getBoolean("vault.started", false);
    }

    public static void generateVaultItems(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "vault_items.yml");

        if (file.exists()) {
            plugin.getLogger().info("vault_items.yml already exists. Skipping generation.");
            return;
        }

        // Load excluded items from the config
        List<String> excludedItems = plugin.getConfig().getStringList("vault.excludeditems");

        // Map to store valid items
        Map<String, String> itemData = new HashMap<>();

        for (Material material : Material.values()) {
            // Add materials that are either blocks or items and are not excluded
            if ((material.isBlock() || material.isItem()) &&
                    !excludedItems.contains(material.name().toLowerCase())) {
                itemData.put(material.name().toLowerCase(), "COMMON"); // Default category
            }
        }

        // Create a YAML configuration and add the items
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("items", itemData);

        try {
            yaml.save(file);
            plugin.getLogger().info("Generated vault_items.yml with all valid blocks and items set to default category COMMON.");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save vault_items.yml: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Formats a Material name to a user-friendly display format.
     *
     * @param material The Material to format.
     * @return The formatted name.
     */
    public static String formatMaterialName(Material material) {
        String[] words = material.name().toLowerCase().split("_");
        StringBuilder formattedName = new StringBuilder();
        for (String word : words) {
            formattedName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return formattedName.toString().trim();
    }

    /**
     * Checks if a specific item has been added to the vault by any player.
     *
     * @param itemName The name of the item to check.
     * @return True if the item has been added, false otherwise.
     */
    public boolean isItemAdded(String itemName) {
        // Normalize item name
        itemName = itemName.toLowerCase();

        File vaultDataFile = new File(plugin.getDataFolder(), "vault_data.yml");
        YamlConfiguration vaultData = YamlConfiguration.loadConfiguration(vaultDataFile);

        if (!vaultData.contains("vault_data")) {
            return false;
        }

        for (String playerName : vaultData.getConfigurationSection("vault_data").getKeys(false)) {
            List<String> collectedItems = vaultData.getStringList("vault_data." + playerName + ".collected_items");

            for (String collectedItem : collectedItems) {
                if (collectedItem.toLowerCase().equals(itemName)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void updateVaultState(Player player) {
        try {
            // Parse region coordinates from config
            int upperX = fileUtil.getConfig().getInt("vault.region.upperregion.X");
            int upperY = fileUtil.getConfig().getInt("vault.region.upperregion.Y");
            int upperZ = fileUtil.getConfig().getInt("vault.region.upperregion.Z");

            int lowerX = fileUtil.getConfig().getInt("vault.region.lowerregion.X");
            int lowerY = fileUtil.getConfig().getInt("vault.region.lowerregion.Y");
            int lowerZ = fileUtil.getConfig().getInt("vault.region.lowerregion.Z");

            World world = Bukkit.getWorlds().get(0);
            if (world == null) {
                String errorMsg = "§cWorld not found! Stopping the update process.";
                Bukkit.getLogger().warning(errorMsg);
                if (player != null) player.sendMessage(errorMsg);
                return;
            }

            // Normalize coordinates
            int minX = Math.min(upperX, lowerX);
            int maxX = Math.max(upperX, lowerX);
            int minY = Math.min(upperY, lowerY);
            int maxY = Math.max(upperY, lowerY);
            int minZ = Math.min(upperZ, lowerZ);
            int maxZ = Math.max(upperZ, lowerZ);

            int foundCount = 0;
            int updatedCount = 0;

            // Iterate over entities in the specified region
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ItemFrame) {
                    Location loc = entity.getLocation();
                    if (loc.getX() >= minX && loc.getX() <= maxX &&
                            loc.getY() >= minY && loc.getY() <= maxY &&
                            loc.getZ() >= minZ && loc.getZ() <= maxZ) {

                        ItemFrame itemFrame = (ItemFrame) entity;
                        if (itemFrame.getItem() != null && itemFrame.getItem().getType() != Material.AIR) {
                            String itemName = itemFrame.getItem().getType().toString().toLowerCase();

                            boolean isInVault = isItemAdded(itemName);
                            String frameMessage = String.format("§aItemFrame at [%d, %d, %d] has item: %s (In Vault: %s).",
                                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), itemName, isInVault ? "Yes" : "No");
                            Bukkit.getLogger().info(frameMessage);
                            if (player != null) player.sendMessage(frameMessage);

                            // Get the block behind the item frame
                            Location blockBehindLoc = itemFrame.getLocation().getBlock().getRelative(itemFrame.getAttachedFace()).getLocation();
                            Material newMaterial = isInVault ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS;
                            blockBehindLoc.getBlock().setType(newMaterial);

                            String updateMessage = String.format("§eBlock behind ItemFrame at [%d, %d, %d] replaced with %s.",
                                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), newMaterial.name());
                            Bukkit.getLogger().info(updateMessage);
                            if (player != null) player.sendMessage(updateMessage);

                            foundCount++;
                            updatedCount++;
                        }
                    }
                }
            }

            String summaryMessage = String.format("§aFound %d item frames and updated %d blocks in the specified region.",
                    foundCount, updatedCount);
            Bukkit.getLogger().info(summaryMessage);
            if (player != null) player.sendMessage(summaryMessage);
        } catch (Exception e) {
            String errorMessage = "§cAn error occurred while processing the region.";
            Bukkit.getLogger().severe(errorMessage);
            e.printStackTrace();
            if (player != null) player.sendMessage(errorMessage);
        }
    }

    /**
     * Calculates the collective progress of all players in the vault and generates a progress bar.
     *
     * @param vaultData   The YamlConfiguration object for vault data.
     * @param totalItems  The total number of items required for the vault.
     * @return A formatted progress string showing the collective percentage and progress bar.
     */
    public String getProgress(YamlConfiguration vaultData, int totalItems) {
        int totalCollectedItems = 0;

        // Iterate through all player entries in the vault data to sum up collected items
        for (String playerName : vaultData.getConfigurationSection("vault_data").getKeys(false)) {
            List<String> playerCollectedItems = vaultData.getStringList("vault_data." + playerName + ".collected_items");
            totalCollectedItems += playerCollectedItems.size();
        }

        // Calculate the percentage of progress for the collective effort
        int progressPercentage = (int) ((double) totalCollectedItems / totalItems * 100);

        // Create a progress bar
        StringBuilder progressBar = new StringBuilder("§a[");
        int barLength = 50; // Total bar length
        int progressLength = (int) ((progressPercentage / 100.0) * barLength);

        for (int i = 0; i < barLength; i++) {
            if (i < progressLength) {
                progressBar.append("§a|");
            } else {
                progressBar.append("§7|");
            }
        }
        progressBar.append("§a]");

        // Create the progress string with total collected and total required items
        String progressInfo = String.format("§e%d/%d collected", totalCollectedItems, totalItems);

        // Return the full progress string
        return progressInfo + " §f(" + progressPercentage + "% complete)\n" + progressBar;
    }
}
