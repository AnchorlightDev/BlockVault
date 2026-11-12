package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Staff: {@code /bvfeatured [set <block>|clear]} - show or override the featured block. */
public final class FeaturedCommand implements CommandExecutor, TabCompleter {

    private final BlockVault plugin;

    public FeaturedCommand(BlockVault plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blockvault.featured")) {
            plugin.tell(sender, "§cYou don't have permission to use this command.");
            return true;
        }
        if (args.length == 0) {
            show(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ENGLISH);
        switch (sub) {
            case "set" -> set(sender, args);
            case "clear" -> clear(sender);
            default -> plugin.tell(sender, "§cUsage: /bvfeatured §7(show)§c, /bvfeatured set <block>§c, /bvfeatured clear");
        }
        return true;
    }

    private void show(CommandSender sender) {
        var featured = plugin.featured().featured();
        if (featured.isEmpty()) {
            plugin.tell(sender, "§7No featured block this week yet. §8(set one with /bvfeatured set <block>)");
            return;
        }
        plugin.tell(sender, "§6Featured block: §b"
                + String.join(", ", featured.stream().map(FeaturedCommand::pretty).toList())
                + " §7(double points)");
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.tell(sender, "§cUsage: /bvfeatured set <block>");
            return;
        }
        if (!plugin.getConfig().getBoolean("featured-block.enabled", true)) {
            plugin.tell(sender, "§cFeatured blocks are turned off (featured-block.enabled: false).");
            return;
        }
        String material = args[1].toLowerCase(Locale.ENGLISH).replace("minecraft:", "");
        TargetEntry entry = plugin.manifest().entry(material);
        if (entry == null) {
            plugin.tell(sender, "§c'" + material + "' is not part of this collection.");
            return;
        }
        if (!plugin.chapters().isOpen(entry.chapter())) {
            plugin.tell(sender, "§c" + pretty(material) + " is on a floor that hasn't opened - pick an unlocked block.");
            return;
        }
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;
        String weekKey = plugin.featured().currentWeekKey();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.database().setFeatured(weekKey, List.of(material), actor);
            plugin.featured().refresh();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ok) {
                    plugin.tell(sender, "§aFeatured block is now §b" + pretty(material) + "§a (double points).");
                    plugin.displays().refresh();
                } else {
                    plugin.tell(sender, "§cCould not set the featured block - see the console.");
                }
            });
        });
    }

    private void clear(CommandSender sender) {
        UUID actor = (sender instanceof Player p) ? p.getUniqueId() : null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.database().clearFeatured(actor);
            plugin.featured().refresh();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.tell(sender, ok ? "§aCleared the current featured block."
                        + (plugin.featured().featured().isEmpty()
                           ? " §7A new one will be picked automatically within a minute." : "")
                        : "§7There was no featured block to clear.");
                plugin.displays().refresh();
            });
        });
    }

    private static String pretty(String material) {
        Material m = Material.matchMaterial(material);
        return m != null ? VaultUtil.formatMaterialName(m) : material;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ENGLISH);
            return java.util.stream.Stream.of("set", "clear").filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            String p = args[1].toLowerCase(Locale.ENGLISH);
            List<String> out = new ArrayList<>();
            for (TargetEntry e : plugin.manifest().entries().values()) {
                if (plugin.chapters().isOpen(e.chapter()) && e.material().startsWith(p)) out.add(e.material());
                if (out.size() >= 50) break;
            }
            return out;
        }
        return List.of();
    }
}
