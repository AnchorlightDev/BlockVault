package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only player commands, one dispatcher for all of them:
 * {@code /bvfind /bvinfo /bvmissing /bvcheck /bvme /bvhistory /bvedition /bvreload}.
 */
public final class QueryCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE = 10;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BlockVault plugin;

    public QueryCommand(BlockVault plugin) {
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
            case "bvfind" -> find(sender, args);
            case "bvinfo" -> info(sender, args);
            case "bvmissing" -> missing(sender, args);
            case "bvcheck" -> check(sender);
            case "bvme" -> me(sender);
            case "bvhistory" -> history(sender, args);
            case "bvedition" -> edition(sender);
            case "bvreload" -> reload(sender);
            default -> plugin.tell(sender, "§cUnknown command.");
        }
        return true;
    }

    private TargetEntry require(CommandSender sender, String[] args) {
        if (args.length == 0) {
            plugin.tell(sender, "§cUsage: /<command> <block>");
            return null;
        }
        String key = args[0].toLowerCase().replace("minecraft:", "");
        TargetEntry e = plugin.manifest().entry(key);
        if (e == null) plugin.tell(sender, "§c'" + key + "' is not part of this collection.");
        return e;
    }

    private String pretty(TargetEntry e) {
        Material m = Material.matchMaterial(e.material());
        return m != null ? VaultUtil.formatMaterialName(m) : e.material();
    }

    private void find(CommandSender sender, String[] args) {
        TargetEntry e = require(sender, args);
        if (e == null) return;
        int[] s = e.sign();
        org.bukkit.Location loc = plugin.resolve(e.sign());
        String room = roomFor(e.chapter());
        plugin.tell(sender, "§6" + pretty(e)
                + " §7— Level " + e.chapter() + ", " + room);
        plugin.tell(sender, "§7  shelf at §f" + loc.getBlockX() + " " + loc.getBlockY() + " "
                + loc.getBlockZ() + " §7(relative " + s[0] + " " + s[1] + " " + s[2] + ", facing " + e.facing() + ")");
    }

    private void info(CommandSender sender, String[] args) {
        TargetEntry e = require(sender, args);
        if (e == null) return;
        int pts = plugin.getConfig().getInt("points." + e.rarity(), 1);
        boolean have = plugin.database().isCollected(e.material());
        plugin.tell(sender, "§6" + pretty(e));
        plugin.tell(sender, "§7  rarity §f" + e.rarity() + " §7· points §f" + pts
                + " §7· chapter §f" + e.chapter() + " §7· section §f" + e.section());
        plugin.tell(sender, have ? "§a  already in the vault" : "§e  still needed");
    }

    private void missing(CommandSender sender, String[] args) {
        int chapter = args.length > 0 ? parseInt(args[0], plugin.chapters().current())
                                      : plugin.chapters().current();
        int page = args.length > 1 ? Math.max(1, parseInt(args[1], 1)) : 1;

        List<String> out = new ArrayList<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (e.chapter() == chapter && !plugin.database().isCollected(e.material())) {
                out.add(e.material());
            }
        }
        if (out.isEmpty()) {
            plugin.tell(sender, "§aChapter " + chapter + " is complete!");
            return;
        }
        int pages = (out.size() + PAGE - 1) / PAGE;
        page = Math.min(page, pages);
        plugin.tell(sender, "§6Chapter " + chapter + " — " + out.size()
                + " outstanding §7(page " + page + "/" + pages + ")");
        for (int i = (page - 1) * PAGE; i < Math.min(out.size(), page * PAGE); i++) {
            sender.sendMessage("§7 · §f" + out.get(i));
        }
        if (page < pages) plugin.tell(sender, "§7/bvmissing " + chapter + " " + (page + 1) + " for more");
    }

    private void check(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cPlayers only.");
            return;
        }
        List<String> needed = new ArrayList<>();
        for (ItemStack it : player.getInventory().getContents()) {
            if (it == null || it.getType() == Material.AIR) continue;
            String key = it.getType().getKey().getKey();
            TargetEntry e = plugin.manifest().entry(key);
            if (e != null && !plugin.database().isCollected(key) && !needed.contains(key)) {
                needed.add(key);
            }
        }
        if (needed.isEmpty()) {
            plugin.tell(sender, "§7Nothing in your inventory is currently needed.");
            return;
        }
        plugin.tell(sender, "§aThe vault needs " + needed.size() + " block(s) you're carrying:");
        needed.forEach(n -> sender.sendMessage("§7 · §f" + n + " §8— /bvsubmit"));
    }

    private void me(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cPlayers only.");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.MyStats s = plugin.database().myStats(player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.tell(player, "§6Your vault record");
                plugin.tell(player, "§7  blocks donated §f" + s.blocks()
                        + " §7· points §f" + s.points()
                        + " §7· rank §f" + (s.rank() > 0 ? "#" + s.rank() : "—"));
                plugin.tell(player, "§7  every donation is a first — one of each block is allowed.");
            });
        });
    }

    private void history(CommandSender sender, String[] args) {
        TargetEntry e = require(sender, args);
        if (e == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.SubmissionRow row = plugin.database().submission(e.material());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (row == null) {
                    plugin.tell(sender, "§e" + e.material() + " has not been donated yet.");
                    return;
                }
                String when = row.submittedAt().toLocalDateTime().format(DATE);
                plugin.tell(sender, "§6" + e.material() + " §7was donated by §f" + row.name()
                        + " §7on §f" + when + " §7(+" + row.points() + " pts)");
            });
        });
    }

    private void edition(CommandSender sender) {
        plugin.tell(sender, "§6Edition §f" + plugin.database().edition()
                + " §7· manifest version §f" + plugin.manifest().version()
                + " §7· data version §f" + plugin.manifest().dataVersion());
        plugin.tell(sender, "§7  " + plugin.manifest().entries().size()
                + " blocks, frozen for the season. New blocks require a new edition.");
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.invalidateRegion();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.database().resyncChapterDates());
        plugin.tell(sender, "§aConfiguration reloaded.");
    }

    // --- helpers -------------------------------------------------------

    private String roomFor(int chapter) {
        return switch (chapter) {
            case 1 -> "The Undercroft";
            case 2 -> "The Conservatory";
            case 3 -> "The Deep";
            case 4 -> "The Gallery";
            case 5 -> "The Forge";
            case 6 -> "The Observatory";
            default -> "?";
        };
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        boolean blockArg = (name.equals("bvfind") || name.equals("bvinfo") || name.equals("bvhistory"))
                && args.length == 1;
        if (!blockArg) return List.of();
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<>();
        for (String m : plugin.manifest().entries().keySet()) {
            if (m.startsWith(prefix)) out.add(m);
            if (out.size() >= 50) break;
        }
        return out;
    }
}
