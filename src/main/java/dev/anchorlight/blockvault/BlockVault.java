package dev.anchorlight.blockvault;

import dev.anchorlight.blockvault.commands.*;
import dev.anchorlight.blockvault.util.FileUtil;
import dev.anchorlight.blockvault.util.ScheduleUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import static dev.anchorlight.blockvault.util.ScheduleUtil.scheduleVaultStateTask;
import static org.bukkit.Bukkit.getConsoleSender;

public final class BlockVault extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getConsoleSender().sendMessage(translatePrefix() + "§aPlugin is now enabled!");

        FileUtil fileUtil = new FileUtil(this);
        VaultUtil vaultUtil = new VaultUtil(this);

        // Register commands
        register("bvstart", new StartCommand(this));
        register("bvsubmit", new SubmitCommand(this));
        register("bvprogress", new ProgressCommand(this));
        register("bvleaderboard", new LeaderboardCommand(this));
        register("bvupdatestate", new UpdateStateCommand(this));

        // Register events

        VaultUtil.generateVaultItems(this);
        scheduleVaultStateTask(this, vaultUtil, fileUtil);
    }

    @Override
    public void onDisable() {
        getConsoleSender().sendMessage(translatePrefix() + "§cPlugin is now disabled.");
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml; skipping registration.");
            return;
        }
        command.setExecutor(executor);
    }

    private String translatePrefix() {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("lang.prefix", ""));
    }
}
