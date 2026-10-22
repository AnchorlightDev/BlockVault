package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Set;

/** Two bars: the current chapter, and the whole season. */
public class ProgressCommand implements CommandExecutor {
    private final BlockVault plugin;
    private final VaultUtil vaultUtil;

    public ProgressCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.progress")) {
            plugin.tell(sender, "§cYou don't have permission to use this command!");
            return true;
        }
        if (!vaultUtil.hasStarted()) {
            plugin.tell(sender, "§cThe vault has not been opened yet.");
            return true;
        }

        Set<String> collected = plugin.database().collectedSnapshot();
        int chapter = plugin.chapters().current();

        int chapTotal = 0, chapDone = 0, allTotal = 0, allDone = 0;
        for (TargetEntry e : plugin.manifest().entries().values()) {
            boolean done = collected.contains(e.material());
            allTotal++;
            if (done) allDone++;
            if (e.chapter() == chapter) {
                chapTotal++;
                if (done) chapDone++;
            }
        }

        plugin.tell(sender, "§aVault progress");
        sender.sendMessage(bar("Chapter " + chapter, chapDone, chapTotal));
        sender.sendMessage(bar("Overall", allDone, allTotal));
        return true;
    }

    private static String bar(String labelText, int done, int total) {
        int len = 30;
        int filled = total == 0 ? 0 : (int) Math.round((double) done / total * len);
        StringBuilder b = new StringBuilder("§f").append(labelText).append(" §8[");
        for (int i = 0; i < len; i++) b.append(i < filled ? "§a|" : "§7|");
        int pct = total == 0 ? 0 : (int) Math.round((double) done / total * 100);
        b.append("§8] §e").append(done).append('/').append(total).append(" §7(").append(pct).append("%)");
        return b.toString();
    }
}
