package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /bvstart} opens the event; {@code /bvstart stop} closes it again.
 *
 * <p>The flag lives in the database ({@code bv_state}), not config.yml. While
 * the vault is closed no floor opens, by date or early; on {@code /bvstart}
 * any floor whose {@code opens-at} has already passed opens immediately.
 */
public class StartCommand implements CommandExecutor {

    private final BlockVault plugin;

    public StartCommand(BlockVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.start")) {
            plugin.tell(sender, "§cYou don't have permission to use this command!");
            return true;
        }

        if (plugin.isVaultFinished()) {
            plugin.tell(sender, "§cThe vault is finished and read-only - it cannot be reopened.");
            return true;
        }

        boolean stopping = args.length > 0 && args[0].equalsIgnoreCase("stop");
        boolean started = plugin.isVaultStarted();

        if (stopping && !started) {
            plugin.tell(sender, "§cThe vault is not open.");
            return true;
        }
        if (!stopping && started) {
            plugin.tell(sender, "§cThe vault is already open.");
            return true;
        }

        final boolean open = !stopping;
        final UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.database().setStarted(open, actor);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    plugin.tell(sender, "§cCould not save that to the database - nothing changed. See the console.");
                    return;
                }
                if (!open) {
                    plugin.tell(sender, "§eThe vault is now closed. Submissions and floor openings are paused.");
                    if (plugin.displays() != null) plugin.displays().refresh(); // hologram shows CLOSED now
                    return;
                }
                plugin.tell(sender, "§aThe vault is open! Players may now submit blocks.");
                if (plugin.displays() != null) plugin.displays().refresh(); // hologram shows OPEN now
                List<Integer> due = overdueFloors();
                if (!due.isEmpty()) {
                    plugin.tell(sender, "§eFloor(s) " + due
                            + " have an opens-at date in the past and will open now.");
                }
                plugin.chapters().check();
            });
        });
        return true;
    }

    /** Floors 2-6 that are still sealed but whose configured open date has passed. */
    private List<Integer> overdueFloors() {
        List<Integer> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(plugin.zone());
        for (int ch = 2; ch <= 6; ch++) {
            if (plugin.chapters().isOpen(ch)) continue;
            String raw = plugin.getConfig().getString("chapters." + ch + ".opens-at");
            if (raw == null || raw.isBlank()) continue;
            try {
                if (!LocalDateTime.parse(raw).isAfter(now)) out.add(ch);
            } catch (Exception ignored) {
                // Database.syncChapterDates already warns about bad dates.
            }
        }
        return out;
    }
}
