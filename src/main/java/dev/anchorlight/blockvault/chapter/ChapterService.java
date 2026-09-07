package dev.anchorlight.blockvault.chapter;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns which chapters are open. A chapter opens on its scheduled date OR when
 * the previous chapter reaches 90%, whichever comes first. Earlier chapters
 * never close - only the spotlight (the "current" chapter) advances.
 */
public final class ChapterService {

    private static final double EARLY_OPEN_FRACTION = 0.90;

    private final BlockVault plugin;
    private final Set<Integer> open = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> completed = Collections.synchronizedSet(new HashSet<>());
    private BukkitTask task;

    public ChapterService(BlockVault plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Chapter 1 is always open.
        open.add(1);
        long everyFiveMinutes = 20L * 60L * 5L;
        this.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::check, 20L * 20L, everyFiveMinutes);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public boolean isOpen(int chapter) {
        return chapter <= 1 || open.contains(chapter);
    }

    /** Highest currently-open chapter - the spotlight. */
    public int current() {
        int c = 1;
        synchronized (open) {
            for (int ch : open) c = Math.max(c, ch);
        }
        return c;
    }

    /** Recompute open state and run any pending unlock ceremonies. Safe to call often. */
    public void check() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.ChapterRow> rows = plugin.database().chapters();
            Set<String> collected = plugin.database().collectedSnapshot();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                long now = System.currentTimeMillis();
                for (Database.ChapterRow row : rows) {
                    int ch = row.chapter();
                    if (row.openedAt() != null) open.add(ch);

                    if (!isOpen(ch) && row.openedAt() == null) {
                        boolean dateReached = row.opensAt() != null
                                && now >= row.opensAt().getTime();
                        boolean prevNearlyDone = fraction(ch - 1, collected) >= EARLY_OPEN_FRACTION;
                        if (dateReached || prevNearlyDone) {
                            unlock(row, prevNearlyDone && !dateReached);
                        }
                    }

                    // Completion: earlier chapters never close, but 100% is worth marking.
                    // Requires the chapter to actually have targets - guards against a
                    // partial manifest reporting every empty chapter as "complete".
                    if (chapterSize(ch) > 0 && fraction(ch, collected) >= 1.0 && completed.add(ch)) {
                        onChapterComplete(row);
                    }
                }
            });
        });
    }

    private void onChapterComplete(Database.ChapterRow row) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.database().markChapterComplete(row.chapter(), null));
        String line = "§a§lChapter " + row.chapter() + " — " + row.title()
                + " §r§ais complete! Every block on that floor has been given.";
        plugin.getServer().broadcastMessage(plugin.prefix() + line);
        plugin.getServer().getOnlinePlayers().forEach(p ->
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f));
        plugin.webhook().chapterOpened(row.chapter(), row.title() + " (complete)");
        plugin.displays().refresh();
        plugin.advancements().grantAll(row.chapter());
        plugin.getLogger().info("Chapter " + row.chapter() + " reached 100%.");
    }

    private double fraction(int chapter, Set<String> collected) {
        if (chapter < 1) return 1.0; // "previous of chapter 1" is trivially done
        int total = 0, done = 0;
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (e.chapter() != chapter) continue;
            total++;
            if (collected.contains(e.material())) done++;
        }
        return total == 0 ? 1.0 : (double) done / total;
    }

    private int chapterSize(int chapter) {
        int n = 0;
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (e.chapter() == chapter) n++;
        }
        return n;
    }

    private void unlock(Database.ChapterRow row, boolean early) {
        int chapter = row.chapter();
        if (!open.add(chapter)) return; // someone beat us to it this tick

        // Break the iron-bar seal: one setBlock to air.
        int[] seal = plugin.manifest().seal(chapter);
        if (seal == null && row.sealX() != null) {
            seal = new int[]{row.sealX(), row.sealY(), row.sealZ()};
        }
        if (seal != null) {
            Location loc = plugin.resolve(seal);
            if (loc.getWorld() != null) loc.getBlock().setType(Material.AIR, false);
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.database().markChapterOpened(chapter));

        ceremony(row, early);
        plugin.displays().refresh();
    }

    private void ceremony(Database.ChapterRow row, boolean early) {
        String line = "§6§lChapter " + row.chapter() + " — " + row.title()
                + " §r§7(" + row.room() + ")§r is now open!";
        plugin.getServer().broadcastMessage(plugin.prefix() + line);
        if (early) {
            plugin.getServer().broadcastMessage(plugin.prefix()
                    + "§7Unlocked early — the previous floor is nearly complete.");
        }
        plugin.webhook().chapterOpened(row.chapter(), row.title());

        plugin.getServer().getOnlinePlayers().forEach(p -> {
            p.sendTitle("§6Chapter " + row.chapter(), "§e" + row.title(), 10, 70, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        });

        org.bukkit.boss.BossBar bar = plugin.getServer().createBossBar(
                "§6Chapter " + row.chapter() + " — " + row.title() + " is open",
                org.bukkit.boss.BarColor.YELLOW, org.bukkit.boss.BarStyle.SOLID);
        bar.setProgress(1.0);
        plugin.getServer().getOnlinePlayers().forEach(bar::addPlayer);
        plugin.getServer().getScheduler().runTaskLater(plugin, bar::removeAll, 200L);

        // Fireworks at the leader panel, if the manifest defines it and the world is up.
        int[] leaderHead = plugin.manifest().leader("head");
        Location at = leaderHead == null ? null : plugin.resolve(leaderHead);
        if (at != null && at.getWorld() != null) {
            for (int i = 0; i < 3; i++) {
                Firework fw = at.getWorld().spawn(at.clone().add(0.5, 1, 0.5), Firework.class);
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .withColor(Color.AQUA, Color.WHITE)
                        .with(FireworkEffect.Type.BALL_LARGE)
                        .withFlicker().withTrail().build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
            }
        }
        plugin.getLogger().info("Chapter " + row.chapter() + " opened"
                + (early ? " (early, previous floor >= 90%)." : "."));
    }
}
