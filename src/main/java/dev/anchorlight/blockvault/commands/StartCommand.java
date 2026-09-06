package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.util.FileUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

public class StartCommand implements CommandExecutor {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;
    private final FileUtil fileUtil;

    public StartCommand(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.fileUtil = new FileUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.start")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }

        if (vaultUtil.hasStarted()) {
            sender.sendMessage(ChatColor.RED + "The Vault challenge has already started.");
            return true;
        }

        try {
            String currentDateTime = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            // Update specific config values
            fileUtil.updateConfigValue("vault.starttime", currentDateTime);
            fileUtil.updateConfigValue("vault.started", true);

            sender.sendMessage(ChatColor.GREEN + "The vault challenge has started, the vault is open!");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "An error occurred while starting the vault.");
            e.printStackTrace();
        }

        return true;
    }
}
