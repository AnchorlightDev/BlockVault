package dev.anchorlight.blockvault.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.profile.PlayerProfile;

import java.util.Map;

public class VaultUtil {

    private final BlockVault plugin;

    public VaultUtil(BlockVault plugin) {
        this.plugin = plugin;
    }

    public boolean hasStarted() {
        return plugin.getConfig().getBoolean("vault.started", false);
    }

    /**
     * Turns a Material into a display name: {@code cut_sandstone} -> "Cut Sandstone".
     * Guards against empty tokens from a malformed key.
     */
    public static String formatMaterialName(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.getKey().getKey().split("_")) {
            if (word.isEmpty()) continue;
            out.append(Character.toUpperCase(word.charAt(0)))
               .append(word.substring(1))
               .append(' ');
        }
        return out.toString().trim();
    }

    /**
     * Reconcile the world display against the database. Non-destructive: only
     * the manifest {@code head} cells, the shelf frame's item and the shelf
     * sign's text are ever written, never structure.
     * Emits exactly one summary line (brief section 7, acceptance criterion 1).
     *
     * <p>Shelves on a locked floor are kept empty - blank sign, empty frame - so
     * freecam or x-ray shows nothing until the chapter opens.
     *
     * @param sender optional command sender to echo the summary to
     */
    public void updateVaultState(CommandSender sender) {
        World world = plugin.originWorld();
        if (world == null) {
            String msg = "BlockVault: origin world '"
                    + plugin.getConfig().getString("origin.world") + "' is not loaded; skipping.";
            plugin.getLogger().warning(msg);
            if (sender != null) plugin.tell(sender, "§c" + msg);
            return;
        }

        // Database read off the main thread; block edits applied back on it.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, String> profiles = plugin.database().allProfiles();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                int placed = 0, cleared = 0, ok = 0, shelves = 0;
                for (TargetEntry entry : plugin.manifest().entries().values()) {
                    if (applyShelf(world, entry, plugin.chapters().isOpen(entry.chapter()))) shelves++;

                    Location loc = plugin.resolve(entry.head());
                    Block block = loc.getBlock();
                    // A head is shown only when the block is collected AND its
                    // chapter floor is open. Anything else means: no head.
                    boolean shouldShow = plugin.database().isCollected(entry.material())
                            && plugin.chapters().isOpen(entry.chapter());
                    boolean hasHead = block.getType() == Material.PLAYER_WALL_HEAD
                            || block.getType() == Material.PLAYER_HEAD;

                    if (shouldShow && !hasHead) {
                        HeadUtil.placeHead(loc, entry.face(),
                                parseProfile(world, profiles.get(entry.material())));
                        placed++;
                    } else if (!shouldShow && hasHead) {
                        block.setType(Material.AIR, false);
                        cleared++;
                    } else {
                        ok++;
                    }
                }
                String summary = String.format(
                        "BlockVault reconcile: %d in place, %d heads added, %d removed, %d shelves updated (%d targets).",
                        ok, placed, cleared, shelves, plugin.manifest().entries().size());
                plugin.getLogger().info(summary);
                if (sender != null) plugin.tell(sender, "§a" + summary);
            });
        });
    }

    /**
     * Fill (open floor) or empty (locked floor) one shelf's frame and sign.
     * Missing frames or signs are skipped - /bvrepair rebuilds frames, and signs
     * are schematic structure. Returns true if anything changed.
     */
    public boolean applyShelf(World world, TargetEntry entry, boolean open) {
        Material type = Material.matchMaterial(entry.material());
        if (type != null && !type.isItem()) type = null;
        boolean changed = false;

        ItemFrame frame = findFrame(world, plugin.resolve(entry.frame()));
        if (frame != null) {
            Material want = open && type != null ? type : Material.AIR;
            ItemStack have = frame.getItem();
            Material haveType = have == null ? Material.AIR : have.getType();
            if (haveType != want) {
                frame.setItem(want == Material.AIR ? null : new ItemStack(want), false);
                changed = true;
            }
        }

        Location signLoc = plugin.resolve(entry.sign());
        if (signLoc.getBlock().getState() instanceof Sign sign) {
            String[] want = open ? signLines(entry, type) : new String[]{"", "", "", ""};
            boolean differs = false;
            for (int i = 0; i < 4; i++) {
                if (!want[i].equals(sign.getLine(i))) {
                    sign.setLine(i, want[i]);
                    differs = true;
                }
            }
            if (differs) {
                sign.update(true, false);
                changed = true;
            }
        }
        return changed;
    }

    private static String[] signLines(TargetEntry entry, Material type) {
        String name = type != null ? formatMaterialName(type) : entry.material();
        String[] lines = {"", "", "", ""};
        // Word-wrap the name over the first three lines (~15 chars fit a sign line).
        int line = 0;
        StringBuilder cur = new StringBuilder();
        for (String word : name.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > 15) {
                if (line == 2) break;
                lines[line++] = cur.toString();
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        lines[line] = cur.toString();
        lines[3] = switch (entry.rarity()) {
            case "rare" -> "§6Rare";
            case "uncommon" -> "§bUncommon";
            default -> "§7Common";
        };
        return lines;
    }

    /** The item frame hanging in exactly this block cell, or null. */
    public static ItemFrame findFrame(World world, Location cell) {
        Location centre = cell.clone().add(0.5, 0.5, 0.5);
        for (var ent : world.getNearbyEntities(centre, 0.5, 0.5, 0.5)) {
            if (ent instanceof ItemFrame f
                    && f.getLocation().getBlockX() == cell.getBlockX()
                    && f.getLocation().getBlockY() == cell.getBlockY()
                    && f.getLocation().getBlockZ() == cell.getBlockZ()) {
                return f;
            }
        }
        return null;
    }

    private static PlayerProfile parseProfile(World world, String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return HeadUtil.fromJson(org.bukkit.Bukkit.getServer(), o);
        } catch (Exception e) {
            return null;
        }
    }
}
