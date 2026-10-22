package dev.anchorlight.blockvault.model;

import org.bukkit.block.BlockFace;

/**
 * One block in the target list and the wall-head niche that shows it.
 * {@code head} is relative to the configured origin; the runtime adds the
 * world origin before touching blocks. The floating item display and the
 * top-donor head both live at that niche.
 *
 * @param edition the edition this block belongs to (e.g. 26.2). Only entries
 *                matching the running edition are part of the season target.
 * @param facing  the wall head's facing, a {@link BlockFace} name in lowercase
 */
public record TargetEntry(
        String material,
        int chapter,
        String rarity,
        String section,
        String edition,
        int[] head,
        String facing
) {
    public BlockFace face() {
        return HeadSlot.parseFace(facing);
    }
}
