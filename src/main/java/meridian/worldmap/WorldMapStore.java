package meridian.worldmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * The map: every tile ever collected, kept in a table on disk.
 *
 * <p>Two halves. What the session has just seen lives in memory until it is committed; everything
 * else lives in {@link TableFile}, where each tile has a place of its own addressed by world and
 * column. Which means a tile written again <em>replaces</em> the old one instead of being added
 * after it, and opening a map reads a directory rather than a map.
 *
 * <p>That is the difference between this and what came before. The old file was one long stream:
 * to read a tile you read all of them, ground walked over twice was stored twice, and writing
 * meant rewriting everything - so a session that read the file badly could write back less than
 * it found. None of those are possible here.
 */
final class WorldMapStore {

    private final Path file;
    /** What this session has seen and not yet committed. */
    private final Map<UUID, Map<Long, Tile>> live = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, Long>> updateListeners = new CopyOnWriteArrayList<>();

    /** How far each world reaches, kept up to date rather than counted on every frame. */
    private final Map<UUID, int[]> bounds = new ConcurrentHashMap<>();
    /**
     * What each tile looked like when it was last taken, so an unchanged one is ignored.
     *
     * <p>Only for tiles this session is still holding. Once they are committed the table knows
     * their checksums and these go: a tile that is not in memory has no hash in memory either.
     */
    private final Map<UUID, Map<Long, Integer>> checksums = new ConcurrentHashMap<>();

    /** What this session has been offered: taken because it was new, or known already. */
    private final java.util.concurrent.atomic.AtomicInteger taken =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger unchanged =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile TableFile table;
    private volatile String loadStatus = "not opened yet";

