package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Top 10 contributors, plus the viewer's own rank if they are outside it. */
public class LeaderboardCommand implements CommandExecutor {

    private final BlockVault plugin;
    private final VaultUtil vaultUtil;

    public LeaderboardCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.leaderboard")) {
            plugin.tell(sender, "§cYou don't have permission to use this command!");
            return true;
        }
        if (!vaultUtil.hasStarted()) {
            plugin.tell(sender, "§cThe vault has not been opened yet.");
            return true;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.LeaderRow> top = plugin.database().topContributors(10);
            int ownRank = (sender instanceof Player p) ? plugin.database().rankOf(p.getUniqueId()) : -1;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.tell(sender, "§aTop contributors");
                if (top.isEmpty()) {
                    sender.sendMessage("§7  Nobody has donated a block yet.");
                    return;
                }
                int i = 1;
                boolean sawViewer = false;
                for (Database.LeaderRow r : top) {
                    if (sender instanceof Player p && p.getUniqueId().equals(r.uuid())) sawViewer = true;
                    sender.sendMessage(String.format("§e%2d. §f%-16s §7%d pts, %d blocks",
                            i++, r.name(), r.points(), r.blocks()));
                }
                if (!sawViewer && ownRank > 0) {
                    sender.sendMessage("§8  …");
                    sender.sendMessage("§e" + ownRank + ". §f" + sender.getName() + " §7(you)");
                }
            });
        });
        return true;
    }
}
