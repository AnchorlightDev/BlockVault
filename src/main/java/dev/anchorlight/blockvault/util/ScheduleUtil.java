package dev.anchorlight.blockvault.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class ScheduleUtil {

    private ScheduleUtil() {}

    public static void scheduleVaultStateTask(Plugin plugin, VaultUtil vaultUtil, FileUtil fileUtil) {
        int intervalMinutes = fileUtil.getConfig().getInt("update.interval-minutes", 15);
        if (intervalMinutes < 1) {
            plugin.getLogger().warning("update.interval-minutes was " + intervalMinutes
                    + "; clamping to 1. A value of 0 would fail to schedule.");
            intervalMinutes = 1;
        }
        int startupDelaySeconds = fileUtil.getConfig().getInt("update.startup-delay-seconds", 30);
        if (startupDelaySeconds < 0) startupDelaySeconds = 0;

        long delayTicks = 20L * startupDelaySeconds;
        long periodTicks = 20L * 60L * intervalMinutes;

        // Delay the first run so worlds are loaded before it touches blocks.
        Bukkit.getScheduler().runTaskTimer(plugin,
                () -> vaultUtil.updateVaultState(null),
                delayTicks, periodTicks);
    }
}
