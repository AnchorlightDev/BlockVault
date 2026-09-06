package me.benrobson.blockvault.commands;

import me.benrobson.blockvault.util.FileUtil;
import me.benrobson.blockvault.util.VaultUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class bvupdatestate implements CommandExecutor {
    private final Plugin plugin;
    private final VaultUtil vaultUtil;
    private final FileUtil fileUtil;

    public bvupdatestate(Plugin plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.fileUtil = new FileUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("blockvault.updatestate")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        vaultUtil.updateVaultState(player);
        return true;
    }
}