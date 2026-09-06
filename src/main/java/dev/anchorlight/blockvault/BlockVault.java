package dev.anchorlight.blockvault;

import dev.anchorlight.blockvault.commands.*;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.Manifest;
import dev.anchorlight.blockvault.util.FileUtil;
import dev.anchorlight.blockvault.util.ScheduleUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

import static org.bukkit.Bukkit.getConsoleSender;

public final class BlockVault extends JavaPlugin {

    private static BlockVault instance;

    private Database database;
    private Manifest manifest;

    public static BlockVault get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // The target list is a required artefact - never regenerated (brief section 7).
        try {
            Path override = getDataFolder().toPath().resolve("vault_slots.json");
            this.manifest = Manifest.load(override, getClassLoader());
        } catch (Exception e) {
            getLogger().severe("Could not load vault_slots.json: " + e.getMessage());
            getLogger().severe("Generate it with tools/gen_artifacts.py and restart. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Bootstrap runs once at startup, before the server accepts players. All
        // *runtime* database I/O goes through the async scheduler; this does not.
        try {
            this.database = new Database(this);
            this.database.bootstrap(manifest);
        } catch (Exception e) {
            getLogger().severe("Database bootstrap failed: " + e.getMessage());
            getLogger().severe("Check config.yml credentials and that the schema is reachable. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getConsoleSender().sendMessage(prefix() + "§aPlugin is now enabled!");

        FileUtil fileUtil = new FileUtil(this);
        VaultUtil vaultUtil = new VaultUtil(this);

        register("bvstart", new StartCommand(this));
        register("bvsubmit", new SubmitCommand(this));
        register("bvprogress", new ProgressCommand(this));
        register("bvleaderboard", new LeaderboardCommand(this));
        register("bvupdatestate", new UpdateStateCommand(this));

        // Register events

        ScheduleUtil.scheduleVaultStateTask(this, vaultUtil, fileUtil);
    }

    @Override
    public void onDisable() {
        if (database != null) database.close();
        getConsoleSender().sendMessage(prefix() + "§cPlugin is now disabled.");
    }

    public Database database() {
        return database;
    }

    public Manifest manifest() {
        return manifest;
    }

    /** The configured world the schematic origin sits in, or null if not loaded. */
    public World originWorld() {
        return getServer().getWorld(getConfig().getString("origin.world", "world"));
    }

    /** Resolve a manifest-relative coordinate to a live world location. */
    public Location resolve(int[] rel) {
        return new Location(originWorld(),
                getConfig().getInt("origin.x") + rel[0],
                getConfig().getInt("origin.y") + rel[1],
                getConfig().getInt("origin.z") + rel[2]);
    }

    public String prefix() {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("lang.prefix", ""));
    }

    /** Send a player-facing message through the configured prefix. */
    public void tell(org.bukkit.command.CommandSender to, String message) {
        to.sendMessage(prefix() + message);
    }

    private void register(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml; skipping registration.");
            return;
        }
        command.setExecutor(executor);
    }
}
