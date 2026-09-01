package meridian.worldmap;

import java.awt.image.BufferedImage;

/**
 * One map tile, as pixels.
 *
 * <p>Kept decoded rather than in the form it arrived in. The wire form belongs to one version of
 * the game and changes with it; an archive of collected worlds is meant to outlive that, and the
 * only thing anything here actually does with a tile is draw it.
 *
 * <p>Its own class rather than core's {@code MapTile}: the window has to open with a double-click,
 * where core does not exist, and a tile is the one thing every part of it touches. Core's tiles
 * are copied into these at the door - see {@link CoreTiles}.
 */
final class Tile {

    private final int chunkX;
    private final int chunkZ;
    private final int size;
    private final int[] pixels;      // 0xRRGGBB, row-major

    Tile(int chunkX, int chunkZ, int size, int[] pixels) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.size = size;
        this.pixels = pixels;
    }

    /** A tile read back from an archive written before tiles were stored decoded. */
    static Tile fromImage(int chunkX, int chunkZ, BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getWidth() != image.getHeight()) {
            return null;
        }
        int size = image.getWidth();
        int[] pixels = new int[size * size];
        image.getRGB(0, 0, size, size, pixels, 0, size);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] &= 0xFFFFFF;
        }
        return new Tile(chunkX, chunkZ, size, pixels);
    }

    static Tile fromRgb(int chunkX, int chunkZ, int size, byte[] rgb) {
        int[] pixels = new int[size * size];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = ((rgb[i * 3] & 0xFF) << 16)
                    | ((rgb[i * 3 + 1] & 0xFF) << 8)
                    | (rgb[i * 3 + 2] & 0xFF);
        }
        return new Tile(chunkX, chunkZ, size, pixels);
    }

    byte[] toRgb() {
        byte[] rgb = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) {
            rgb[i * 3] = (byte) (pixels[i] >> 16);
            rgb[i * 3 + 1] = (byte) (pixels[i] >> 8);
            rgb[i * 3 + 2] = (byte) pixels[i];
        }
        return rgb;
    }

    /** The tile as something to draw. */
    BufferedImage toImage() {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        return image;
    }

    int chunkX() {
        return chunkX;
    }

    int chunkZ() {
        return chunkZ;
    }

    int size() {
        return size;
    }

    int colourAt(int pixelX, int pixelZ) {
        if (pixelX < 0 || pixelZ < 0 || pixelX >= size || pixelZ >= size) {
            return -1;
        }
        return pixels[pixelZ * size + pixelX];
    }

    /**
     * The colour at a block, wherever the tile's own resolution puts it.
     *
     * <p>A tile covers {@link #BLOCKS} blocks a side however many pixels it happens to be drawn
     * with, so the two are not interchangeable - the pixel is worked out from the block.
     */
    int colourAtBlock(int blockX, int blockZ) {
        int offsetX = Math.floorMod(blockX, BLOCKS);
        int offsetZ = Math.floorMod(blockZ, BLOCKS);
        return colourAt(offsetX * size / BLOCKS, offsetZ * size / BLOCKS);
    }

    /**
     * How much ground one tile covers, in blocks.
     *
     * <p>The same number as core's {@code WorldMap.TILE_BLOCKS}, kept here rather than borrowed:
     * this class has to work where core is not on the class path at all. It is a property of the
     * game's map, not of core, and it has not moved.
     */
    static final int BLOCKS = 32;
}
