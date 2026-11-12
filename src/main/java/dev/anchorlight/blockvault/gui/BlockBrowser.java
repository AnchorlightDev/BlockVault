package dev.anchorlight.blockvault.gui;

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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * {@code /bvblocks [needed|unlocked|top|carrying] [floor]} - a read-only chest
 * menu of every block on the open floors: whether it's still needed, who its top
 * contributor is, how many you've given and how many more you'd need to take
 * the top spot. Filter by floor and by status, page through, click a block to
 * see where its niche is.
 *
 * <p>The standings are loaded once (async) when the menu opens; paging and
 * filtering re-use that snapshot, and the Refresh button reloads it. Floors
 * that haven't opened stay hidden unless the viewer has
 * {@code blockvault.spoilers}, like the other query commands.
 */
public final class BlockBrowser implements CommandExecutor, TabCompleter, Listener {

    private static final String TITLE = "Block Vault - Blocks";
    private static final int PER_PAGE = 45; // five rows of blocks, bottom row is controls

    private static final int SLOT_PREV = 45;
    private static final int SLOT_FLOOR = 47;
    private static final int SLOT_FILTER = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_REFRESH = 50;
    private static final int SLOT_CLOSE = 52;
    private static final int SLOT_NEXT = 53;

    enum Filter {
        ALL("All blocks"),
        NEEDED("Still needed"),
        UNLOCKED("Unlocked - compete for top"),
        TOP("Blocks you top"),
        CARRYING("In your inventory");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next(boolean back) {
            Filter[] all = values();
            return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
        }
    }

    /** One open menu: the viewer's snapshot plus the current page and filters. */
    private static final class View implements InventoryHolder {
        private Inventory inventory;
        private final UUID viewer;
        private Map<String, Database.TopDonor> tops;
        private Map<String, Long> mine;
        private Set<String> collected;
        private Set<String> carrying;
        private int floor; // 0 = every open floor
        private Filter filter;
        private int page;
        private List<TargetEntry> shown = List.of();

        View(UUID viewer, Filter filter, int floor) {
            this.viewer = viewer;
            this.filter = filter;
            this.floor = floor;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlockVault plugin;

    public BlockBrowser(BlockVault plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cOnly players can use this command.");
            return true;
        }
        if (!player.hasPermission("blockvault.blocks")) {
            plugin.tell(player, "§cYou don't have permission to use this command.");
            return true;
        }
        if (!plugin.isVaultStarted() && !plugin.isVaultFinished()) {
            plugin.tell(player, "§cThe vault has not been opened yet.");
            return true;
        }
        Filter filter = Filter.ALL;
        int floor = 0;
        for (String a : args) {
            Filter f = parseFilter(a);
            if (f != null) {
                filter = f;
                continue;
            }
            try {
                int n = Integer.parseInt(a);
                if (!canSee(player, n)) {
                    plugin.tell(player, "§7Floor " + n + " hasn't opened yet - its blocks are a surprise.");
                    return true;
                }
                floor = n;
            } catch (NumberFormatException e) {
                plugin.tell(player, "§cUsage: /bvblocks [needed|unlocked|top|carrying] [floor]");
                return true;
            }
        }
        open(player, new View(player.getUniqueId(), filter, floor));
        return true;
    }

    private static Filter parseFilter(String raw) {
        return switch (raw.toLowerCase(Locale.ENGLISH)) {
            case "all" -> Filter.ALL;
            case "needed", "missing" -> Filter.NEEDED;
            case "unlocked", "compete" -> Filter.UNLOCKED;
            case "top", "mine" -> Filter.TOP;
            case "carrying", "inventory", "inv" -> Filter.CARRYING;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length < 1) return List.of();
        String p = args[args.length - 1].toLowerCase(Locale.ENGLISH);
        List<String> out = new ArrayList<>();
        for (String s : List.of("needed", "unlocked", "top", "carrying")) if (s.startsWith(p)) out.add(s);
        for (int ch : visibleFloors(sender)) if (String.valueOf(ch).startsWith(p)) out.add(String.valueOf(ch));
        return out;
    }

    // ------------------------------------------------------------------ open / load

    /** Load the standings off the main thread, then build and show the menu. */
    private void open(Player player, View view) {
        load(player, view, () -> {
            view.inventory = plugin.getServer().createInventory(view, 54, TITLE);
            render(player, view);
            player.openInventory(view.inventory);
        });
    }

