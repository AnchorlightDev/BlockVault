package dev.anchorlight.blockvault.commands;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.TargetEntry;
import dev.anchorlight.blockvault.util.HeadUtil;
import dev.anchorlight.blockvault.util.VaultUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Submit the held block. Order is always: write -> confirm commit -> consume.
 * If the database is unreachable the item stays in the player's hand.
 */
public class SubmitCommand implements CommandExecutor {
    private final BlockVault plugin;
    private final VaultUtil vaultUtil;

    public SubmitCommand(BlockVault plugin) {
        this.plugin = plugin;
        this.vaultUtil = new VaultUtil(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.tell(sender, "§cOnly players can use this command!");
            return true;
        }
        if (!player.hasPermission("blockvault.submit")) {
            plugin.tell(player, "§cYou don't have permission to submit blocks.");
            return true;
        }
        if (!vaultUtil.hasStarted()) {
            plugin.tell(player, "§cThe vault has not been opened yet.");
            return true;
        }
        if (player.getGameMode() == GameMode.CREATIVE) {
            plugin.tell(player, "§cSubmissions are not allowed in creative mode.");
            return true;
        }
        var region = plugin.region();
        if (region == null || !region.contains(player.getLocation())) {
            plugin.tell(player, "§cYou must be inside the vault to submit a block.");
            return true;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || held.getAmount() < 1) {
            plugin.tell(player, "§cYou are not holding anything.");
            return true;
        }

        Material type = held.getType();
        String material = type.getKey().getKey(); // lowercase registry id
        String pretty = VaultUtil.formatMaterialName(type);

        TargetEntry entry = plugin.manifest().entry(material);
        if (entry == null) {
            plugin.tell(player, "§c" + pretty + " is not part of this collection.");
            return true;
        }

        Database db = plugin.database();
        if (db.isCollected(material)) {
            plugin.tell(player, "§e" + pretty + " has already been donated.");
            return true;
        }

        int points = plugin.getConfig().getInt("points." + entry.rarity(), 1);
        org.bukkit.profile.PlayerProfile profile = player.getPlayerProfile();
        String profileJson = HeadUtil.toJson(profile);

        plugin.tell(player, "§7Submitting " + pretty + "…");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Database.SubmitOutcome outcome =
                    db.submit(material, player.getUniqueId(), player.getName(), points, profileJson);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                switch (outcome) {
                    case ALREADY_TAKEN -> plugin.tell(player,
                            "§e" + pretty + " was donated by someone else first. Your block is untouched.");
                    case DB_ERROR -> plugin.tell(player,
                            "§cCould not record that right now - your block is still in your hand. Please retry.");
                    case OK -> {
                        // Write committed. Only now is it safe to consume the item.
                        consumeOne(player, type);
                        placeHead(entry, profile);
                        plugin.tell(player, "§aDonated " + pretty + "! §7(+" + points
                                + (points == 1 ? " point)" : " points)"));
                        celebrate(player, entry.rarity());
                        if ("rare".equals(entry.rarity())) {
                            plugin.webhook().rareSubmission(material, player.getName(), points);
                        }
                        plugin.displays().refresh();
                        plugin.chapters().check();
                    }
                }
            });
        });
        return true;
    }

    private void consumeOne(Player player, Material expected) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != expected || held.getAmount() < 1) {
            // Player swapped items between the command and the commit. The block
            // is already recorded; not consuming it is a harmless edge case.
            plugin.getLogger().warning(player.getName() + " moved " + expected
                    + " before it could be consumed; it was recorded but not removed.");
            return;
        }
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }

    private void placeHead(TargetEntry entry, org.bukkit.profile.PlayerProfile profile) {
        // Submissions for a not-yet-open chapter are credited, but the head
        // stays hidden until the floor opens (the reconcile pass adds it then).
        if (!plugin.chapters().isOpen(entry.chapter())) return;
        Location loc = plugin.resolve(entry.head());
        if (loc.getWorld() == null) return;
        HeadUtil.placeHead(loc, entry.face(), profile);
    }

    private void celebrate(Player player, String rarity) {
        Sound sound = switch (rarity) {
            case "rare" -> Sound.UI_TOAST_CHALLENGE_COMPLETE;
            case "uncommon" -> Sound.ENTITY_PLAYER_LEVELUP;
            default -> Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        };
        player.playSound(player.getLocation(), sound, 1f, 1f);
    }

}
