package dev.anchorlight.blockvault.model;

import org.bukkit.block.BlockFace;

/**
 * One row of the frozen target list. Coordinates are relative to the schematic
 * origin; the runtime adds the configured world origin before touching blocks.
 */
public record TargetEntry(
        String material,
        int chapter,
        String rarity,
        String section,
        int[] sign,
        int[] frame,
        int[] head,
        String facing
) {
    public BlockFace face() {
        return switch (facing) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> throw new IllegalStateException("bad facing: " + facing);
        };
    }
}
