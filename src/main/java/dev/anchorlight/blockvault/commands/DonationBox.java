package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.HeadUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The donation chest behind {@code /bvsubmit}: a 54-slot menu the player fills
 * with whatever they want to give, then closes.
 *
 * <p>On close the menu is emptied straight away, so the items are never in two
 * places. Anything the vault doesn't take (not on the list, another edition, a
 * container with contents) goes back to the player at once. The rest is
 * written to the database one block type at a time; any type whose write fails
 * is handed back too. Nothing is destroyed unless its donation committed.
 */
public final class DonationBox implements Listener {

    private static final String TITLE = "Donate to the Vault";

    private final BlockVault plugin;
    private final VaultUtil vaultUtil;
    /**
     * Items owed to players who logged off before a failed write could hand them
     * back. Mirrored to {@code owed_items.yml} so a restart doesn't lose them.
     */
    private final Map<UUID, List<ItemStack>> owed = new ConcurrentHashMap<>();
    private final File owedFile;

    public DonationBox(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.owedFile = new File(plugin.getDataFolder(), "owed_items.yml");
        loadOwed();
    }

    private void loadOwed() {
        if (!owedFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(owedFile);
        for (String key : y.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                List<ItemStack> items = new ArrayList<>();
                List<?> raw = y.getList(key);
                if (raw != null) for (Object o : raw) if (o instanceof ItemStack it) items.add(it);
                if (!items.isEmpty()) owed.put(uuid, items);
            } catch (IllegalArgumentException ignored) {
                // not a uuid key - skip
            }
        }
        if (!owed.isEmpty()) {
            plugin.getLogger().info("Holding returned donation items for " + owed.size()
                    + " offline player(s) until they rejoin.");
        }
    }

