package me.benrobson.blockvault.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ScheduleUtil {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;
    private final FileUtil fileUtil;

    public ScheduleUtil(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.fileUtil = new FileUtil(plugin);
    }

    public static void scheduleVaultStateTask(Plugin plugin, VaultUtil vaultUtil, FileUtil fileUtil) {
        // Get the interval from the config file
        int intervalMinutes = fileUtil.getConfig().getInt("vault.updateinterval", 15); // Default to 15 if not set

        // Convert the interval from minutes to ticks (20 ticks per second, 60 seconds per minute)
        long intervalTicks = 20L * 60L * intervalMinutes;

        // Schedule the task to run every 'intervalTicks' ticks
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            vaultUtil.updateVaultState(null);
        }, 0L, intervalTicks); // Runs based on the interval in the config
    }
}
