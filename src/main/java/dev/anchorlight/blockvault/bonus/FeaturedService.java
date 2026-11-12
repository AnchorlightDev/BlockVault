package dev.anchorlight.blockvault.bonus;

import dev.anchorlight.blockvault.BlockVault;

import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caches the current week's featured block(s) so the hot submit path can ask
 * {@link #isFeatured(String)} without a database round-trip. Refreshed at
 * startup, after each weekly payout, and when a staff member overrides the pick.
 *
 * <p>Also makes sure there always is one: once a minute, if the vault is open
 * and no featured block is set (the first week of a season, or after
 * {@code /bvfeatured clear} removed the last one), it picks one automatically
 * with the same rule as the weekly rotation and announces it. The weekly payout
 * still rotates it every week.
 */
public final class FeaturedService {

    private final BlockVault plugin;
    private volatile Set<String> featured = Set.of();
    private volatile String weekKey = "";
    private final AtomicBoolean picking = new AtomicBoolean(false);

    public FeaturedService(BlockVault plugin) {
        this.plugin = plugin;
    }

    /** The ISO week key for right now, in the plugin's timezone. */
    public String currentWeekKey() {
        return WeekKeys.of(ZonedDateTime.now(plugin.zone()));
    }

    /** Reload the cached current featured set (the latest batch). Blocking - call async. */
    public void refresh() {
        this.weekKey = currentWeekKey();
        if (!plugin.getConfig().getBoolean("featured-block.enabled", true)) {
            this.featured = Set.of(); // turned off: nothing counts as featured
            return;
        }
        List<String> list = plugin.database().currentFeatured();
        this.featured = new HashSet<>(list);
    }

    /** True if {@code material} is featured this week (double points). */
    public boolean isFeatured(String material) {
        return featured.contains(material);
    }

    /** True if unlocking {@code material} right now pays double (featured + featured-block.double-first-contributor). */
    public boolean doublesUnlock(String material) {
        return isFeatured(material)
                && plugin.getConfig().getBoolean("featured-block.enabled", true)
                && plugin.getConfig().getBoolean("featured-block.double-first-contributor", true);
    }

    public Set<String> featured() {
        return featured;
    }

    public String weekKey() {
        return weekKey;
    }

    /** Start the once-a-minute "is there a featured block?" check. Call once from onEnable. */
    public void start() {
        // First check after startup's async refresh() has loaded the cache.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoPickIfNone, 20L * 45L, 20L * 60L);
    }

    /**
     * Pick and announce a featured block if none is set. Main thread; the pick
     * itself runs async and re-checks inside its transaction, so a stale cache
     * can never cause a second pick.
     */
    public void autoPickIfNone() {
        if (!featured.isEmpty()) return;
        if (!plugin.getConfig().getBoolean("featured-block.enabled", true)) return;
        if (!plugin.isVaultStarted() || plugin.isVaultFinished()) return;
        Set<Integer> open = openChapters();
        if (open.isEmpty() || !picking.compareAndSet(false, true)) return;
        int count = Math.max(1, plugin.getConfig().getInt("featured-block.count", 1));
        String week = currentWeekKey();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> picks = plugin.database().autoPickFeaturedIfNone(week, open, count, new Random());
                refresh();
                if (picks.isEmpty()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> announce(picks));
            } finally {
                picking.set(false);
            }
        });
    }

    private void announce(List<String> picks) {
        String names = String.join(", ", picks.stream().map(FeaturedService::pretty).toList());
        plugin.getLogger().info("Featured block set automatically: " + names);
        plugin.getServer().broadcastMessage(plugin.prefix() + "§bThis week's featured block: §f" + names
                + " §7(double points - first to give it gets double unlock points)");
        plugin.webhook().featuredBlock(names);
        if (plugin.displays() != null) plugin.displays().refresh();
    }

    /** The unlocked floors, from the in-memory chapter state (so floor 1 is included). */
    public Set<Integer> openChapters() {
        Set<Integer> open = new HashSet<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (plugin.chapters().isOpen(e.chapter())) open.add(e.chapter());
        }
        return open;
    }

    private static String pretty(String material) {
        Material m = Material.matchMaterial(material);
        return m != null ? VaultUtil.formatMaterialName(m) : material;
    }

    /** First featured block by name for the info board, or null if none this week. */
    public String displayMaterial() {
        return featured.stream().sorted().findFirst().orElse(null);
    }
}