    /** Write the owed items to disk (main thread; small file). */
    private void saveOwed() {
        YamlConfiguration y = new YamlConfiguration();
        owed.forEach((uuid, items) -> y.set(uuid.toString(), new ArrayList<>(items)));
        try {
            y.save(owedFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save owed_items.yml: " + e.getMessage());
        }
    }

    /** Hand items back to a player (or hold them until they rejoin). Main thread. */
    void returnItems(Player player, List<ItemStack> items) {
        giveBack(player, items);
    }

    /** Marks our menu so the close handler only reacts to it. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @SuppressWarnings("deprecation") // String title: the project uses legacy text throughout
    public void open(Player player) {
        Holder h = new Holder();
        h.inventory = plugin.getServer().createInventory(h, 54, TITLE);
        player.openInventory(h.inventory);
        plugin.tell(player, "§7Put in everything you want to give, then close the chest. "
                + "Anything the vault doesn't need comes straight back.");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        if (!(e.getPlayer() instanceof Player player)) return;

        // Take everything out now so nothing can be duplicated or lost in the menu.
        List<ItemStack> given = new ArrayList<>();
        for (ItemStack it : e.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR && it.getAmount() > 0) given.add(it.clone());
        }
        e.getInventory().clear();
        if (given.isEmpty()) return;
        donate(player, given, "chest");
    }

    /**
     * Donate items already taken from the player (the chest, or
     * {@code /bvsubmit new}). Anything not recorded goes back to them.
     *
     * @param method how it was submitted ('chest' or 'new'), for the monitoring log
     */
    void donate(Player player, List<ItemStack> given, String method) {
        // Conditions may have changed while the chest was open (left the vault, event closed...).
        if (!SubmitCommand.canSubmit(plugin, player)) {
            giveBack(player, given);
            plugin.tell(player, "§7Your items have been returned.");
            return;
        }

        Map<TargetEntry, List<ItemStack>> byBlock = new LinkedHashMap<>();
        List<ItemStack> rejected = new ArrayList<>();
        List<ItemStack> locked = new ArrayList<>();
        boolean hadFullContainer = false;
        for (ItemStack it : given) {
            TargetEntry entry = plugin.manifest().entry(it.getType().getKey().getKey());
            if (entry == null) {
                rejected.add(it);
            } else if (SubmitCommand.hasContents(it)) {
                rejected.add(it);
                hadFullContainer = true;
            } else if (!plugin.chapters().isOpen(entry.chapter())) {
                locked.add(it);
            } else {
                byBlock.computeIfAbsent(entry, k -> new ArrayList<>()).add(it);
            }
        }
        if (!rejected.isEmpty()) {
            giveBack(player, rejected);
            int n = rejected.stream().mapToInt(ItemStack::getAmount).sum();
            plugin.tell(player, "§7Returned §f" + n + " §7item" + (n == 1 ? "" : "s")
                    + " the vault doesn't collect" + (hadFullContainer ? " §8(including a container that wasn't empty)" : "") + ".");
        }
        if (!locked.isEmpty()) {
            giveBack(player, locked);
            int n = locked.stream().mapToInt(ItemStack::getAmount).sum();
            plugin.tell(player, "§7Returned §f" + n + " §7item" + (n == 1 ? "" : "s")
                    + " for floors that haven't opened yet.");
        }
        if (byBlock.isEmpty()) return;

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String profileJson = HeadUtil.toJson(player.getPlayerProfile());
        Database db = plugin.database();
        plugin.tell(player, "§7Donating " + byBlock.size() + " kind" + (byBlock.size() == 1 ? "" : "s") + " of block…");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<TargetEntry, Database.SubmitResult> results = new LinkedHashMap<>();
            for (var b : byBlock.entrySet()) {
                TargetEntry entry = b.getKey();
                int qty = b.getValue().stream().mapToInt(ItemStack::getAmount).sum();
                int firstPoints = plugin.getConfig().getInt("points." + entry.rarity(), 1);
                boolean featured = plugin.featured().isFeatured(entry.material());
                results.put(entry, db.submit(entry.material(), uuid, name, qty, firstPoints,
                        profileJson, method, featured));
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> finish(player, uuid, name, byBlock, results));
        });
    }

    /** Main thread: hand back failures, update the wall, and summarise. */
    private void finish(Player player, UUID uuid, String name, Map<TargetEntry, List<ItemStack>> byBlock,
                        Map<TargetEntry, Database.SubmitResult> results) {
        List<ItemStack> failed = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        int items = 0, kinds = 0, points = 0;
        String bestRarity = null;

        for (var r : results.entrySet()) {
            TargetEntry entry = r.getKey();
            Database.SubmitResult res = r.getValue();
            if (res.outcome() != Database.SubmitOutcome.OK) {
                failed.addAll(byBlock.get(entry));
                continue;
            }
            kinds++;
            items += byBlock.get(entry).stream().mapToInt(ItemStack::getAmount).sum();
            if (res.first()) {
                points += res.points();
                Material m = Material.matchMaterial(entry.material());
                unlocked.add(m != null ? VaultUtil.formatMaterialName(m) : entry.material());
                bestRarity = higher(bestRarity, entry.rarity());
                if ("rare".equals(entry.rarity())) {
                    plugin.webhook().rareSubmission(entry.material(), name, res.points());
                }
            }
            SubmitCommand.showOnWall(plugin, vaultUtil, entry, res.top());
        }

        if (!failed.isEmpty()) {
            giveBack(player, failed);
            plugin.getLogger().warning("Donation chest: " + failed.size() + " stack(s) from " + name
                    + " could not be recorded and were returned.");
        }
        if (!player.isOnline()) {
            if (kinds > 0) plugin.getLogger().info(name + " donated " + items + " items (" + kinds + " kinds) via the chest.");
        } else {
            if (kinds > 0) {
                plugin.tell(player, "§aDonated §f" + items + " §aitem" + (items == 1 ? "" : "s")
                        + " across §f" + kinds + " §ablock" + (kinds == 1 ? "" : "s") + ".");
            }
            if (!unlocked.isEmpty()) {
                String list = String.join(", ", unlocked.subList(0, Math.min(5, unlocked.size())))
                        + (unlocked.size() > 5 ? " and " + (unlocked.size() - 5) + " more" : "");
                plugin.tell(player, "§6You unlocked " + unlocked.size() + ": §f" + list
                        + " §7(+" + points + (points == 1 ? " point)" : " points)"));
                SubmitCommand.celebrate(player, bestRarity);
            } else if (kinds > 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
            }
            if (!failed.isEmpty()) {
                plugin.tell(player, "§cSome blocks couldn't be recorded right now and were returned. Please retry.");
            }
        }
        if (!unlocked.isEmpty()) plugin.chapters().check();
        if (kinds > 0) plugin.displays().refresh();
    }

    private static String higher(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String rarity) {
        if (rarity == null) return -1;
        return switch (rarity) {
            case "rare" -> 2;
            case "uncommon" -> 1;
            default -> 0;
        };
    }

    /** Return items to the player; overflow drops at their feet. Offline: keep until they join. */
    private void giveBack(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            owed.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).addAll(items);
            saveOwed();
            plugin.getLogger().warning("Holding " + items.size() + " stack(s) for " + player.getName()
                    + " until they rejoin (donation chest).");
            return;
        }
        var leftover = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        leftover.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        List<ItemStack> items = owed.remove(e.getPlayer().getUniqueId());
        if (items == null || items.isEmpty()) return;
        saveOwed();
        giveBack(e.getPlayer(), items);
        plugin.tell(e.getPlayer(), "§7Items from your last donation that couldn't be recorded have been returned.");
    }
}
