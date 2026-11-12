package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.bonus.WeekKeys;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

public final class ScheduleUtil {

    private ScheduleUtil() {}

    /** Bad config values already warned about, so the per-minute check doesn't spam the log. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnOnce(BlockVault plugin, String key, String message) {
        if (WARNED.add(key)) plugin.getLogger().warning(message);
    }

    /** How often the wall-clock schedules look at the time: once a minute. */
    private static final long CHECK_TICKS = 20L * 60L;
    /** First look after startup, once bootstrap and world load are done. */
    private static final long FIRST_CHECK_TICKS = 20L * 40L;

    /*
     * The daily and weekly jobs are driven by the real clock, not by a fixed tick
     * period. A tick timer runs late whenever the server is below 20 TPS (a "7 day"
     * timer at 18 TPS fires ~19 hours late every week) and never follows daylight
     * saving. Instead a cheap check runs every minute and fires when the most
     * recent scheduled slot (in the plugin timezone) hasn't been handled yet.
     * Settings are re-read on every check, so /bvreload applies them.
     */

    /**
     * Post the current top contributors to the Discord webhook once a day at the
     * configured hour. A post missed while the server was down is not caught up
     * (and a restart after the hour doesn't re-post). Skipped while the vault is
     * closed or finished.
     */
    public static void scheduleDailyLeaderboard(BlockVault plugin) {
        // The slot that has already passed counts as handled, so a restart never re-posts.
        AtomicReference<LocalDate> lastPosted = new AtomicReference<>(
                latestDaily(ZonedDateTime.now(plugin.zone()), dailyHour(plugin)).toLocalDate());

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("discord.daily-leaderboard.enabled", true)) return;
            LocalDate day = latestDaily(ZonedDateTime.now(plugin.zone()), dailyHour(plugin)).toLocalDate();
            if (day.equals(lastPosted.get())) return;
            lastPosted.set(day);
            int top = Math.max(1, plugin.getConfig().getInt("discord.daily-leaderboard.top", 10));
            postDailyLeaderboard(plugin, top, day);
        }, FIRST_CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("Daily leaderboard posts at " + dailyHour(plugin) + ":00 ("
                + plugin.zone() + ") each day.");
    }

    private static int dailyHour(BlockVault plugin) {
        int hour = plugin.getConfig().getInt("discord.daily-leaderboard.hour", 19);
        if (hour < 0 || hour > 23) {
            warnOnce(plugin, "daily.hour=" + hour,
                    "discord.daily-leaderboard.hour was " + hour + "; it must be 0-23. Using 19.");
            return 19;
        }
        return hour;
    }

    private static void postDailyLeaderboard(BlockVault plugin, int top, LocalDate day) {
        if (!plugin.isVaultStarted() || plugin.isVaultFinished()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var rows = plugin.database().topContributors(top);
            plugin.webhook().dailyLeaderboard("Daily leaderboard — " + day, rows);
        });
    }

    /**
     * Run the weekly top-contributor payout on the configured day/hour (default
     * Friday 9am). If the most recent slot was missed while the server was down,
     * it runs on the first check after startup - but only for a slot after the
     * vault was opened (bv_state.started_at), so a week before the season (or
     * before a /bvstart resume) is never paid. The payout's own guard means a
     * week is never paid twice.
     */
    public static void scheduleWeeklyBonus(BlockVault plugin, WeeklyBonusService service) {
        AtomicReference<String> lastWeek = new AtomicReference<>();

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.getConfig().getBoolean("weekly-bonus.enabled", true)) return;
            if (!plugin.isVaultStarted() || plugin.isVaultFinished()) return;
            ZonedDateTime slot = latestWeekly(ZonedDateTime.now(plugin.zone()), weeklyDay(plugin), weeklyHour(plugin));
            String weekKey = WeekKeys.of(slot);
            if (weekKey.equals(lastWeek.get())) return;
            lastWeek.set(weekKey);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Instant started = plugin.database().startedAt();
                if (started == null || slot.toInstant().isBefore(started)) return; // vault wasn't open then
                if (plugin.database().hasPayoutRun(weekKey)) return;              // already paid
                plugin.getLogger().info("Running the weekly payout for " + weekKey + " (due " + slot + ").");
                Bukkit.getScheduler().runTask(plugin, () -> service.runPayout(null, slot));
            });
        }, FIRST_CHECK_TICKS, CHECK_TICKS);

        ZonedDateTime now = ZonedDateTime.now(plugin.zone());
        plugin.getLogger().info("Weekly bonus runs " + weeklyDay(plugin) + "s at " + weeklyHour(plugin)
                + ":00; next " + latestWeekly(now, weeklyDay(plugin), weeklyHour(plugin)).plusWeeks(1) + ".");
    }

    private static DayOfWeek weeklyDay(BlockVault plugin) {
        String raw = plugin.getConfig().getString("weekly-bonus.day", "FRIDAY");
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(java.util.Locale.ENGLISH));
        } catch (Exception e) {
            warnOnce(plugin, "weekly.day=" + raw, "weekly-bonus.day '" + raw + "' is not a day name; using FRIDAY.");
            return DayOfWeek.FRIDAY;
        }
    }

    private static int weeklyHour(BlockVault plugin) {
        int hour = plugin.getConfig().getInt("weekly-bonus.hour", 9);
        if (hour < 0 || hour > 23) {
            warnOnce(plugin, "weekly.hour=" + hour, "weekly-bonus.hour was " + hour + "; it must be 0-23. Using 9.");
            return 9;
        }
        return hour;
    }

    /** The most recent {@code hour}:00 at or before {@code now}, in its zone. */
    static ZonedDateTime latestDaily(ZonedDateTime now, int hour) {
        ZonedDateTime slot = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return slot.isAfter(now) ? slot.minusDays(1) : slot;
    }

    /** The most recent {@code day} at {@code hour}:00 at or before {@code now}, in its zone. */
    static ZonedDateTime latestWeekly(ZonedDateTime now, DayOfWeek day, int hour) {
        int back = (now.getDayOfWeek().getValue() - day.getValue() + 7) % 7;
        ZonedDateTime slot = now.minusDays(back).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        return slot.isAfter(now) ? slot.minusWeeks(1) : slot;
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
