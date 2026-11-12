package dev.anchorlight.blockvault;

import dev.anchorlight.StoneLib.config.ConfigUpdater;

import dev.anchorlight.blockvault.bonus.FeaturedService;
import dev.anchorlight.blockvault.chapter.ChapterService;
import dev.anchorlight.blockvault.commands.*;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.display.DisplayService;
import dev.anchorlight.blockvault.listener.RegionProtectionListener;
import dev.anchorlight.blockvault.listener.PlayerGuidanceListener;
import dev.anchorlight.blockvault.model.Manifest;
import dev.anchorlight.blockvault.model.Region;
import dev.anchorlight.blockvault.util.FileUtil;
import dev.anchorlight.blockvault.util.ScheduleUtil;
import dev.anchorlight.blockvault.util.StartupValidation;
import dev.anchorlight.blockvault.util.VaultUtil;
import dev.anchorlight.blockvault.util.Webhook;
import dev.anchorlight.blockvault.util.WeeklyBonusService;
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
    private ChapterService chapters;
    private DisplayService displays;
    private Webhook webhook;
    private FeaturedService featured;
    private WeeklyBonusService weekly;
    private Region region;

    public static BlockVault get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        ConfigUpdater.update(this, "config.yml");
        reloadConfig();

        // /bvsetup builds the layout from the world, so it is available even
        // before a layout exists.
        register("bvsetup", setupCommand());
        getServer().getPluginManager().registerEvents(setup, this); // setup wand

        Path layout = getDataFolder().toPath().resolve(Manifest.FILE);
        if (!Manifest.exists(layout, getClassLoader())) {
            getLogger().warning("No " + Manifest.FILE + " yet - starting in SETUP MODE.");
            getLogger().warning("Only /bvsetup is available. Scan each floor in-game, then restart.");
            setupModeOnly("no layout yet - scan the floors with /bvsetup, then restart the server.");
            return;
        }
        try {
            this.manifest = Manifest.load(layout, getClassLoader(), getConfig().getString("edition", "26.2"));
        } catch (Exception e) {
            getLogger().severe("Could not load " + Manifest.FILE + ": " + e.getMessage());
            getLogger().severe("Fix or delete it (then /bvsetup again) and restart. Only /bvsetup is available.");
            this.manifest = null;
            setupModeOnly(Manifest.FILE + " could not be read - see the console.");
            return;
        }
        if (manifest.entries().isEmpty()) {
            getLogger().warning(Manifest.FILE + " has no blocks for edition "
                    + getConfig().getString("edition") + " - SETUP MODE. Run /bvsetup floor <n>.");
            this.manifest = null;
            setupModeOnly("the layout has no blocks yet - scan the floors with /bvsetup, then restart.");
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

        this.chapters = new ChapterService(this);
        this.webhook = new Webhook(this);
        this.displays = new DisplayService(this);
        this.featured = new FeaturedService(this);
        this.weekly = new WeeklyBonusService(this);

        StartupValidation.run(this);

        register("bvstart", new StartCommand(this));
        register("bvfinish", new FinishCommand(this));
        DonationBox donationBox = new DonationBox(this);
        getServer().getPluginManager().registerEvents(donationBox, this);
        register("bvsubmit", new SubmitCommand(this, donationBox));
        register("bvprogress", new ProgressCommand(this));
        register("bvleaderboard", new LeaderboardCommand(this));
        register("bvupdatestate", new UpdateStateCommand(this));

        QueryCommand query = new QueryCommand(this);
        for (String c : new String[]{"bvfind", "bvinfo", "bvmissing", "bvcheck",
                "bvme", "bvhistory", "bvedition", "bvreload"}) {
            register(c, query);
            PluginCommand pc = getCommand(c);
            if (pc != null) pc.setTabCompleter(query);
        }
        AdminCommand admin = new AdminCommand(this);
        for (String c : new String[]{"bvrevoke", "bvrepair", "bvbackup"}) {
            register(c, admin);
            PluginCommand pc = getCommand(c);
            if (pc != null) pc.setTabCompleter(admin);
        }
        register("bvweekly", new WeeklyCommand(this));
        dev.anchorlight.blockvault.gui.BlockBrowser browser = new dev.anchorlight.blockvault.gui.BlockBrowser(this);
        getServer().getPluginManager().registerEvents(browser, this);
        register("bvblocks", browser);
        PluginCommand browserPc = getCommand("bvblocks");
        if (browserPc != null) browserPc.setTabCompleter(browser);
        FeaturedCommand featuredCmd = new FeaturedCommand(this);
        register("bvfeatured", featuredCmd);
        PluginCommand featuredPc = getCommand("bvfeatured");
        if (featuredPc != null) featuredPc.setTabCompleter(featuredCmd);
        MonitorCommand monitorCmd = new MonitorCommand(this);
        register("bvmonitor", monitorCmd);
        PluginCommand monitorPc = getCommand("bvmonitor");
        if (monitorPc != null) monitorPc.setTabCompleter(monitorCmd);

        getServer().getPluginManager().registerEvents(new RegionProtectionListener(this), this);
        PlayerGuidanceListener guidance = new PlayerGuidanceListener(this);
        getServer().getPluginManager().registerEvents(guidance, this);
        guidance.start();

        forceLoadChunks(true);
        chapters.start();
        displays.start();
        // Load the featured block, then redraw so the info board doesn't show "-".
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            featured.refresh();
            getServer().getScheduler().runTask(this, () -> displays.refresh());
        });
        ScheduleUtil.scheduleVaultStateTask(this, vaultUtil, fileUtil);
        ScheduleUtil.scheduleDailyLeaderboard(this);
        ScheduleUtil.scheduleWeeklyBonus(this, weekly);
    }

    @Override
    public void onDisable() {
        if (setup != null) setup.cancelAll();
        if (displays != null) displays.stop();
        if (chapters != null) chapters.stop();
        forceLoadChunks(false);
        if (database != null) database.close();
        getConsoleSender().sendMessage(prefix() + "§cPlugin is now disabled.");
    }

    public ChapterService chapters() {
        return chapters;
    }

    public Webhook webhook() {
        return webhook;
    }

    public DisplayService displays() {
        return displays;
    }

    public FeaturedService featured() {
        return featured;
    }

    public WeeklyBonusService weekly() {
        return weekly;
    }

    /**
     * The protected volume, built lazily once the origin world is loaded.
     * Rebuilt by {@link #invalidateRegion()} after a config reload.
     */
    public Region region() {
        if (manifest == null) return null;
        if (region == null) {
            World world = originWorld();
            if (world == null) return null;
            int[] origin = {
                    getConfig().getInt("origin.x"),
                    getConfig().getInt("origin.y"),
                    getConfig().getInt("origin.z")
            };
            region = new Region(world, origin, manifest.min(), manifest.max(), 4);
        }
        return region;
    }

    public void invalidateRegion() {
        region = null;
    }

    /**
     * Re-read the layout file ({@link Manifest#FILE}) from disk so changes made
     * with {@code /bvsetup} (new hologram spots, re-scanned floors) take effect
     * without a server restart. The old manifest is kept on any failure or if the
     * file has no blocks, so a bad edit never blanks a running vault. Returns true
     * only when a fresh layout was loaded.
     */
    public boolean reloadManifest() {
        Path layout = getDataFolder().toPath().resolve(Manifest.FILE);
        if (!Manifest.exists(layout, getClassLoader())) return false;
        try {
            Manifest reloaded = Manifest.load(layout, getClassLoader(), getConfig().getString("edition", "26.2"));
            if (reloaded.entries().isEmpty()) {
                getLogger().warning("Skipped layout reload: " + Manifest.FILE
                        + " has no blocks for edition " + getConfig().getString("edition") + ".");
                return false;
            }
            this.manifest = reloaded;
            return true;
        } catch (Exception e) {
            getLogger().severe("Could not reload " + Manifest.FILE + ": " + e.getMessage()
                    + " - keeping the previously loaded layout.");
            return false;
        }
    }

    private void forceLoadChunks(boolean load) {
        Region r = region();
        if (r == null || r.world() == null) return;
        for (int cx = r.minChunkX(); cx <= r.maxChunkX(); cx++) {
            for (int cz = r.minChunkZ(); cz <= r.maxChunkZ(); cz++) {
                r.world().setChunkForceLoaded(cx, cz, load);
            }
        }
        getLogger().info((load ? "Force-loaded " : "Released ")
                + "vault chunks in " + r.world().getName() + ".");
    }

    /**
     * The timezone chapter opens-at dates are written in ({@code timezone} in
     * config). Falls back to the server's own zone if unset or invalid.
     */
    public java.time.ZoneId zone() {
        String id = getConfig().getString("timezone", "");
        if (id != null && !id.isBlank()) {
            try {
                return java.time.ZoneId.of(id.trim());
            } catch (Exception e) {
                getLogger().warning("timezone '" + id + "' is not a valid zone id (e.g. Australia/Sydney)"
                        + " - using the server's zone " + java.time.ZoneId.systemDefault() + ".");
            }
        }
        return java.time.ZoneId.systemDefault();
    }

    /** True once /bvstart has opened the event (and it has not been stopped). */
    public boolean isVaultStarted() {
        return database != null && database.isStarted();
    }

    /** True once /bvfinish has ended the season: the vault is read-only. */
    public boolean isVaultFinished() {
        return database != null && database.isFinished();
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
        if (rel == null) throw new IllegalArgumentException(
                "manifest coordinate missing - check vault_slots.json (leader/seals block)");
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

    private dev.anchorlight.blockvault.setup.SetupCommand setup;

    private dev.anchorlight.blockvault.setup.SetupCommand setupCommand() {
        if (setup == null) setup = new dev.anchorlight.blockvault.setup.SetupCommand(this);
        PluginCommand pc = getCommand("bvsetup");
        if (pc != null) pc.setTabCompleter(setup);
        return setup;
    }

    /**
     * In setup mode only /bvsetup is wired up. Give every other command a clear
     * answer instead of Bukkit's bare usage line.
     */
    @SuppressWarnings("deprecation")
    private void setupModeOnly(String reason) {
        CommandExecutor notReady = (sender, cmd, label, args) -> {
            tell(sender, "§eBlockVault is in setup mode: §f" + reason);
            if (sender.hasPermission("blockvault.setup")) {
                tell(sender, "§7Check progress with §f/bvsetup status§7.");
            }
            return true;
        };
        for (String name : getDescription().getCommands().keySet()) {
            if (!name.equals("bvsetup")) register(name, notReady);
        }
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
