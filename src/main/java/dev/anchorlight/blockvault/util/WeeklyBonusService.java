package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.bonus.WeekKeys;
import dev.anchorlight.blockvault.db.Database;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the weekly top-contributor payout: triggers the (idempotent) database
 * transaction, then announces the result in-game and to Discord, direct-messages
 * the paid players, and refreshes the featured-block cache. The heavy lifting and
 * the "never pay twice" guard live in {@link Database#runWeeklyPayout}; this is
 * the orchestration and the wording.
 */
public final class WeeklyBonusService {

    private final BlockVault plugin;

    public WeeklyBonusService(BlockVault plugin) {
        this.plugin = plugin;
    }

    /** Run the payout for right now. */
    public void runPayout(CommandSender feedback) {
        runPayout(feedback, ZonedDateTime.now(plugin.zone()));
    }

    /**
     * Run (or catch up) the payout for the week containing {@code payoutTime}.
     * Idempotent: a week already paid is a no-op. {@code feedback} receives
     * staff-facing status (null for the scheduled run). {@code payoutTime} is the
     * scheduled fire time, so a catch-up after a restart pays the right week.
     */
    public void runPayout(CommandSender feedback, ZonedDateTime payoutTime) {
        if (!plugin.getConfig().getBoolean("weekly-bonus.enabled", true)) {
            tell(feedback, "§cThe weekly bonus is disabled in config.");
            return;
        }
        if (!plugin.isVaultStarted() || plugin.isVaultFinished()) {
            tell(feedback, "§cThe vault is not open - no payout runs while it is closed or finished.");
            return;
        }

        String weekKey = WeekKeys.of(payoutTime);
        String nextWeekKey = WeekKeys.next(payoutTime);
        Set<Integer> open = plugin.featured().openChapters();
        int pts = plugin.getConfig().getInt("weekly-bonus.points-per-block", 2);
        int cap = Math.max(1, plugin.getConfig().getInt("weekly-bonus.cap-blocks", 10));
        int featuredPts = plugin.getConfig().getInt("weekly-bonus.featured-points-per-block", 4);
        int featuredCount = plugin.getConfig().getBoolean("featured-block.enabled", true)
                ? Math.max(1, plugin.getConfig().getInt("featured-block.count", 1)) : 0;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.PayoutResult r = plugin.database().runWeeklyPayout(
                    weekKey, nextWeekKey, open, pts, cap, featuredPts, featuredCount, new java.util.Random());
            plugin.getServer().getScheduler().runTask(plugin, () -> onResult(r, feedback));
        });
    }

    private void onResult(Database.PayoutResult r, CommandSender feedback) {
        if (!r.ok()) {
            tell(feedback, "§cThe weekly payout failed - see the console.");
            return;
        }
        if (r.alreadyRan()) {
            tell(feedback, "§eWeek " + r.weekKey() + " has already been paid - nothing to do.");
            return;
        }
        // Next week's featured block is now the current one - refresh the cache,
        // then redraw so the info board's {featured} line shows the new block.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.featured().refresh();
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.displays().refresh());
        });

        announce(r);
        tell(feedback, "§aPaid §f" + r.totalPoints() + " §apoints to §f" + r.players()
                + " §aplayer" + (r.players() == 1 ? "" : "s") + " for week " + r.weekKey() + ".");
    }

    /** Broadcast the summary, post it to Discord, and DM each paid player. */
    private void announce(Database.PayoutResult r) {
        // Per-player totals and block counts.
        Map<UUID, int[]> perPlayer = new LinkedHashMap<>(); // [points, blocks]
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Database.PayoutLine l : r.lines()) {
            int[] acc = perPlayer.computeIfAbsent(l.uuid(), k -> new int[2]);
            acc[0] += l.points();
            acc[1] += 1;
            names.putIfAbsent(l.uuid(), l.name());
        }

        List<Map.Entry<UUID, int[]>> movers = new ArrayList<>(perPlayer.entrySet());
        movers.sort(Comparator.comparingInt((Map.Entry<UUID, int[]> e) -> e.getValue()[0]).reversed());

        String featured = r.featuredChosen().isEmpty() ? "-"
                : String.join(", ", r.featuredChosen().stream().map(this::pretty).toList());

        // In-game broadcast.
        if (perPlayer.isEmpty()) {
            plugin.getServer().broadcastMessage(plugin.prefix()
                    + "§6§lWeekly bonus §r§7- no eligible top contributors this week.");
        } else {
            plugin.getServer().broadcastMessage(plugin.prefix() + "§6§lWeekly top-contributor bonus!");
            int shown = 0;
            for (var e : movers) {
                if (shown++ >= 5) break;
                plugin.getServer().broadcastMessage(plugin.prefix() + "§e" + (shown) + ". §f"
                        + names.get(e.getKey()) + " §7+" + e.getValue()[0] + " pts §8("
                        + e.getValue()[1] + " block" + (e.getValue()[1] == 1 ? "" : "s") + ")");
            }
            if (movers.size() > 5) {
                plugin.getServer().broadcastMessage(plugin.prefix() + "§7…and "
                        + (movers.size() - 5) + " more.");
            }
        }
        plugin.getServer().broadcastMessage(plugin.prefix()
                + "§bFeatured block next week: §f" + featured + " §7(double points)");

        // Discord.
        List<String> body = new ArrayList<>();
        int shown = 0;
        for (var e : movers) {
            if (shown++ >= 10) break;
            body.add((shown) + ". " + names.get(e.getKey()) + " +" + e.getValue()[0] + " pts ("
                    + e.getValue()[1] + " block" + (e.getValue()[1] == 1 ? "" : "s") + ")");
        }
        body.add("Featured next week: " + featured + " (double points)");
        plugin.webhook().weeklyPayout("Weekly top-contributor bonus - " + r.weekKey(), body);

        // Personal messages.
        if (plugin.getConfig().getBoolean("weekly-bonus.announce-personal", true)) {
            Set<UUID> capped = new HashSet<>(r.cappedPlayers());
            for (var e : perPlayer.entrySet()) {
                Player p = plugin.getServer().getPlayer(e.getKey());
                if (p == null) continue;
                String msg = "§6Weekly bonus: §f+" + e.getValue()[0] + " points §7for being top contributor on §f"
                        + e.getValue()[1] + " block" + (e.getValue()[1] == 1 ? "" : "s") + ".";
                if (capped.contains(e.getKey())) {
                    msg += " §8(You hit the " + plugin.getConfig().getInt("weekly-bonus.cap-blocks", 10)
                            + "-block weekly cap.)";
                }
                plugin.tell(p, msg);
            }
        }
    }

    private String pretty(String material) {
        Material m = Material.matchMaterial(material);
        return m != null ? VaultUtil.formatMaterialName(m) : material;
    }

    private void tell(CommandSender to, String message) {
        if (to != null) plugin.tell(to, message);
    }
}
