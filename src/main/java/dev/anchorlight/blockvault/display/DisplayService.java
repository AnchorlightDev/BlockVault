package dev.anchorlight.blockvault.display;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.HeadSlot;
import dev.anchorlight.blockvault.util.HeadUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * The vanilla display layer: the lobby leader panel (head + flanking signs), a
 * {@link TextDisplay} hologram with the standings, and each floor's standing
 * leader heads (#1, #2, ... by items given to that floor) with a name label,
 * a "locked" sign over each floor that hasn't opened yet (date + countdown,
 * never the floor's name), and an optional how-it-works board.
 * No third-party dependency - TextDisplay is vanilla since 1.19.4.
 */
public final class DisplayService {

    private final BlockVault plugin;
    private final org.bukkit.NamespacedKey tagKey;
    private BukkitTask task;

    public DisplayService(BlockVault plugin) {
        this.plugin = plugin;
        this.tagKey = new org.bukkit.NamespacedKey(plugin, "hologram");
        this.floorKey = new org.bukkit.NamespacedKey(plugin, "floor_leader");
        this.boardKey = boardKey(plugin);
    }

    private final org.bukkit.NamespacedKey boardKey;

    /** Tag on the locked-floor signs ("2".."6") and the how-it-works board ("info"). */
    private static org.bukkit.NamespacedKey boardKey(org.bukkit.plugin.Plugin plugin) {
        return new org.bukkit.NamespacedKey(plugin, "board");
    }

    /** Where a board floats: centred over the top of its block. */
    private static Location boardAt(Location block) {
        return block.clone().add(0.5, 1.15, 0.5);
    }

    /** Remove a board spawned above {@code block} - used by /bvsetup when one is moved. */
    public static void removeBoard(org.bukkit.plugin.Plugin plugin, Location block, String key) {
        if (block.getWorld() == null || !block.isChunkLoaded()) return;
        var k = boardKey(plugin);
        for (var e : block.getWorld().getNearbyEntities(boardAt(block), 1, 2, 1)) {
            if (e instanceof TextDisplay td
                    && key.equals(td.getPersistentDataContainer().get(k, PersistentDataType.STRING))) {
                td.remove();
            }
        }
    }

    private final org.bukkit.NamespacedKey floorKey;

    public void start() {
        this.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshNow, 20L * 15L, 20L * 60L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /** Set while a refresh is queued, so a burst of donations runs one pass, not dozens. */
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Rebuild the leader panel and holograms from the current standings. Calls in
     * the same second are coalesced into one pass (each pass is ~10 queries).
     */
    public void refresh() {
        if (!refreshQueued.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            refreshQueued.set(false);
            refreshNow();
        }, 20L);
    }

    private void refreshNow() {
        if (plugin.originWorld() == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.LeaderRow> top = plugin.database().topContributors(5);
            int done = plugin.database().collectedCount();
            int total = plugin.manifest().entries().size();
            java.util.Map<Integer, List<Database.TopDonor>> floors = new java.util.HashMap<>();
            for (var e : plugin.manifest().floorLeaders().entrySet()) {
                if (e.getValue().isEmpty()) continue;
                List<Database.TopDonor> rows = plugin.database().floorLeaders(e.getKey(), e.getValue().size());
                if (rows != null) floors.put(e.getKey(), rows); // null = DB error: leave heads as they are
            }
            List<Database.ChapterRow> chapters = plugin.database().chapters();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                updateLeaderPanel(top.isEmpty() ? null : top.get(0));
                updateHologram(top, done, total);
                floors.forEach(this::updateFloorLeaders);
                updateLockedSigns(chapters);
                updateInfoBoard(done, total);
            });
        });
    }

    private void updateLeaderPanel(Database.LeaderRow leader) {
        setSign(plugin.manifest().leader("name_sign"),
                "§lLeader", leader == null ? "—" : leader.name(), "", "");
        setSign(plugin.manifest().leader("count_sign"),
                "§lContribution", leader == null ? "—" : leader.points() + " pts",
                leader == null ? "" : leader.blocks() + " blocks", "");
        setSign(plugin.manifest().leader("title"),
                "§6The Vault", "Hall of the", "First Givers", "");

        int[] headRel = plugin.manifest().leader("head");
        if (headRel == null) return;
        Location loc = plugin.resolve(headRel);
        if (loc.getWorld() == null) return;
        if (leader == null) {
            // No donations (e.g. after a reset): clear the old leader's skin.
            if (loc.getBlock().getState() instanceof Skull skull && skull.getOwnerProfile() != null) {
                skull.setOwnerProfile(null);
                skull.update(true, false);
            }
            return;
        }
        if (loc.getBlock().getType() != Material.PLAYER_HEAD
                && loc.getBlock().getType() != Material.PLAYER_WALL_HEAD) {
            loc.getBlock().setType(Material.PLAYER_HEAD, false);
        }
        if (loc.getBlock().getState() instanceof Skull skull) {
            PlayerProfile profile = Bukkit.createPlayerProfile(leader.uuid());
            skull.setOwnerProfile(profile);
            skull.update(true, false);
        }
    }

    private void setSign(int[] rel, String... lines) {
        if (rel == null) return;
        Location loc = plugin.resolve(rel);
        if (loc.getWorld() == null) return;
        if (!(loc.getBlock().getState() instanceof Sign sign)) return; // leave real structure alone
        for (int i = 0; i < 4 && i < lines.length; i++) {
            sign.setLine(i, lines[i]);
        }
        sign.update(true, false);
    }

    private void updateHologram(List<Database.LeaderRow> top, int done, int total) {
        int[] rel = plugin.manifest().leader("head");
        if (rel == null) return;
        Location at = plugin.resolve(rel).add(0.5, 2.2, 0.5);
        if (at.getWorld() == null || !at.isChunkLoaded()) return; // don't spawn into an unloaded chunk

        // Open/closed status under the title, so players can tell at a glance.
        String status = plugin.isVaultFinished() ? "§6§lSEASON COMPLETE"
                : plugin.isVaultStarted() ? "§a§l● OPEN §r§7· /bvsubmit to donate"
                : "§c§l● CLOSED §r§7· donations paused";
        StringBuilder text = new StringBuilder("§6§lThe Vault\n")
                .append(status).append("\n§7")
                .append(done).append(" / ").append(total)
                .append(" §7(").append(total == 0 ? 0 : Math.round(100.0 * done / total)).append("%)\n");
        int rank = 1;
        for (Database.LeaderRow r : top) {
            text.append("\n§e").append(rank++).append(". §f").append(r.name())
                .append(" §7").append(r.points()).append(" pts");
        }
        if (top.isEmpty()) text.append("\n§7No donations yet");

        TextDisplay display = findHologram(at);
        if (display == null) {
            display = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
            });
        }
        display.setText(text.toString());
    }

    /**
     * Standing heads on a floor, in rank order. Locked floors keep plain heads
     * and no labels; open floors show #1..#n by items given to that floor.
     */
    private void updateFloorLeaders(int chapter, List<Database.TopDonor> rows) {
        boolean open = plugin.chapters().isOpen(chapter);
        List<HeadSlot> slots = plugin.manifest().floorLeaders(chapter);
        for (int i = 0; i < slots.size(); i++) {
            HeadSlot slot = slots.get(i);
            Location loc = plugin.resolve(slot.head());
            if (loc.getWorld() == null || !loc.isChunkLoaded()) continue;
            Database.TopDonor who = open && i < rows.size() ? rows.get(i) : null;

            PlayerProfile profile = null;
            if (who != null) {
                profile = HeadUtil.fromJson(plugin.getServer(), who.profileJson());
                if (profile == null) profile = Bukkit.createPlayerProfile(who.uuid(), who.name());
            }
            HeadUtil.applyHead(loc, slot.wall(), slot.face(), profile);

            Location labelAt = loc.clone().add(0.5, 0.9, 0.5);
            String tag = chapter + ":" + i;
            TextDisplay label = findTagged(labelAt, floorKey, tag);
            if (!open) {
                if (label != null) label.remove();
                continue;
            }
            String text = "§6#" + (i + 1) + "\n" + (who == null
                    ? "§7—"
                    : "§f" + who.name() + "\n§7" + who.quantity() + " given");
            if (label == null) {
                label = labelAt.getWorld().spawn(labelAt, TextDisplay.class, d -> {
                    d.getPersistentDataContainer().set(floorKey, PersistentDataType.STRING, tag);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setDefaultBackground(false);
                    d.setTransformation(new org.bukkit.util.Transformation(
                            new org.joml.Vector3f(), new org.joml.AxisAngle4f(),
                            new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.AxisAngle4f()));
                });
            }
            if (!text.equals(label.getText())) label.setText(text);
        }
    }

    // ------------------------------------------------------------ locked floors

    private static final java.time.format.DateTimeFormatter DROP_FMT =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", java.util.Locale.ENGLISH);

    /**
     * A sign over each floor that hasn't opened: floor number, drop date (in the
     * configured timezone) and a countdown. The floor's name is never sent - not
     * even obfuscated, since §k text still reaches the client in full. Removed the
     * moment the floor opens (unlock() calls refresh()).
     */
    private void updateLockedSigns(List<Database.ChapterRow> chapters) {
        if (chapters.isEmpty()) return; // DB error: leave signs as they are
        long now = System.currentTimeMillis();
        for (Database.ChapterRow row : chapters) {
            int ch = row.chapter();
            String key = String.valueOf(ch);
            int[] rel = plugin.manifest().hologram(key);
            if (rel == null || ch < 2) continue;
            Location block = plugin.resolve(rel);
            if (block.getWorld() == null || !block.isChunkLoaded()) continue;

            if (plugin.chapters().isOpen(ch)) {
                removeBoard(plugin, block, key);
                continue;
            }

            StringBuilder text = new StringBuilder("§c§lFloor ").append(ch).append(" §8· §7Sealed\n§8???\n");
            if (row.opensAt() == null) {
                text.append("\n§7Opening date to be announced");
            } else {
                long ms = row.opensAt().getTime() - now;
                var when = row.opensAt().toInstant().atZone(plugin.zone());
                text.append("\n§7Opens §e").append(DROP_FMT.format(when));
                if (ms > 0) text.append("\n§7in §f").append(countdown(ms));
                else text.append("\n§eOpening soon");
            }
            upsertBoard(block, key, text.toString(), 1.0f);
        }
    }

    private static String countdown(long ms) {
        long mins = Math.max(1, ms / 60_000L);
        long d = mins / 1440, h = (mins % 1440) / 60, m = mins % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    // ------------------------------------------------------------ info board

    /** The how-it-works board, from {@code info-board.lines} in config. */
    private void updateInfoBoard(int done, int total) {
        int[] rel = plugin.manifest().hologram("info");
        if (rel == null) return;
        Location block = plugin.resolve(rel);
        if (block.getWorld() == null || !block.isChunkLoaded()) return;
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("info-board.enabled", true)) {
            removeBoard(plugin, block, "info");
            return;
        }
        List<String> lines = cfg.getStringList("info-board.lines");
        if (lines.isEmpty()) return;
        String text = String.join("\n", lines)
                .replace("{done}", String.valueOf(done))
                .replace("{total}", String.valueOf(total))
                .replace("{percent}", String.valueOf(total == 0 ? 0 : Math.round(100.0 * done / total)))
                .replace("{floor}", String.valueOf(plugin.chapters().current()))
                .replace("{common}", String.valueOf(cfg.getInt("points.common", 1)))
                .replace("{uncommon}", String.valueOf(cfg.getInt("points.uncommon", 5)))
                .replace("{rare}", String.valueOf(cfg.getInt("points.rare", 10)))
                .replace("{bonus}", String.valueOf(cfg.getInt("points.leader-bonus", 0)))
                .replace("{featured}", featuredName())
                .replace("{weekly}", String.valueOf(cfg.getInt("weekly-bonus.points-per-block", 2)))
                .replace("{cap}", String.valueOf(cfg.getInt("weekly-bonus.cap-blocks", 10)));
        text = org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
        upsertBoard(block, "info", text, (float) cfg.getDouble("info-board.scale", 1.0));
    }

    /** This week's featured block as a display name, or "-" if none. */
    private String featuredName() {
        if (plugin.featured() == null) return "-";
        String material = plugin.featured().displayMaterial();
        if (material == null) return "-";
        Material m = Material.matchMaterial(material);
        return m != null ? dev.anchorlight.blockvault.util.VaultUtil.formatMaterialName(m) : material;
    }

    /** Find-or-spawn the tagged board above {@code block} and set its text. */
    private void upsertBoard(Location block, String key, String text, float scale) {
        Location at = boardAt(block);
        TextDisplay board = null;
        for (var e : at.getWorld().getNearbyEntities(at, 1, 2, 1)) {
            if (e instanceof TextDisplay td
                    && key.equals(td.getPersistentDataContainer().get(boardKey, PersistentDataType.STRING))) {
                if (board == null) board = td; else td.remove(); // de-dupe
            }
        }
        if (board == null) {
            board = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.getPersistentDataContainer().set(boardKey, PersistentDataType.STRING, key);
                d.setBillboard(Display.Billboard.CENTER);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setLineWidth(240);
                d.setShadowed(true);
            });
        }
        var t = board.getTransformation();
        if (t.getScale().x() != scale) {
            board.setTransformation(new org.bukkit.util.Transformation(
                    t.getTranslation(), t.getLeftRotation(),
                    new org.joml.Vector3f(scale, scale, scale), t.getRightRotation()));
        }
        if (!text.equals(board.getText())) board.setText(text);
    }

    private TextDisplay findTagged(Location near, org.bukkit.NamespacedKey key, String tag) {
        for (var e : near.getWorld().getNearbyEntities(near, 0.5, 0.5, 0.5)) {
            if (e instanceof TextDisplay td
                    && tag.equals(td.getPersistentDataContainer().get(key, PersistentDataType.STRING))) {
                return td;
            }
        }
        return null;
    }

    private TextDisplay findHologram(Location near) {
        for (var e : near.getWorld().getNearbyEntities(near, 3, 3, 3)) {
            if (e instanceof TextDisplay td
                    && td.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE)) {
                return td;
            }
        }
        return null;
    }
}