    private void load(Player player, View view, Runnable then) {
        view.carrying = carried(player);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Database.TopDonor> tops = plugin.database().topDonors();
            Map<String, Long> mine = plugin.database().myDonations(view.viewer);
            Set<String> collected = plugin.database().collectedSnapshot();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (tops == null || mine == null) {
                    plugin.tell(player, "§cCouldn't load the vault right now - please try again.");
                    return;
                }
                view.tops = tops;
                view.mine = mine;
                view.collected = collected;
                then.run();
            });
        });
    }

    private Set<String> carried(Player player) {
        Set<String> out = new HashSet<>();
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it != null && it.getType() != Material.AIR) out.add(it.getType().getKey().getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ render

    @SuppressWarnings("deprecation") // legacy text, like the rest of the plugin
    private void render(Player player, View view) {
        List<TargetEntry> list = new ArrayList<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (!canSee(player, e.chapter())) continue;
            if (view.floor != 0 && e.chapter() != view.floor) continue;
            if (matches(view, e)) list.add(e);
        }
        view.shown = list;
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        view.page = Math.max(0, Math.min(view.page, pages - 1));

        Inventory inv = view.inventory;
        inv.clear();
        int from = view.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            inv.setItem(i, blockItem(view, list.get(from + i)));
        }
        if (list.isEmpty()) {
            inv.setItem(22, item(Material.PAPER, "§7Nothing here",
                    List.of("§8No blocks match " + view.filter.label.toLowerCase(Locale.ENGLISH),
                            "§8on " + floorLabel(view.floor).toLowerCase(Locale.ENGLISH) + ".")));
        }

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (view.page > 0) {
            inv.setItem(SLOT_PREV, item(Material.ARROW, "§fPrevious page", List.of("§7Page " + view.page + " of " + pages)));
        }
        if (view.page < pages - 1) {
            inv.setItem(SLOT_NEXT, item(Material.ARROW, "§fNext page", List.of("§7Page " + (view.page + 2) + " of " + pages)));
        }
        inv.setItem(SLOT_FLOOR, item(Material.LADDER, "§eFloor: §f" + floorLabel(view.floor),
                List.of("§7Left-click: next floor", "§7Right-click: previous floor")));
        List<String> filterLore = new ArrayList<>();
        for (Filter f : Filter.values()) filterLore.add((f == view.filter ? "§a▶ " : "§8  ") + f.label);
        filterLore.add("");
        filterLore.add("§7Left-click: next · Right-click: previous");
        inv.setItem(SLOT_FILTER, item(Material.HOPPER, "§eShowing: §f" + view.filter.label, filterLore));
        inv.setItem(SLOT_INFO, infoItem(view, list.size(), pages));
        inv.setItem(SLOT_REFRESH, item(Material.CLOCK, "§fRefresh", List.of("§7Reload the latest standings")));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "§cClose", List.of()));
    }

    private boolean matches(View view, TargetEntry e) {
        boolean unlocked = view.collected.contains(e.material());
        Database.TopDonor top = view.tops.get(e.material());
        return switch (view.filter) {
            case ALL -> true;
            case NEEDED -> !unlocked;
            case UNLOCKED -> unlocked;
            case TOP -> top != null && top.uuid().equals(view.viewer);
            case CARRYING -> view.carrying.contains(e.material());
        };
    }

    @SuppressWarnings("deprecation")
    private ItemStack blockItem(View view, TargetEntry e) {
        String material = e.material();
        Material m = Material.matchMaterial(material);
        boolean asItem = m != null && m.isItem() && m != Material.AIR;
        String name = m != null ? VaultUtil.formatMaterialName(m) : material;
        boolean unlocked = view.collected.contains(material);
        boolean featured = plugin.featured() != null && plugin.featured().isFeatured(material);
        int pts = plugin.getConfig().getInt("points." + e.rarity(), 1);
        Database.TopDonor top = view.tops.get(material);
        long given = view.mine.getOrDefault(material, 0L);
        boolean iTop = top != null && top.uuid().equals(view.viewer);

        List<String> lore = new ArrayList<>();
        lore.add("§8Floor " + e.chapter() + " · " + e.section());
        lore.add("§7Rarity: " + rarityColour(e.rarity()) + capitalise(e.rarity()));
        if (featured) lore.add("§b⭐ Featured this week §7(double points)");
        lore.add("");
        if (!unlocked) {
            int unlockPts = plugin.featured() != null && plugin.featured().doublesUnlock(material) ? pts * 2 : pts;
            lore.add("§eStill needed!");
            lore.add("§7First to give it unlocks it for §f" + unlockPts + " §7point" + (unlockPts == 1 ? "" : "s") + ".");
        } else if (top == null) {
            lore.add("§aUnlocked");
            lore.add("§7No top contributor yet - give one to take it.");
        } else if (iTop) {
            lore.add("§6★ You're the top contributor");
            lore.add("§7You've given §f" + top.quantity());
        } else {
            long need = top.quantity() - given + 1; // ties go to whoever got there first
            lore.add("§7Top contributor: §f" + top.name() + " §7(" + top.quantity() + ")");
            lore.add("§7You've given §f" + given);
            lore.add("§eGive §f" + need + " §emore to take the top spot");
        }
        if (view.carrying.contains(material)) lore.add("§aYou're carrying some");
        lore.add("");
        lore.add("§8Click to see where it is in the vault");

        ItemStack it = item(asItem ? m : Material.PAPER, (unlocked ? "§f" : "§e") + name, lore);
        if (featured || iTop) {
            ItemMeta meta = it.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack infoItem(View view, int count, int pages) {
        int unlockedHere = 0, topHere = 0;
        for (TargetEntry e : view.shown) {
            if (view.collected.contains(e.material())) unlockedHere++;
            Database.TopDonor t = view.tops.get(e.material());
            if (t != null && t.uuid().equals(view.viewer)) topHere++;
        }
        int topAll = 0;
        for (Database.TopDonor t : view.tops.values()) if (t.uuid().equals(view.viewer)) topAll++;
        List<String> lore = new ArrayList<>();
        lore.add("§7" + count + " block" + (count == 1 ? "" : "s") + " shown · page " + (view.page + 1) + " of " + pages);
        if (view.filter == Filter.ALL) lore.add("§7" + unlockedHere + " unlocked, " + (count - unlockedHere) + " still needed");
        lore.add("§7You're top contributor on §f" + topAll + " §7block" + (topAll == 1 ? "" : "s")
                + (view.floor != 0 ? " §8(" + topHere + " here)" : ""));
        if (plugin.getConfig().getBoolean("weekly-bonus.enabled", true)) {
            lore.add("");
            lore.add("§7Top contributors earn §f" + plugin.getConfig().getInt("weekly-bonus.points-per-block", 2)
                    + " pts §7a block every week");
            lore.add("§8(up to " + plugin.getConfig().getInt("weekly-bonus.cap-blocks", 10) + " blocks)");
        }
        lore.add("");
        lore.add("§8Glowing = featured, or you're top");
        return item(Material.BOOK, "§6Block Vault", lore);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            it.setItemMeta(meta);
        }
        return it;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof View view)) return;
        e.setCancelled(true); // read-only, both halves of the screen
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        boolean back = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;

        if (slot < PER_PAGE) {
            int index = view.page * PER_PAGE + slot;
            if (index >= view.shown.size()) return;
            String material = view.shown.get(index).material();
            player.closeInventory();
            player.performCommand("bvfind " + material);
            return;
        }
        switch (slot) {
            case SLOT_PREV -> { if (view.page > 0) { view.page--; render(player, view); } }
            case SLOT_NEXT -> { view.page++; render(player, view); }
            case SLOT_FLOOR -> {
                List<Integer> floors = new ArrayList<>();
                floors.add(0);
                floors.addAll(visibleFloors(player));
                int i = floors.indexOf(view.floor);
                view.floor = floors.get((Math.max(i, 0) + (back ? floors.size() - 1 : 1)) % floors.size());
                view.page = 0;
                render(player, view);
            }
            case SLOT_FILTER -> {
                view.filter = view.filter.next(back);
                view.page = 0;
                render(player, view);
            }
            case SLOT_REFRESH -> load(player, view, () -> render(player, view));
            case SLOT_CLOSE -> player.closeInventory();
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ helpers

    private boolean canSee(CommandSender sender, int chapter) {
        return plugin.chapters().isOpen(chapter) || sender.hasPermission("blockvault.spoilers");
    }

    /** Floors with blocks this sender may see, in order. */
    private Set<Integer> visibleFloors(CommandSender sender) {
        Set<Integer> out = new TreeSet<>();
        for (TargetEntry e : plugin.manifest().entries().values()) {
            if (canSee(sender, e.chapter())) out.add(e.chapter());
        }
        return out;
    }

    private static String floorLabel(int floor) {
        return floor == 0 ? "All open floors" : "Floor " + floor;
    }

    private static String rarityColour(String rarity) {
        return switch (rarity) {
            case "rare" -> "§d";
            case "uncommon" -> "§9";
            default -> "§a";
        };
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
