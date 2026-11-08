package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.ZonedDateTime;

public final class ScheduleUtil {

    private ScheduleUtil() {}

    private static final long TICKS_PER_SECOND = 20L;
    private static final long TICKS_PER_DAY = TICKS_PER_SECOND * 60L * 60L * 24L;

    /**
     * Post the current top contributors to the Discord webhook once a day, at the
     * configured hour in the plugin's timezone. The first run is delayed until the
     * next occurrence of that hour, then it repeats every 24h. No-op when disabled
     * or no webhook is configured. Skips a day while the vault is closed or finished.
     */
    public static void scheduleDailyLeaderboard(BlockVault plugin) {
        if (!plugin.getConfig().getBoolean("discord.daily-leaderboard.enabled", true)) return;

        int hour = plugin.getConfig().getInt("discord.daily-leaderboard.hour", 19);
        if (hour < 0 || hour > 23) {
            plugin.getLogger().warning("discord.daily-leaderboard.hour was " + hour
                    + "; it must be 0-23. Using 19.");
            hour = 19;
        }
        int top = plugin.getConfig().getInt("discord.daily-leaderboard.top", 10);
        if (top < 1) top = 1;

        ZonedDateTime now = ZonedDateTime.now(plugin.zone());
        ZonedDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long delayTicks = TICKS_PER_SECOND * Duration.between(now, next).getSeconds();

        int listSize = top;
        Bukkit.getScheduler().runTaskTimer(plugin,
                () -> postDailyLeaderboard(plugin, listSize),
                delayTicks, TICKS_PER_DAY);

        plugin.getLogger().info("Daily leaderboard post scheduled for " + next + " (then every 24h).");
    }

    private static void postDailyLeaderboard(BlockVault plugin, int top) {
        if (!plugin.isVaultStarted() || plugin.isVaultFinished()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var rows = plugin.database().topContributors(top);
            String heading = "Daily leaderboard — "
                    + java.time.LocalDate.now(plugin.zone());
            plugin.webhook().dailyLeaderboard(heading, rows);
        });
    }

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
