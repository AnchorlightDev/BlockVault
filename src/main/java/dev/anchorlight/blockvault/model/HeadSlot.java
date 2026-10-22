package dev.anchorlight.blockvault.model;

import org.bukkit.block.BlockFace;

/**
 * A head position found by {@code /bvsetup}: a spare niche, a floor leader
 * pedestal or the lobby leader. Relative to the configured origin.
 *
 * @param wall   true for a wall-mounted head, false for one standing on a block
 * @param facing wall facing, or the standing head's rotation, as a lowercase {@link BlockFace} name
 */
public record HeadSlot(int chapter, int[] head, boolean wall, String facing) {

    public BlockFace face() {
        return parseFace(facing);
    }

    public static BlockFace parseFace(String facing) {
        try {
            return BlockFace.valueOf(facing.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return BlockFace.NORTH;
        }
    }
}
