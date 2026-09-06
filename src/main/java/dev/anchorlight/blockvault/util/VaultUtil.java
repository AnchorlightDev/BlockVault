package dev.anchorlight.blockvault.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.TargetEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
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
     * the manifest {@code head} cells are ever written, never structure.
     * Emits exactly one summary line (brief section 7, acceptance criterion 1).
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
                int placed = 0, cleared = 0, ok = 0;
                for (TargetEntry entry : plugin.manifest().entries().values()) {
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
                        "BlockVault reconcile: %d in place, %d heads added, %d removed (%d targets).",
                        ok, placed, cleared, plugin.manifest().entries().size());
                plugin.getLogger().info(summary);
                if (sender != null) plugin.tell(sender, "§a" + summary);
            });
        });
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
