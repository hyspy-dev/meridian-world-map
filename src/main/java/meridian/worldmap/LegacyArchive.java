package meridian.worldmap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Maps written the old way, turned into a table.
 *
 * <p>Before the table there was a stream: one gzipped run of every tile, read from the beginning
 * to reach anything, rewritten whole to change anything, and grown by appending - so ground walked
 * over twice was stored twice. Older maps are in that shape and are worth keeping, so the first
 * time one is opened it is converted, once, and the original is set aside rather than deleted.
 *
 * <p><b>This class is meant to be deleted.</b> When no map in the wild is still a stream, it and
 * {@link LegacyTiles} can go, and nothing else in the module will notice: everything here is
 * called from one line in {@link WorldMapStore#open()}.
 */
final class LegacyArchive {

    /** A tile is a picture, not a payload: anything wilder than this is a broken file. */
    private static final int MAX_TILE_SIZE = 512;

    private LegacyArchive() {
    }

    /**
     * Converts the map at this path if it is an old one, leaving a table in its place.
     *
     * <p>The order matters and is the whole safety of it: the new table is built beside the old
     * file, the old file is only moved aside once the table is written, and the table only takes
     * its name after that. A machine that dies at any point leaves either the old map or the new
     * one, never a mixture.
     */
    static void convertIfNeeded(Path file) throws IOException {
        if (!Files.exists(file) || !isStream(file)) {
            return;
        }
        Path snapshot = file.resolveSibling(file.getFileName() + ".tmp");
        Map<UUID, Map<Long, Tile>> tiles = new HashMap<>();
        int fromArchive = read(file, tiles);
        int fromSnapshot = Files.exists(snapshot) ? read(snapshot, tiles) : 0;
        System.out.println("[world-map] converting the old map: " + fromArchive + " tile(s)"
                + (fromSnapshot > 0 ? " and " + fromSnapshot + " from an unfinished session" : "")
                + " -> a table");

        Path building = file.resolveSibling(file.getFileName() + ".converting");
        Files.deleteIfExists(building);
        int written = 0;
        try (TableFile table = TableFile.openOrCreate(building)) {
            for (Map.Entry<UUID, Map<Long, Tile>> world : tiles.entrySet()) {
                for (Tile tile : world.getValue().values()) {
                    table.write(world.getKey(), tile);
                    written++;
                }
            }
            table.commit();
        }

        // The old map is kept, not thrown away: it is the only copy of everything until the new
        // one has proved itself, and it costs nothing but disk.
        Files.move(file, file.resolveSibling(file.getFileName() + ".v2"),
                StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(snapshot)) {
            Files.move(snapshot, snapshot.resolveSibling(snapshot.getFileName() + ".v2"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(building, file, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[world-map] converted: " + written + " tile(s) in a table, the old map "
                + "kept beside it as " + file.getFileName() + ".v2");
    }

    /** Whether this is one of the old stream maps - they are gzip, the table is not. */
    private static boolean isStream(Path file) throws IOException {
        byte[] head = new byte[2];
        try (var in = Files.newInputStream(file)) {
            if (in.read(head) != 2) {
                return false;
            }
        }
        return (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
    }

    /**
     * Reads one old file into the pile, block after block.
     *
     * <p>Tolerant on purpose: these files were written by rewriting them whole, and a session that
     * was killed part-way through left a tail that cannot be read. Everything before the tail is
     * still good and is what this is for.
     */
    private static int read(Path file, Map<UUID, Map<Long, Tile>> into) {
        int read = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file))))) {
            blocks:
            while (true) {
                byte[] magic = in.readNBytes(4);
                if (magic.length < 4
                        || !"MMAP".equals(new String(magic, StandardCharsets.US_ASCII))) {
                    break;
                }
                int version = in.readInt();
                int worldCount = in.readInt();
                // The version number cannot be trusted on its own: some of these files say 1 and
                // hold tiles in the later shape - the writer's number never moved when the tiles
                // did. So the first record is looked at, and it says which shape this file is in.
                Boolean decodedTiles = null;
                for (int w = 0; w < worldCount; w++) {
                    UUID world;
                    int tileCount;
                    try {
                        world = new UUID(in.readLong(), in.readLong());
                        tileCount = in.readInt();
                    } catch (IOException | RuntimeException e) {
                        break blocks;
                    }
                    Map<Long, Tile> tiles = into.computeIfAbsent(world, k -> new HashMap<>());
                    if (decodedTiles == null) {
                        decodedTiles = version >= 2 || looksDecoded(in);
                    }
                    for (int t = 0; t < tileCount; t++) {
                        Tile tile;
                        try {
                            tile = decodedTiles ? readTile(in) : readLegacyTile(in);
                        } catch (IOException | RuntimeException e) {
                            break blocks;
                        }
                        if (tile == null) {
                            break blocks;
                        }
                        tiles.put(WorldMapStore.pack(tile.chunkX(), tile.chunkZ()), tile);
                        read++;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[world-map] the old map ends badly (" + e + ") - keeping the "
                    + read + " tile(s) that could be read");
        }
        return read;
    }

    /**
     * Whether the tiles that follow are pictures rather than wire blobs.
     *
     * <p>Reads the first record's heading and puts it back. A picture starts with its column and
     * a side in pixels, which is a small number in a known range; a wire blob starts with a
     * length, which practically never lands there.
     */
    private static boolean looksDecoded(DataInputStream in) throws IOException {
        in.mark(16);
        try {
            in.readInt();                               // chunk x
            in.readInt();                               // chunk z
            int size = in.readShort();
            return size > 0 && size <= MAX_TILE_SIZE;
        } catch (IOException e) {
            return false;
        } finally {
            in.reset();
        }
    }

    private static Tile readTile(DataInputStream in) throws IOException {
        int chunkX = in.readInt();
        int chunkZ = in.readInt();
        int size = in.readShort();
        if (size <= 0 || size > MAX_TILE_SIZE) {
            return null;
        }
        byte[] rgb = in.readNBytes(size * size * 3);
        return rgb.length == size * size * 3 ? Tile.fromRgb(chunkX, chunkZ, size, rgb) : null;
    }

    /** The oldest maps kept tiles as the wire sent them, palette and all. */
    private static Tile readLegacyTile(DataInputStream in) throws IOException {
        int blobLength = in.readInt();
        if (blobLength <= 0 || blobLength > 1 << 20) {
            return null;
        }
        byte[] blob = new byte[blobLength];
        in.readFully(blob);
        return LegacyTiles.read(blob);
    }
}
