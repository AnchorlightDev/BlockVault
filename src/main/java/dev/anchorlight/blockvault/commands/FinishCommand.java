package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /bvfinish} ends the season. This is terminal and irreversible: the
 * vault becomes read-only (no more submissions, and it cannot be reopened), and
 * each block's current leader is paid a flat bonus ({@code points.leader-bonus})
 * once.
 *
 * <p>Because it moves points and cannot be undone, it is two-step: the first
 * call warns and arms a confirmation; {@code /bvfinish confirm} within
 * {@link #CONFIRM_WINDOW_MS} carries it out.
 */
public class FinishCommand implements CommandExecutor {

    private static final long CONFIRM_WINDOW_MS = 30_000L;

    private final BlockVault plugin;
    /** sender name -> when they armed the confirmation. */
    private final Map<String, Long> armed = new ConcurrentHashMap<>();

    public FinishCommand(BlockVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.finish")) {
            plugin.tell(sender, "§cYou don't have permission to use this command!");
            return true;
        }
        if (plugin.isVaultFinished()) {
            plugin.tell(sender, "§cThe vault is already finished.");
            return true;
        }

        int bonus = plugin.getConfig().getInt("points.leader-bonus", 5);
        boolean confirming = args.length > 0 && args[0].equalsIgnoreCase("confirm");

        if (!confirming) {
            armed.put(sender.getName(), System.currentTimeMillis());
            plugin.tell(sender, "§e§lThis ends the season and cannot be undone.");
            plugin.tell(sender, "§7The vault becomes §fread-only §7(no more submissions, and it can't be reopened),");
            plugin.tell(sender, "§7and every block's current leader is paid §f" + bonus
                    + " §7bonus points per block they lead.");
            plugin.tell(sender, "§7Run §f/bvfinish confirm §7within 30 seconds to go ahead.");
            return true;
        }

        Long armedAt = armed.remove(sender.getName());
        if (armedAt == null || System.currentTimeMillis() - armedAt > CONFIRM_WINDOW_MS) {
            plugin.tell(sender, "§cNothing to confirm (or it expired). Run §f/bvfinish §cfirst.");
            return true;
        }

        final UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;
        plugin.tell(sender, "§7Finishing the season…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.FinishResult r = plugin.database().finish(actor, bonus);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!r.ok()) {
                    plugin.tell(sender, "§cCould not finish the season - nothing changed. See the console.");
                    return;
                }
                if (r.alreadyFinished()) {
                    plugin.tell(sender, "§eThe vault was already finished - no bonus was paid again.");
                    return;
                }
                plugin.tell(sender, "§aThe vault is finished and now read-only.");
                plugin.tell(sender, "§aPaid §f" + r.pointsAwarded() + " §abonus points to §f"
                        + r.leaders() + " §aplayers across §f" + r.blocksLed() + " §ablocks led.");
                plugin.webhook().seasonFinished(r.leaders(), r.blocksLed(), r.pointsAwarded());
                // Redraw so locked/held hints and panels reflect the closed state.
                if (plugin.displays() != null) plugin.displays().refresh();
            });
        });
        return true;
    }
}
