package dev.anchorlight.blockvault.util;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.db.Database;
import dev.anchorlight.blockvault.model.HeadSlot;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
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
 *   <li>on the face of the block directly below the head, a small {@link ItemDisplay}
 *       shows the block and a {@link TextDisplay} gives its name - only on open
 *       floors; locked floors show nothing, so freecam reveals nothing.</li>
 * </ul>
 */
public class VaultUtil {

    private final BlockVault plugin;
    private final NamespacedKey nicheKey;
    private final NamespacedKey labelKey;

    public VaultUtil(BlockVault plugin) {
        this.plugin = plugin;
        this.nicheKey = nicheKey(plugin);
        this.labelKey = labelKey(plugin);
    }

    /** PDC tag on every niche item display; value = the block it shows. */
    public static NamespacedKey nicheKey(BlockVault plugin) {
        return new NamespacedKey(plugin, "niche");
    }

    /** PDC tag on every niche name label; value = the block it names. */
    public static NamespacedKey labelKey(BlockVault plugin) {
        return new NamespacedKey(plugin, "niche_label");
    }

    /** Item centre: this far below the head block's base (block below spans 0..-1). */
    private static final double ITEM_Y = -0.38;
    /** Item sits this far out from the wall face (it turns to face the viewer). */
    private static final double ITEM_OUT = 0.25;
    /** Label baseline: near the bottom of the block below the head. */
    private static final double LABEL_Y = -0.95;
    /** Label sits just proud of the wall face so it doesn't z-fight. */
    private static final double LABEL_OUT = 0.02;

    public boolean hasStarted() {
        return plugin.isVaultStarted();
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
                int heads = 0, items = sweepStray(world);
                for (TargetEntry entry : plugin.manifest().entries().values()) {
                    boolean open = plugin.chapters().isOpen(entry.chapter());
                    if (applyNiche(entry, open ? top.get(entry.material()) : null)) heads++;
                    if (applyItem(world, entry, open)) items++;
                    if (applyLabel(world, entry, open)) items++;
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
     * Spawn (open floor) or remove (locked floor) the item shown below one
     * niche head. Returns true if anything changed. Main thread.
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
     * Spawn (open floor) or remove (locked floor) the name label below one niche
     * head. Returns true if anything changed. Main thread.
     */
    public boolean applyLabel(World world, TargetEntry entry, boolean open) {
        Location at = labelLocation(entry.head(), entry.face());
        TextDisplay existing = findLabel(world, at, entry.material());
        boolean show = open && plugin.getConfig().getBoolean("display.label", true);
        if (!show) {
            if (existing == null) return false;
            existing.remove();
            return true;
        }
        String text = labelText(entry.material());
        if (existing != null) {
            if (text.equals(existing.getText())) return false;
            existing.setText(text);
            return true;
        }
        spawnLabel(at, entry.material(), text, labelKey);
        return true;
    }

    /** "cut_sandstone" -> "Cut Sandstone", white. */
    public static String labelText(String material) {
        Material m = Material.matchMaterial(material);
        if (m != null) return "§f" + formatMaterialName(m);
        StringBuilder out = new StringBuilder("§f");
        for (String w : material.split("_")) {
            if (!w.isEmpty()) out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    /**
     * Where a niche's item sits: in front of the block directly below the head,
     * in the upper part of its face. {@code facing} is the way the head faces
     * (out of the wall).
     */
    public Location itemLocation(int[] headRel, boolean wall, BlockFace facing) {
        double out = 0.5 + ITEM_OUT;
        return plugin.resolve(headRel).add(
                0.5 + facing.getModX() * out, ITEM_Y, 0.5 + facing.getModZ() * out);
    }

    /** Where a niche's name label sits: flat on the lower part of the block below the head. */
    public Location labelLocation(int[] headRel, BlockFace facing) {
        double out = 0.5 + LABEL_OUT;
        Location at = plugin.resolve(headRel).add(
                0.5 + facing.getModX() * out, LABEL_Y, 0.5 + facing.getModZ() * out);
        // Face out of the wall, like a sign.
        at.setDirection(new org.bukkit.util.Vector(facing.getModX(), 0, facing.getModZ()));
        return at;
    }

    /** Spawn a tagged name label flat against the wall. Also used by the /bvsetup preview. */
    public TextDisplay spawnLabel(Location at, String tag, String text, NamespacedKey key) {
        float scale = (float) plugin.getConfig().getDouble("display.label-scale", 0.35);
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
            d.setText(text);
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(100); // ~one block wide at the default scale; long names wrap
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setPersistent(true);
        });
    }

    private TextDisplay findLabel(World world, Location at, String material) {
        for (Entity e : world.getNearbyEntities(at, 0.3, 0.3, 0.3)) {
            if (e instanceof TextDisplay d
                    && material.equals(d.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING))) {
                return d;
            }
        }
        return null;
    }

    /**
     * Remove niche items/labels that are not where the layout now puts them -
     * e.g. ones spawned above the heads by an older version, or for a block that
     * left the manifest. Returns how many were removed. Main thread.
     */
    private int sweepStray(World world) {
        int removed = 0;
        for (Entity e : world.getEntitiesByClasses(ItemDisplay.class, TextDisplay.class)) {
            String item = e.getPersistentDataContainer().get(nicheKey, PersistentDataType.STRING);
            String label = e.getPersistentDataContainer().get(labelKey, PersistentDataType.STRING);
            String material = item != null ? item : label;
            if (material == null) continue;
            TargetEntry entry = plugin.manifest().entry(material);
            Location want = entry == null ? null
                    : item != null ? itemLocation(entry.head(), true, entry.face())
                                   : labelLocation(entry.head(), entry.face());
            if (want == null || e.getLocation().distanceSquared(want) > 0.01) {
                e.remove();
                removed++;
            }
        }
        return removed;
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
