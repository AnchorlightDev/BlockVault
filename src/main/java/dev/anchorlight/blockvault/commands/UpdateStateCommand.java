package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class UpdateStateCommand implements CommandExecutor {

    private final VaultUtil vaultUtil;

    public UpdateStateCommand(BlockVault plugin) {
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.updatestate")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }
        // Console-runnable by design.
        vaultUtil.updateVaultState(sender);
        return true;
    }
}
