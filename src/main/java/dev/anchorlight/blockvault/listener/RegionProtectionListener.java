package dev.anchorlight.blockvault.listener;

import dev.anchorlight.blockvault.BlockVault;
import dev.anchorlight.blockvault.model.Region;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Everything that keeps the display layer and the seals intact. The display is
 * entity-based (item frames + heads), so without this a single creeper erases
 * a wall of donations.
 */
public final class RegionProtectionListener implements Listener {

    private static final String BYPASS = "blockvault.build";

    private final BlockVault plugin;

    public RegionProtectionListener(BlockVault plugin) {
        this.plugin = plugin;
    }

    private boolean inside(org.bukkit.Location loc) {
        Region r = plugin.region();
        return r != null && r.contains(loc);
    }

    // --- item frames -------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (inside(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent e) {
        if (inside(e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        EntityType t = e.getRightClicked().getType();
        if ((t == EntityType.ITEM_FRAME || t == EntityType.GLOW_ITEM_FRAME)
                && inside(e.getRightClicked().getLocation())
                && !e.getPlayer().hasPermission(BYPASS)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        EntityType t = e.getEntity().getType();
        if ((t == EntityType.ITEM_FRAME || t == EntityType.GLOW_ITEM_FRAME)
                && inside(e.getEntity().getLocation())) {
            e.setCancelled(true);
        }
    }

    // --- blocks ----------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (inside(e.getBlock().getLocation()) && !e.getPlayer().hasPermission(BYPASS)) {
            e.setCancelled(true);
            plugin.tell(e.getPlayer(), "§cYou can't break blocks inside the vault.");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (inside(e.getBlock().getLocation()) && !e.getPlayer().hasPermission(BYPASS)) {
            e.setCancelled(true);
            plugin.tell(e.getPlayer(), "§cYou can't place blocks inside the vault.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inside(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inside(b.getLocation()));
    }

    // --- environmental griefing -------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        // Endermen picking up blocks, silverfish infesting, sheep eating grass,
        // falling blocks landing, etc.
        if (inside(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (inside(e.getBlock().getLocation())
                || e.getBlocks().stream().anyMatch(b -> inside(b.getLocation()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (inside(e.getBlock().getLocation())
                || e.getBlocks().stream().anyMatch(b -> inside(b.getLocation()))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (inside(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (inside(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        // Fire spreading, mushrooms/vines growing across the build.
        if (inside(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        // Water/lava flowing into the region.
        if (inside(e.getToBlock().getLocation())) e.setCancelled(true);
    }

    // --- mob spawning --------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!inside(e.getLocation())) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, BREEDING, DISPENSE_EGG -> { /* allowed */ }
            default -> e.setCancelled(true);
        }
    }
}
