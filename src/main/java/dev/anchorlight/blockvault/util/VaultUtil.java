package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.HeadSlot;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * The niche display layer. Each niche is a built wall head:
 * <ul>
 *   <li>the head wears the skin of whoever has donated the most of that block
 *       (a plain head until someone donates, and while the floor is locked);</li>
 *   <li>a small floating {@link ItemDisplay} above it shows the block, only on
 *       open floors - locked floors show nothing, so freecam reveals nothing.</li>
 * </ul>
 */
public class VaultUtil {

    private final BlockVault plugin;
    private final NamespacedKey nicheKey;

    public VaultUtil(BlockVault plugin) {
        this.plugin = plugin;
        this.nicheKey = nicheKey(plugin);
    }

    /** PDC tag on every niche item display; value = the block it shows. */
    public static NamespacedKey nicheKey(BlockVault plugin) {
        return new NamespacedKey(plugin, "niche");
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
     * niche head skins and the niche item displays are ever written, never
     * structure. Emits exactly one summary line.
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

        // Database read off the main thread; world edits applied back on it.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Database.TopDonor> top = plugin.database().topDonors();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (top == null) {
                    String msg = "BlockVault reconcile skipped: database unavailable.";
                    plugin.getLogger().warning(msg);
                    if (sender != null) plugin.tell(sender, "§c" + msg);
                    return;
                }
                int heads = 0, items = 0;
                for (TargetEntry entry : plugin.manifest().entries().values()) {
                    boolean open = plugin.chapters().isOpen(entry.chapter());
                    if (applyNiche(entry, open ? top.get(entry.material()) : null)) heads++;
                    if (applyItem(world, entry, open)) items++;
                }
                // Reserved (other-edition) and spare niches: plain heads, no item.
                for (TargetEntry entry : plugin.manifest().reserved()) {
                    if (HeadUtil.applyHead(plugin.resolve(entry.head()), true, entry.face(), null)) heads++;
                }
                for (HeadSlot spare : plugin.manifest().spares()) {
                    if (HeadUtil.applyHead(plugin.resolve(spare.head()), spare.wall(), spare.face(), null)) heads++;
                }
                String summary = String.format(
                        "BlockVault reconcile: %d heads updated, %d item displays updated (%d targets).",
                        heads, items, plugin.manifest().entries().size());
                plugin.getLogger().info(summary);
                if (sender != null) plugin.tell(sender, "§a" + summary);
            });
        });
    }

    /** Put {@code donor}'s skin (or a plain head) on one niche. Main thread. */
    public boolean applyNiche(TargetEntry entry, Database.TopDonor donor) {
        Location loc = plugin.resolve(entry.head());
        if (loc.getWorld() == null) return false;
        org.bukkit.profile.PlayerProfile profile = donor == null ? null
                : HeadUtil.fromJson(plugin.getServer(), donor.profileJson());
        if (donor != null && profile == null) {
            profile = plugin.getServer().createPlayerProfile(donor.uuid(), donor.name());
        }
        return HeadUtil.applyHead(loc, true, entry.face(), profile);
    }

    /**
     * Spawn (open floor) or remove (locked floor) the floating item above one
     * niche. Returns true if anything changed. Main thread.
     */
    public boolean applyItem(World world, TargetEntry entry, boolean open) {
        Material type = Material.matchMaterial(entry.material());
        if (type != null && !type.isItem()) type = null;
        Location at = itemLocation(entry.head(), true, entry.face());
        ItemDisplay existing = findItem(world, at, entry.material());

        if (!open || type == null) {
            if (existing == null) return false;
            existing.remove();
            return true;
        }
        if (existing != null) {
            if (existing.getItemStack() != null && existing.getItemStack().getType() == type) return false;
            existing.setItemStack(new ItemStack(type));
            return true;
        }
        spawnItem(at, entry.material(), new ItemStack(type), nicheKey);
        return true;
    }

    /**
     * Where the floating item sits for a head: centred on the head and lifted by
     * {@code display.item-offset-y}. Wall heads hug the wall, so the item is
     * pulled back towards it by the same quarter block.
     */
    public Location itemLocation(int[] headRel, boolean wall, BlockFace facing) {
        Location base = plugin.resolve(headRel).add(0.5, 0, 0.5);
        double lift = plugin.getConfig().getDouble("display.item-offset-y", 1.0);
        base.add(0, lift, 0);
        if (wall) {
            base.add(-facing.getModX() * 0.25, 0, -facing.getModZ() * 0.25);
        }
        return base;
    }

    /** Spawn a tagged floating item. Also used by the /bvsetup preview. */
    public ItemDisplay spawnItem(Location at, String tag, ItemStack item, NamespacedKey key) {
        float scale = (float) plugin.getConfig().getDouble("display.item-scale", 0.4);
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setPersistent(true);
        });
    }

    private ItemDisplay findItem(World world, Location at, String material) {
        for (Entity e : world.getNearbyEntities(at, 0.4, 0.4, 0.4)) {
            if (e instanceof ItemDisplay d
                    && material.equals(d.getPersistentDataContainer().get(nicheKey, PersistentDataType.STRING))) {
                return d;
            }
        }
        return null;
    }
}
