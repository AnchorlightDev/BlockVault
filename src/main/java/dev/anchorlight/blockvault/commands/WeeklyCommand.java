package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.bonus.WeekKeys;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Staff: {@code /bvweekly [run|status]} - trigger the weekly payout, or show its status. */
public final class WeeklyCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final BlockVault plugin;

    public WeeklyCommand(BlockVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.weekly")) {
            plugin.tell(sender, "§cYou don't have permission to use this command.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ENGLISH) : "status";
        if (sub.equals("run")) {
            boolean confirmed = args.length > 1 && args[1].equalsIgnoreCase("confirm");
            if (!confirmed) {
                String week = WeekKeys.of(ZonedDateTime.now(plugin.zone()));
                plugin.tell(sender, "§e§lThis pays week " + week + " now, with the current standings.");
                plugin.tell(sender, "§7It counts as this week's payout - the scheduled run this week will then do nothing.");
                plugin.tell(sender, "§7Run §f/bvweekly run confirm §7to go ahead.");
                return true;
            }
            plugin.tell(sender, "§7Running the weekly top-contributor payout…");
            plugin.weekly().runPayout(sender);
        } else {
            status(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        var featured = plugin.featured().featured();
        String f = featured.isEmpty() ? "§7none this week"
                : "§b" + String.join(", ", featured.stream().map(WeeklyCommand::pretty).toList());
        plugin.tell(sender, "§6Featured block: " + f);

        String thisWeek = WeekKeys.of(ZonedDateTime.now(plugin.zone()));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.PayoutRunRow last = plugin.database().lastPayoutRun();
            boolean paidThisWeek = plugin.database().hasPayoutRun(thisWeek);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (last == null) {
                    plugin.tell(sender, "§7No weekly payout has run yet.");
                } else {
                    plugin.tell(sender, "§7Last payout: week §f" + last.weekKey() + " §7- §f"
                            + last.players() + " §7player" + (last.players() == 1 ? "" : "s") + ", §f"
                            + last.totalPoints() + " §7points, " + WHEN.format(
                            last.runAt().toInstant().atZone(plugin.zone())));
                }
                plugin.tell(sender, "§7This week (" + thisWeek + "): "
                        + (paidThisWeek ? "§aalready paid" : "§enot yet paid")
                        + " §8· /bvweekly run confirm");
            });
        });
    }

    private static String pretty(String material) {
        Material m = Material.matchMaterial(material);
        return m != null ? VaultUtil.formatMaterialName(m) : material;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("run")) {
            return "confirm".startsWith(args[1].toLowerCase(Locale.ENGLISH)) ? List.of("confirm") : List.of();
        }
        if (args.length != 1) return List.of();
        String p = args[0].toLowerCase(Locale.ENGLISH);
        return java.util.stream.Stream.of("run", "status").filter(s -> s.startsWith(p)).toList();
    }
}
