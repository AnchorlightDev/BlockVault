package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Admin dispatcher: {@code /bvrevoke /bvrepair /bvbackup}. */
public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final BlockVault plugin;

    public AdminCommand(BlockVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (!sender.hasPermission("blockvault." + name.substring(2))) {
            plugin.tell(sender, "§cYou don't have permission to use this command.");
            return true;
        }
        switch (name) {
            case "bvrevoke" -> revoke(sender, args);
            case "bvrepair" -> repair(sender);
            case "bvbackup" -> backup(sender);
            default -> plugin.tell(sender, "§cUnknown command.");
        }
        return true;
    }

    private void revoke(CommandSender sender, String[] args) {
        if (args.length == 0) {
            plugin.tell(sender, "§cUsage: /bvrevoke <block>");
            return;
        }
        String material = args[0].toLowerCase().replace("minecraft:", "");
        TargetEntry entry = plugin.manifest().entry(material);
        if (entry == null) {
            plugin.tell(sender, "§c'" + material + "' is not part of this collection.");
            return;
        }
        java.util.UUID actor = (sender instanceof org.bukkit.entity.Player p) ? p.getUniqueId() : null;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.RevokeResult r = plugin.database().revoke(material, actor);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!r.ok()) {
                    plugin.tell(sender, "§e" + material + " has no submission to revoke.");
                    return;
                }
                // Every tally for the block is gone, so the niche goes back to a plain head.
                new VaultUtil(plugin).applyNiche(entry, null);
                plugin.displays().refresh();
                plugin.chapters().check();
                String who = plugin.getServer().getOfflinePlayer(r.donor()).getName();
                plugin.tell(sender, "§aRevoked " + material + " (first donated by §f" + who
                        + "§a); refunded " + r.pointsRefunded() + " points and cleared every donation of it.");
            });
        });
    }

    private void repair(CommandSender sender) {
        if (plugin.originWorld() == null) {
            plugin.tell(sender, "§cOrigin world is not loaded.");
            return;
        }
        // Missing heads are rebuilt from the manifest and every floating item is
        // respawned or corrected by the standard reconcile pass.
        plugin.tell(sender, "§aRepair: rebuilding niche heads and item displays…");
        new VaultUtil(plugin).updateVaultState(sender);
        plugin.displays().refresh();
    }

    private void backup(CommandSender sender) {
        var cfg = plugin.getConfig();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File dir = new File(plugin.getDataFolder(), "backups");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, "blockvault-" + stamp + ".sql");

        List<String> cmd = new ArrayList<>(List.of(
                "mysqldump",
                "-h", cfg.getString("database.host", "localhost"),
                "-P", String.valueOf(cfg.getInt("database.port", 3306)),
                "-u", cfg.getString("database.user", "blockvault"),
                "--databases", cfg.getString("database.name", "blockvault")));

        plugin.tell(sender, "§7Starting backup to " + out.getName() + "…");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = false;
            String detail;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.environment().put("MYSQL_PWD", cfg.getString("database.password", ""));
                pb.redirectErrorStream(false);
                pb.redirectOutput(out);
                Process p = pb.start();
                int code = p.waitFor();
                ok = code == 0 && out.length() > 0;
                detail = ok ? (out.length() / 1024) + " KiB" : "mysqldump exit " + code;
            } catch (Exception ex) {
                detail = ex.getMessage();
            }
            final boolean done = ok;
            final String msg = detail;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (done) plugin.tell(sender, "§aBackup complete: " + out.getName() + " (" + msg + ")");
                else plugin.tell(sender, "§cBackup failed: " + msg
                        + " §7(is mysqldump on the server's PATH?)");
            });
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("bvrevoke") || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<>();
        for (String m : plugin.database().collectedSnapshot()) {
            if (m.startsWith(prefix)) out.add(m);
        }
        return out;
    }
}
