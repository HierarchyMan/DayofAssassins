package com.fusion.dev.cystol.arena;

/**
 * Axis-aligned cuboid with normalized min/max.
 */
public final class CuboidBounds {

    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public CuboidBounds(double x1, double y1, double z1, double x2, double y2, double z2) {
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public double sizeY() {
        return maxY - minY;
    }

    /**
     * Whether the vertical span is effectively a single plane (typical when both wand corners
     * are clicked on the same floor Y). FFA standable search needs room for feet + air above.
     */
    public boolean isVerticallyFlat(double epsilon) {
        return sizeY() <= Math.max(0.0, epsilon);
    }

    /**
     * Expand Y around the current mid-plane so FFA Y-search and kill-zone checks have volume.
     * Returns {@code this} when already tall enough. Does not change X/Z.
     *
     * @param minSpan minimum {@code maxY - minY} after expansion (e.g. 24)
     */
    public CuboidBounds withMinimumHeight(double minSpan) {
        double need = Math.max(1.0, minSpan);
        if (sizeY() + 1e-9 >= need) {
            return this;
        }
        double mid = (minY + maxY) / 2.0;
        double half = need / 2.0;
        return new CuboidBounds(minX, mid - half, minZ, maxX, mid + half, maxZ);
    }

    public double minX() {
        return minX;
    }

    public double minY() {
        return minY;
    }

    public double minZ() {
        return minZ;
    }

    public double maxX() {
        return maxX;
    }

    public double maxY() {
        return maxY;
    }

    public double maxZ() {
        return maxZ;
    }

    public double sizeX() {
        return maxX - minX;
    }

    public double sizeZ() {
        return maxZ - minZ;
    }

    public double longestHorizontalSide() {
        return Math.max(sizeX(), sizeZ());
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean containsHorizontal(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}
