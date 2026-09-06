package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ItemFrame;

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
                Location head = plugin.resolve(entry.head());
                if (head.getWorld() != null) head.getBlock().setType(Material.AIR, false);
                String who = plugin.getServer().getOfflinePlayer(r.donor()).getName();
                plugin.tell(sender, "§aRevoked " + material + " from §f" + who
                        + "§a; refunded " + r.pointsRefunded() + " points.");
            });
        });
    }

    private void repair(CommandSender sender) {
        World world = plugin.originWorld();
        if (world == null) {
            plugin.tell(sender, "§cOrigin world is not loaded.");
            return;
        }
        int frames = 0;
        for (TargetEntry e : plugin.manifest().entries().values()) {
            Location frameLoc = plugin.resolve(e.frame());
            if (!hasItemFrame(world, frameLoc)) {
                world.spawn(frameLoc, ItemFrame.class, f -> {
                    f.setFacingDirection(e.face(), true);
                    f.setFixed(true);
                    f.setInvulnerable(true);
                    f.setSilent(true);
                    f.setGlowing("rare".equals(e.rarity())); // highlight rare-tier shelves
                });
                frames++;
            }
        }
        // Heads are reconciled by the standard pass.
        plugin.tell(sender, "§aRepair: respawned " + frames + " missing frames. "
                + "Running head reconciliation…");
        new VaultUtil(plugin).updateVaultState(sender);
    }

    private boolean hasItemFrame(World world, Location loc) {
        for (var ent : world.getNearbyEntities(loc, 0.5, 0.5, 0.5)) {
            if (ent instanceof ItemFrame) return true;
        }
        return false;
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
