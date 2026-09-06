package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.util.FileUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** {@code /bvstart} opens the event; {@code /bvstart stop} closes it again. */
public class StartCommand implements CommandExecutor {

    private final BlockVault plugin;
    private final VaultUtil vaultUtil;
    private final FileUtil fileUtil;

    public StartCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.fileUtil = new FileUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.start")) {
            plugin.tell(sender, "§cYou don't have permission to use this command!");
            return true;
        }

        boolean stopping = args.length > 0 && args[0].equalsIgnoreCase("stop");

        if (stopping) {
            if (!vaultUtil.hasStarted()) {
                plugin.tell(sender, "§cThe vault is not open.");
                return true;
            }
            fileUtil.updateConfigValue("vault.started", false);
            plugin.tell(sender, "§eThe vault is now closed. Submissions are paused.");
            return true;
        }

        if (vaultUtil.hasStarted()) {
            plugin.tell(sender, "§cThe vault is already open.");
            return true;
        }
        fileUtil.updateConfigValue("vault.started", true);
        plugin.tell(sender, "§aThe vault is open! Players may now submit blocks.");
        return true;
    }
}
