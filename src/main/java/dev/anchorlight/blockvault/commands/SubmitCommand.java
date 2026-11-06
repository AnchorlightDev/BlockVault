package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.HeadUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * {@code /bvsubmit} - opens the donation chest ({@link DonationBox}).
 * {@code /bvsubmit hand [amount]} (or {@code /bvsubmit <amount>}) - donate the
 * held block, the whole stack by default. {@code /bvsubmit new} - donate one
 * of every block in your inventory the vault hasn't got yet.
 *
 * <p>The first donation of a block unlocks it and earns its rarity points.
 * Later donations earn no points but add to the donor's tally for that block;
 * whoever has donated the most wears the niche head.
 *
 * <p>Order is always: write -> confirm commit -> consume. If the database is
 * unreachable the items stay with the player.
 */
public class SubmitCommand implements CommandExecutor {
    private final BlockVault plugin;
    private final VaultUtil vaultUtil;
    private final DonationBox box;

    public SubmitCommand(BlockVault plugin, DonationBox box) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
        this.box = box;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cOnly players can use this command!");
            return true;
        }
        if (!canSubmit(plugin, player)) return true;

        if (args.length == 0) {
            box.open(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("new")) {
            submitNew(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("hand")) {
            submitHand(player, args.length > 1 ? args[1] : null);
            return true;
        }
        submitHand(player, args[0]); // /bvsubmit <amount>
        return true;
    }

    /** Permission, event open, not creative, inside the vault. Tells the player why not. */
    static boolean canSubmit(BlockVault plugin, Player player) {
        if (!player.hasPermission("blockvault.submit")) {
            plugin.tell(player, "§cYou don't have permission to submit blocks.");
            return false;
        }
        if (plugin.isVaultFinished()) {
            plugin.tell(player, "§cThe vault is finished - it's now read-only. Thanks for playing!");
            return false;
        }
        if (!plugin.isVaultStarted()) {
            plugin.tell(player, "§cThe vault has not been opened yet.");
            return false;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            plugin.tell(player, "§cSubmissions are not allowed in creative mode.");
            return false;
        }
        var region = plugin.region();
        if (region == null || !region.contains(player.getLocation())) {
            plugin.tell(player, "§cYou must be inside the vault to submit a block.");
            return false;
        }
        return true;
    }

    /** A container item (shulker box etc.) that still has something in it. */
    static boolean hasContents(ItemStack item) {
        return item.getItemMeta() instanceof BlockStateMeta bsm
                && bsm.hasBlockState()
                && bsm.getBlockState() instanceof Container c
                && !c.getInventory().isEmpty();
    }

    /**
     * One of each block in the player's inventory that nobody has donated yet -
     * the quick way to bank every unlock you're carrying without giving up stacks.
     * The items leave the inventory first and go through the donation chest's
     * pipeline, which hands back anything that isn't recorded.
     */
    private void submitNew(Player player) {
        var inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        java.util.Set<String> picked = new java.util.HashSet<>();
        java.util.List<ItemStack> taken = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR || it.getAmount() < 1) continue;
            String material = it.getType().getKey().getKey();
            if (picked.contains(material)
                    || plugin.manifest().entry(material) == null
                    || plugin.database().isCollected(material)
                    || hasContents(it)) continue;
            ItemStack one = it.clone();
            one.setAmount(1);
            taken.add(one);
            picked.add(material);
            it.setAmount(it.getAmount() - 1);
            inv.setItem(i, it.getAmount() > 0 ? it : null);
        }
        if (taken.isEmpty()) {
            plugin.tell(player, "§7You're not carrying anything the vault doesn't already have. "
                    + "§8(/bvsubmit opens the donation chest for extra copies)");
            return;
        }
        box.donate(player, taken);
    }

    private void submitHand(Player player, String amountArg) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || held.getAmount() < 1) {
            plugin.tell(player, "§cYou are not holding anything. §7(/bvsubmit opens the donation chest)");
            return;
        }

        Material type = held.getType();
        String material = type.getKey().getKey(); // lowercase registry id
        String pretty = VaultUtil.formatMaterialName(type);

        TargetEntry entry = plugin.manifest().entry(material);
        if (entry == null) {
            plugin.tell(player, "§c" + pretty + " is not part of this collection.");
            return;
        }
        // A shulker box (or any container item) with contents would take its
        // contents with it - refuse rather than destroy someone's stash.
        if (hasContents(held)) {
            plugin.tell(player, "§cEmpty the " + pretty + " first - its contents would be lost.");
            return;
        }

        int amount = held.getAmount();
        if (amountArg != null) {
            try {
                amount = Integer.parseInt(amountArg);
            } catch (NumberFormatException e) {
                plugin.tell(player, "§cUsage: /bvsubmit §7(chest)§c, /bvsubmit new§7, §c/bvsubmit hand [amount]");
                return;
            }
            if (amount < 1) {
                plugin.tell(player, "§cAmount must be at least 1.");
                return;
            }
            if (amount > held.getAmount()) {
                plugin.tell(player, "§cYou're only holding " + held.getAmount() + ".");
                return;
            }
        }
        final int quantity = amount;
        final ItemStack template = held.clone();

        int firstPoints = plugin.getConfig().getInt("points." + entry.rarity(), 1);
        String profileJson = HeadUtil.toJson(player.getPlayerProfile());
        Database db = plugin.database();

        plugin.tell(player, "§7Donating " + quantity + "× " + pretty + "…");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.SubmitResult r = db.submit(material, player.getUniqueId(), player.getName(),
                    quantity, firstPoints, profileJson);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (r.outcome() != Database.SubmitOutcome.OK) {
                    plugin.tell(player,
                            "§cCould not record that right now - your items are still in your hand. Please retry.");
                    return;
                }
                // Write committed. Only now is it safe to consume the items.
                consume(player, template, quantity);

                if (r.first()) {
                    plugin.tell(player, "§aYou unlocked " + pretty + "! §7(+" + r.points()
                            + (r.points() == 1 ? " point)" : " points)"));
                    celebrate(player, entry.rarity());
                    if ("rare".equals(entry.rarity())) {
                        plugin.webhook().rareSubmission(material, player.getName(), r.points());
                    }
                    plugin.chapters().check();
                } else {
                    plugin.tell(player, "§aDonated " + quantity + "× " + pretty + ".");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
                }

                Database.TopDonor top = r.top();
                boolean iLead = top != null && top.uuid().equals(player.getUniqueId());
                plugin.tell(player, "§7  You've given §f" + r.myQuantity() + " §7in total. "
                        + (iLead ? "§6You're the top donor - the head is yours."
                                 : top == null ? "" : "§7Top donor: §f" + top.name() + " §7(" + top.quantity() + ")"));

                showOnWall(plugin, vaultUtil, entry, top);
                plugin.displays().refresh();
            });
        });
    }

    /**
     * Niche head and label follow the top donor, but only on an open floor
     * (locked floors stay plain; the reconcile pass fixes them on unlock).
     */
    static void showOnWall(BlockVault plugin, VaultUtil vaultUtil, TargetEntry entry, Database.TopDonor top) {
        if (!plugin.chapters().isOpen(entry.chapter())) return;
        vaultUtil.applyNiche(entry, top);
        var world = plugin.originWorld();
        if (world != null) vaultUtil.applyLabel(world, entry, true, top);
    }

    /**
     * Remove {@code quantity} items like {@code template}, main hand first, then
     * anywhere in the inventory (the write has committed, so they must go even
     * if the player shuffled them between the command and this tick).
     */
    private void consume(Player player, ItemStack template, int quantity) {
        int left = quantity;
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isSimilar(template)) {
            int take = Math.min(left, hand.getAmount());
            hand.setAmount(hand.getAmount() - take);
            player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
            left -= take;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || !it.isSimilar(template)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            player.getInventory().setItem(i, it.getAmount() > 0 ? it : null);
            left -= take;
        }
        if (left > 0) {
            plugin.getLogger().warning(player.getName() + " was " + left + "× " + template.getType()
                    + " short when the donation was consumed; recorded but not removed.");
        }
    }

    static void celebrate(Player player, String rarity) {
        Sound sound = switch (rarity) {
            case "rare" -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
            case "uncommon" -> Sound.ENTITY_PLAYER_LEVELUP;
            default -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        };
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }
}
