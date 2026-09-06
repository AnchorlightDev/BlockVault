package dev.anchorlight.blockvault.display;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
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
 * The vanilla display layer: the lobby leader panel (head + flanking signs) and
 * a {@link TextDisplay} hologram with the standings. No third-party dependency -
 * TextDisplay is vanilla since 1.19.4.
 */
public final class DisplayService {

    private final BlockVault plugin;
    private final org.bukkit.NamespacedKey tagKey;
    private BukkitTask task;

    public DisplayService(BlockVault plugin) {
        this.plugin = plugin;
        this.tagKey = new org.bukkit.NamespacedKey(plugin, "hologram");
    }

    public void start() {
        this.task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refresh, 20L * 15L, 20L * 60L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /** Rebuild the leader panel and hologram from the current standings. */
    public void refresh() {
        if (plugin.originWorld() == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.LeaderRow> top = plugin.database().topContributors(5);
            int done = plugin.database().collectedCount();
            int total = plugin.manifest().entries().size();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                updateLeaderPanel(top.isEmpty() ? null : top.get(0));
                updateHologram(top, done, total);
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
        if (headRel == null || leader == null) return;
        Location loc = plugin.resolve(headRel);
        if (loc.getWorld() == null) return;
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
        if (at.getWorld() == null) return;

        StringBuilder text = new StringBuilder("§6§lThe Vault\n§7")
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
