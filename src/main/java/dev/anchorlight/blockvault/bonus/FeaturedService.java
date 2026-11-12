package dev.anchorlight.blockvault.bonus;

import dev.anchorlight.blockvault.BlockVault;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Caches the current week's featured block(s) so the hot submit path can ask
 * {@link #isFeatured(String)} without a database round-trip. Refreshed at
 * startup, after each weekly payout, and when a staff member overrides the pick.
 */
public final class FeaturedService {

    private final BlockVault plugin;
    private volatile Set<String> featured = Set.of();
    private volatile String weekKey = "";

    public FeaturedService(BlockVault plugin) {
        this.plugin = plugin;
    }

    /** The ISO week key for right now, in the plugin's timezone. */
    public String currentWeekKey() {
        return WeekKeys.of(ZonedDateTime.now(plugin.zone()));
    }

    /** Reload the cached current featured set (the latest batch). Blocking - call async. */
    public void refresh() {
        List<String> list = plugin.database().currentFeatured();
        this.weekKey = currentWeekKey();
        this.featured = new HashSet<>(list);
    }

    /** True if {@code material} is featured this week (double points). */
    public boolean isFeatured(String material) {
        return featured.contains(material);
    }

    public Set<String> featured() {
        return featured;
    }

    public String weekKey() {
        return weekKey;
    }

    /** First featured block by name for the info board, or null if none this week. */
    public String displayMaterial() {
        return featured.stream().sorted().findFirst().orElse(null);
    }
}
