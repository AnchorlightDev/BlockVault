package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Staff collusion-review tools: {@code /bvmonitor history <player>},
 * {@code recent [n]}, {@code adjust <player> <block> <delta>},
 * {@code remove <player> <block>}. Review-and-adjust only; nothing is blocked
 * automatically.
 */
public final class MonitorCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private final BlockVault plugin;
    private final VaultUtil vaultUtil;

    public MonitorCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.monitor")) {
            plugin.tell(sender, "§cYou don't have permission to use this command.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ENGLISH) : "";
        switch (sub) {
            case "history" -> history(sender, args);
            case "recent" -> recent(sender, args);
            case "adjust" -> adjust(sender, args);
            case "remove" -> remove(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        plugin.tell(sender, "§cUsage: /bvmonitor history <player> §8| §crecent [n] §8| "
                + "§cadjust <player> <block> <delta> §8| §cremove <player> <block>");
    }

    private void history(CommandSender sender, String[] args) {
        if (args.length < 2) { plugin.tell(sender, "§cUsage: /bvmonitor history <player>"); return; }
        String name = args[1];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = resolve(name);
            if (uuid == null) { main(() -> plugin.tell(sender, "§cNo vault contributor called '" + name + "'.")); return; }
            List<Database.ContribRow> rows = plugin.database().contributionHistory(uuid, 20);
            main(() -> {
                if (rows.isEmpty()) { plugin.tell(sender, "§7" + name + " has no logged contributions."); return; }
                plugin.tell(sender, "§6Last " + rows.size() + " contributions by §f" + name + "§6:");
                for (Database.ContribRow r : rows) {
                    plugin.tell(sender, "§7" + WHEN.format(r.ts().toInstant().atZone(plugin.zone()))
                            + " §f" + r.amount() + "× " + pretty(r.material())
                            + " §8(" + r.method() + (r.firstUnlock() ? ", unlock" : "") + ")");
                }
            });
        });
    }

    private void recent(CommandSender sender, String[] args) {
        int n = 15;
        if (args.length > 1) {
            try { n = Math.max(1, Math.min(50, Integer.parseInt(args[1]))); }
            catch (NumberFormatException e) { plugin.tell(sender, "§cUsage: /bvmonitor recent [n]"); return; }
        }
        final int limit = n;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.ContribRow> rows = plugin.database().recentContributions(limit);
            main(() -> {
                if (rows.isEmpty()) { plugin.tell(sender, "§7No contributions logged yet."); return; }
                plugin.tell(sender, "§6" + rows.size() + " most recent contributions:");
                for (Database.ContribRow r : rows) {
                    plugin.tell(sender, "§7" + WHEN.format(r.ts().toInstant().atZone(plugin.zone()))
                            + " §f" + r.name() + " §7" + r.amount() + "× " + pretty(r.material())
                            + " §8(" + r.method() + (r.firstUnlock() ? ", unlock" : "") + ")");
                }
            });
        });
    }

    private void adjust(CommandSender sender, String[] args) {
        if (args.length < 4) { plugin.tell(sender, "§cUsage: /bvmonitor adjust <player> <block> <delta>"); return; }
        String name = args[1];
        String material = args[2].toLowerCase(Locale.ENGLISH).replace("minecraft:", "");
        int delta;
        try { delta = Integer.parseInt(args[3]); }
        catch (NumberFormatException e) { plugin.tell(sender, "§cDelta must be a whole number (e.g. -64)."); return; }
        if (plugin.manifest().entry(material) == null) {
            plugin.tell(sender, "§c'" + material + "' is not part of this collection.");
            return;
        }
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = resolve(name);
            if (uuid == null) { main(() -> plugin.tell(sender, "§cNo vault contributor called '" + name + "'.")); return; }
            boolean ok = plugin.database().adjustDonation(uuid, material, delta, actor);
            main(() -> finishEdit(sender, ok, name, material,
                    ok ? "§aAdjusted " + name + "'s " + pretty(material) + " tally by " + delta + "."
                       : "§e" + name + " has no donation of " + pretty(material) + " to adjust."));
        });
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 3) { plugin.tell(sender, "§cUsage: /bvmonitor remove <player> <block>"); return; }
        String name = args[1];
        String material = args[2].toLowerCase(Locale.ENGLISH).replace("minecraft:", "");
        if (plugin.manifest().entry(material) == null) {
            plugin.tell(sender, "§c'" + material + "' is not part of this collection.");
            return;
        }
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = resolve(name);
            if (uuid == null) { main(() -> plugin.tell(sender, "§cNo vault contributor called '" + name + "'.")); return; }
            boolean ok = plugin.database().removeDonation(uuid, material, actor);
            main(() -> finishEdit(sender, ok, name, material,
                    ok ? "§aRemoved " + name + "'s " + pretty(material) + " donations."
                       : "§e" + name + " has no donation of " + pretty(material) + " to remove."));
        });
    }

    /** Tell the result and, on a change, redraw the wall - the top contributor may have changed. */
    private void finishEdit(CommandSender sender, boolean ok, String name, String material, String msg) {
        plugin.tell(sender, msg);
        if (ok) {
            vaultUtil.updateVaultState(null); // reconcile niche heads and labels
            plugin.displays().refresh();
            plugin.tell(sender, "§7Niche heads refreshed. §8(unlock/points are unchanged; use /bvrevoke to reverse an unlock)");
        }
    }

    /**
     * Resolve a name to a UUID from the vault's own contributor records (the name
     * they last donated under). Database only, so it's safe off the main thread
     * and finds any donor, online or not. Blocking - call async.
     */
    private UUID resolve(String name) {
        return plugin.database().uuidByName(name);
    }

    private void main(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static String pretty(String material) {
        Material m = Material.matchMaterial(material);
        return m != null ? VaultUtil.formatMaterialName(m) : material;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ENGLISH);
            return java.util.stream.Stream.of("history", "recent", "adjust", "remove")
                    .filter(s -> s.startsWith(p)).toList();
        }
        // Block-name completion for the material argument of adjust/remove.
        if (args.length == 3 && (args[0].equalsIgnoreCase("adjust") || args[0].equalsIgnoreCase("remove"))) {
            String p = args[2].toLowerCase(Locale.ENGLISH);
            List<String> out = new java.util.ArrayList<>();
            for (String m : plugin.database().collectedSnapshot()) {
                if (m.startsWith(p)) out.add(m);
                if (out.size() >= 50) break;
            }
            return out;
        }
        return List.of();
    }
}