    WorldMapStore(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    String loadStatus() {
        return loadStatus;
    }

    /**
     * Opens the map, turning an older one into a table first if that is what is there.
     *
     * <p>Cheap whatever the size: only the directory is read. A hundred and forty megabytes of
     * map opens as fast as an empty one, and tiles are read as they are drawn.
     */
    void open() {
        try {
            LegacyArchive.convertIfNeeded(file);
            table = TableFile.openOrCreate(file);
            loadStatus = "ok (" + table.tileCount() + " tile(s)";
            long spare = table.freeBytes();
            loadStatus += spare > 0 ? ", " + spare / 1024 + " KB free inside)" : ")";
            log("open: " + loadStatus + " " + file);
        } catch (IOException | RuntimeException e) {
            table = null;
            loadStatus = "failed: " + e.getMessage();
            log("open: " + loadStatus + " - " + file);
        }
    }

    void addUpdateListener(BiConsumer<UUID, Long> listener) {
        updateListeners.add(listener);
    }

    void removeUpdateListener(BiConsumer<UUID, Long> listener) {
        updateListeners.remove(listener);
    }

    /**
     * A tile has arrived.
     *
     * <p>Ignored when it is the picture we already have. A server re-sends a tile every time the
     * player walks past it, and taking each one as news meant redrawing the window and rebuilding
     * its pyramid for a picture that had not changed - which is most of them.
     */
    boolean put(UUID world, Tile tile) {
        if (tile == null) {
            return false;
        }
        long key = pack(tile.chunkX(), tile.chunkZ());
        int checksum = TableFile.checksum(tile.toRgb());
        Map<Long, Integer> known = checksums.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
        Integer had = known.get(key);
        if (had == null) {
            TableFile open = table;
            had = open == null ? 0 : open.checksumOf(world, key);
        }
        if (had != 0 && had == checksum) {
            unchanged.incrementAndGet();
            return false;                   // the same picture: nothing has happened
        }
        known.put(key, checksum);
        live.computeIfAbsent(world, k -> new ConcurrentHashMap<>()).put(key, tile);
        grow(world, tile.chunkX(), tile.chunkZ());
        taken.incrementAndGet();
        fireUpdate(world, key);
        return true;
    }

    /** How many tiles this session took in, and how many it recognised and let past. */
    int takenThisSession() {
        return taken.get();
    }

    int unchangedThisSession() {
        return unchanged.get();
    }

    void fireUpdate(UUID world, long key) {
        for (BiConsumer<UUID, Long> listener : updateListeners) {
            try {
                listener.accept(world, key);
            } catch (RuntimeException e) {
                // A listener that throws is its own problem, not the map's.
            }
        }
    }

    /**
     * Writes what the session has collected into the table and makes it part of the file.
     *
     * <p>Every tile goes to its own place, so this costs what was collected and not what the map
     * weighs. Committed tiles are let go of: the table holds them now, and holding a second copy
     * in memory is how a long session used to grow without bound.
     */
    void commit() throws IOException {
        TableFile open = table;
        if (open == null) {
            throw new IOException("the map is not open (" + loadStatus + ")");
        }
        for (Map.Entry<UUID, Map<Long, Tile>> world : live.entrySet()) {
            for (Tile tile : world.getValue().values()) {
                open.write(world.getKey(), tile);
            }
        }
        open.commit();
        // The tiles are the table's now, and so is the memory of what they looked like: the
        // checksum lives in the directory beside each one. Holding a second copy of either is how
        // a long session used to grow without bound.
        live.clear();
        checksums.clear();
    }

    /** Every world the map holds, on disk or in hand. */
    Set<UUID> worlds() {
        Set<UUID> all = new HashSet<>(live.keySet());
        TableFile open = table;
        if (open != null) {
            all.addAll(open.worlds());
        }
        return all;
    }

    /** Every column of a world that has a tile. */
    Set<Long> allCoords(UUID world) {
        Set<Long> all = new HashSet<>();
        TableFile open = table;
        if (open != null) {
            all.addAll(open.coords(world));
        }
        Map<Long, Tile> now = live.get(world);
        if (now != null) {
            all.addAll(now.keySet());
        }
        return all;
    }

    /**
     * How far the map reaches: {@code [minChunkX, minChunkZ, maxChunkX, maxChunkZ]}, or null.
     *
     * <p>Remembered rather than worked out. The window asks on every frame, and walking sixty
     * thousand keys sixty times a second is most of what a frame used to cost.
     */
    int[] tileBounds(UUID world) {
        int[] known = bounds.get(world);
        if (known == null) {
            TableFile open = table;
            known = open == null ? null : open.bounds(world);
            if (known != null) {
                bounds.put(world, known);
            }
        }
        return known == null ? null : known.clone();
    }

    /**
     * Widens what a world reaches to take in one more column.
     *
     * <p>Starting from what is already on disk, not from this one column. A tile arriving before
     * anybody had asked how far the world went used to <em>become</em> how far the world went -
     * sixty-five thousand tiles in the file and a map one chunk wide, which the window then drew
     * as one tile in a black screen.
     */
    private void grow(UUID world, int chunkX, int chunkZ) {
        bounds.compute(world, (id, was) -> {
            int[] from = was;
            if (from == null) {
                TableFile open = table;
                from = open == null ? null : open.bounds(world);
            }
            return from == null
                    ? new int[] { chunkX, chunkZ, chunkX, chunkZ }
                    : new int[] { Math.min(from[0], chunkX), Math.min(from[1], chunkZ),
                                  Math.max(from[2], chunkX), Math.max(from[3], chunkZ) };
        });
    }

    /** The newest tile for a column: what this session saw, else what is on disk. */
    Tile tile(UUID world, long key) {
        Tile now = liveTile(world, key);
        return now != null ? now : savedTile(world, key);
    }

    Tile liveTile(UUID world, long key) {
        Map<Long, Tile> now = live.get(world);
        return now == null ? null : now.get(key);
    }

    /** A tile from the table, read now. Nothing is held in memory on its account. */
    Tile savedTile(UUID world, long key) {
        TableFile open = table;
        return open == null ? null : open.read(world, key);
    }

    boolean hasLive(UUID world, long key) {
        Map<Long, Tile> now = live.get(world);
        return now != null && now.containsKey(key);
    }

    boolean hasSaved(UUID world, long key) {
        TableFile open = table;
        return open != null && open.has(world, key);
    }

    /**
     * Files everything kept under one world under another.
     *
     * <p>The map's tiles start arriving before the server says which world they belong to, so
     * they are filed under a placeholder and moved across the moment it does.
     */
    void migrate(UUID from, UUID to) {
        if (from.equals(to)) {
            return;
        }
        bounds.remove(from);
        bounds.remove(to);
        Map<Long, Integer> movingSums = checksums.remove(from);
        if (movingSums != null) {
            checksums.computeIfAbsent(to, k -> new ConcurrentHashMap<>()).putAll(movingSums);
        }
        Map<Long, Tile> moving = live.remove(from);
        if (moving != null && !moving.isEmpty()) {
            live.computeIfAbsent(to, k -> new ConcurrentHashMap<>()).putAll(moving);
        }
        TableFile open = table;
        if (open != null) {
            open.rename(from, to);
        }
    }

    /** How many tiles the map holds, on disk and in hand. */
    int tileCount() {
        TableFile open = table;
        int total = open == null ? 0 : open.tileCount();
        for (Map<Long, Tile> tiles : live.values()) {
            total += tiles.size();
        }
        return total;
    }

    /** Space inside the file that nothing is using - reused before the file is ever grown. */
    long freeBytes() {
        TableFile open = table;
        return open == null ? 0 : open.freeBytes();
    }

    void close() {
        TableFile open = table;
        if (open != null) {
            try {
                open.close();
            } catch (IOException e) {
                // Closing a file we are done with is not worth a word.
            }
        }
    }

    /** Whether the map is open and can be written to. */
    boolean isOpen() {
        return table != null;
    }

    static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    /** What the map says about itself while it opens, for the window's title bar. */
    private static void log(String message) {
        System.out.println("[world-map] " + message);
    }

    /** Whether anything is there at all - for the window opened on a file somebody dropped. */
    boolean isEmpty() {
        return worlds().isEmpty();
    }

    /** True when the file exists and is a map of some kind. */
    static boolean looksLikeAMap(Path path) {
        try {
            return Files.size(path) > 8;
        } catch (IOException e) {
            return false;
        }
    }
}
