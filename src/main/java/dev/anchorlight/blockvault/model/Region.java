package dev.anchorlight.blockvault.model;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The protected volume around the vault, in absolute world coordinates.
 * Derived from the manifest bounding box plus a margin, offset by the
 * configured origin. Used for build/hanging/spawn protection and chunk
 * force-loading.
 */
public final class Region {

    private final World world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    public Region(World world, int[] origin, int[] relMin, int[] relMax, int margin) {
        this.world = world;
        this.minX = origin[0] + relMin[0] - margin;
        this.minY = origin[1] + relMin[1] - margin;
        this.minZ = origin[2] + relMin[2] - margin;
        this.maxX = origin[0] + relMax[0] + margin;
        this.maxY = origin[1] + relMax[1] + margin;
        this.maxZ = origin[2] + relMax[2] + margin;
    }

    public World world() {
        return world;
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public int minChunkX() { return minX >> 4; }
    public int maxChunkX() { return maxX >> 4; }
    public int minChunkZ() { return minZ >> 4; }
    public int maxChunkZ() { return maxZ >> 4; }
}
