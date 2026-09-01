package meridian.worldmap;

/**
 * Reads a tile out of an archive written before tiles were stored decoded.
 *
 * <p>Those archives hold each tile in the form it arrived in — a serialized {@code MapChunk}. This
 * parses those bytes directly rather than through the protocol classes, and on purpose: the layout
 * in an old file is frozen, it will never be sent again, and depending on the current protocol to
 * read a file written by a past one is backwards. It is also what lets this module carry no
 * protocol dependency at all.
 *
 * <p>The layout, little-endian throughout:
 * <pre>
 *   MapChunk : byte nullBits (0x01 = image present), int32 chunkX, int32 chunkZ, then the image
 *   MapImage : byte nullBits (0x01 = palette, 0x02 = indices), int32 width, int32 height,
 *              byte bitsPerIndex, int32 paletteAt, int32 indicesAt   (18 bytes),
 *              then at 18+paletteAt / 18+indicesAt: varint length, then the data
 *   palette  : int32 each, packed 0xRRGGBBAA
 *   indices  : bitsPerIndex bits per pixel, low bits first
 * </pre>
 */
final class LegacyTiles {

    private LegacyTiles() {
    }

    /** The tile in a legacy blob, or null if it holds nothing usable. */
    static Tile read(byte[] blob) {
        if (blob.length < 9) {
            return null;
        }
        if ((blob[0] & 0x01) == 0) {
            return null;                       // an unload, which was never worth archiving
        }
        int chunkX = i32(blob, 1);
        int chunkZ = i32(blob, 5);
        return image(blob, 9, chunkX, chunkZ);
    }

    private static Tile image(byte[] b, int at, int chunkX, int chunkZ) {
        if (at + 18 > b.length) {
            return null;
        }
        int nullBits = b[at] & 0xFF;
        int width = i32(b, at + 1);
        int height = i32(b, at + 5);
        int bitsPerIndex = b[at + 9] & 0xFF;
        int paletteAt = i32(b, at + 10);
        int indicesAt = i32(b, at + 14);
        if ((nullBits & 0x03) != 0x03 || width <= 0 || width != height || bitsPerIndex <= 0) {
            return null;
        }

        int[] palette = palette(b, at + 18 + paletteAt);
        byte[] packed = bytes(b, at + 18 + indicesAt);
        if (palette == null || packed == null) {
            return null;
        }

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int index = bits(packed, i * bitsPerIndex, bitsPerIndex);
            // The wire packs colour as 0xRRGGBBAA; dropping the alpha is a shift, not a mask.
            pixels[i] = index >= 0 && index < palette.length ? palette[index] >>> 8 : 0;
        }
        return new Tile(chunkX, chunkZ, width, pixels);
    }

    private static int[] palette(byte[] b, int at) {
        long header = varInt(b, at);
        if (header < 0) {
            return null;
        }
        int length = (int) header;
        int p = at + (int) (header >>> 32);
        if (length < 0 || p + length * 4 > b.length) {
            return null;
        }
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            out[i] = i32(b, p + i * 4);
        }
        return out;
    }

    private static byte[] bytes(byte[] b, int at) {
        long header = varInt(b, at);
        if (header < 0) {
            return null;
        }
        int length = (int) header;
        int p = at + (int) (header >>> 32);
        if (length < 0 || p + length > b.length) {
            return null;
        }
        byte[] out = new byte[length];
        System.arraycopy(b, p, out, 0, length);
        return out;
    }

    /** Length in the high half, value in the low half; negative when the bytes run out. */
    private static long varInt(byte[] b, int at) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (at + i >= b.length) {
                return -1L;
            }
            int part = b[at + i] & 0xFF;
            value |= (part & 0x7F) << (7 * i);
            if ((part & 0x80) == 0) {
                return ((long) (i + 1) << 32) | (value & 0xFFFFFFFFL);
            }
        }
        return -1L;
    }

    /** {@code numBits} bits at a bit offset, low bits first, as the packer wrote them. */
    private static int bits(byte[] packed, int bitOffset, int numBits) {
        int value = 0;
        for (int i = 0; i < numBits; i++) {
            int bit = bitOffset + i;
            int index = bit >> 3;
            if (index >= packed.length) {
                return -1;
            }
            value |= ((packed[index] >>> (bit & 7)) & 1) << i;
        }
        return value;
    }

    private static int i32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
