package meridian.worldmap;

import meridian.core.api.MapTile;

/**
 * Core's tiles, copied into ours.
 *
 * <p>The one place a tile crosses over. Everything the window does is with {@link Tile}, which
 * knows nothing about core - that is what lets the jar open on its own - so a tile core is holding
 * is copied here at the door, and the archive never depends on what core keeps or for how long.
 */
final class CoreTiles {

    private CoreTiles() {
    }

    /** A copy of a tile core is holding, or null when there is nothing in it. */
    static Tile copyOf(MapTile source) {
        int size = source.size();
        if (size <= 0) {
            return null;
        }
        int[] pixels = new int[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                pixels[z * size + x] = source.colourAt(x, z);
            }
        }
        return new Tile(source.chunkX(), source.chunkZ(), size, pixels);
    }
}
