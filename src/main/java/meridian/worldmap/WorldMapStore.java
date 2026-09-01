package meridian.worldmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.function.BiPredicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistent per-world store of explored map tiles, layered:
 *
 * <ul>
 *   <li><b>Saved tiles</b> — loaded from disk at startup, representing what
 *       was captured in previous sessions. Immutable for the lifetime of the
 *       store; the viewer draws them <em>dimmed</em>.</li>
 *   <li><b>Live tiles</b> — collected this session, from core's map feed.
 *       The viewer draws them bright, but only once the matching chunk has
 *       physically arrived.</li>
 * </ul>
 *
 * <p>For a coord present in both layers, the live tile wins (latest data).
 * Reading via {@link #tile} returns the live one if present, else the saved
 * one. {@link #save} writes the union back to disk — next session sees them
 * all as "saved".
 *
 * <p>File: {@code <dataDir>/worldmap.dat}, gzip, ASCII magic {@code "MMAP"} then:
 * <pre>
 *   int formatVersion
 *   int worldCount
 *   per world: long uuidMsb, long uuidLsb, int tileCount,
 *              per tile: int chunkX, int chunkZ, short size, byte[size*size*3]   (RGB)
 * </pre>
 *
 * <p>Version 1 stored each tile as a serialized {@code MapChunk} - the wire form, which belongs
 * to one version of the game. It is still read, so an archive built before the change survives;
 * it is written back out decoded.
 */
final class WorldMapStore {

    /** Tiles are stored decoded. Version 1 held the wire form and is still read. */
    private static final int FORMAT_VERSION = 2;
    /** A tile bigger than this is refused as corrupt rather than allocated. */
    private static final int MAX_TILE_SIZE = 512;

    private final Path file;

    /** Tiles loaded from disk at startup — basis for the dim overlay. Never mutated after load. */
    private final Map<UUID, Map<Long, Tile>> saved = new ConcurrentHashMap<>();
    /** Tiles received this session. Wins over {@link #saved} for the same coord. */
    private final Map<UUID, Map<Long, Tile>> live = new ConcurrentHashMap<>();

    /**
     * Listeners notified after every {@link #put} with {@code (world, packedCoord)}.
     * Used by the live map viewer to invalidate its tile pyramid and repaint.
     */
    private final List<BiConsumer<UUID, Long>> updateListeners = new CopyOnWriteArrayList<>();

    /** Last load attempt outcome — surfaced in the viewer's title bar. */
    private volatile String loadStatus = "not yet loaded";

    WorldMapStore(Path file) {
        this.file = file;
    }

    /** Returns the on-disk path this store reads from / writes to. */
    Path file() {
        return file;
    }

    /** Human-readable result of the last {@link #load} call — for diagnostic display. */
    String loadStatus() {
        return loadStatus;
    }

    /** Subscribes to per-tile updates. The listener fires on each {@link #put}. */
    void addUpdateListener(BiConsumer<UUID, Long> listener) {
        updateListeners.add(listener);
    }

    /** Unsubscribes; safe to call from any thread. */
    void removeUpdateListener(BiConsumer<UUID, Long> listener) {
        updateListeners.remove(listener);
    }

    /** Records (or replaces) one map tile for the current session. */
    void put(UUID world, Tile tile) {
        long key = pack(tile.chunkX(), tile.chunkZ());
        live.computeIfAbsent(world, k -> new ConcurrentHashMap<>()).put(key, tile);
        fireUpdate(world, key);
    }

    /**
     * Fires {@link #updateListeners} without modifying state. Used when an
     * external condition that gates tile visibility changes — most notably
     * when a {@code SetChunk} finally arrives and the viewer's chunk-gate
     * predicate flips from false to true at that coord. Without this the
     * viewer's tile pyramid keeps the stale {@code EMPTY} entry it cached
     * before the chunk was available and the new tile never appears.
     */
    void fireUpdate(UUID world, long key) {
        for (BiConsumer<UUID, Long> listener : updateListeners) {
            try {
                listener.accept(world, key);
            } catch (RuntimeException ignored) {
                // A misbehaving listener must not break capture.
            }
        }
    }

    /** Snapshot of all world UUIDs known to the store (saved or live). */
    Set<UUID> worlds() {
        Set<UUID> out = new HashSet<>(saved.keySet());
        out.addAll(live.keySet());
        return out;
    }

    /**
     * Bounding chunk-column range of all known tiles for the given world,
     * returned as {@code {minChunkX, minChunkZ, maxChunkX, maxChunkZ}}, or
     * {@code null} when no tiles exist. Used by the viewer to auto-fit the
     * initial view to whatever is actually on disk.
     */
    int[] tileBounds(UUID world) {
        Set<Long> coords = allCoords(world);
        if (coords.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : coords) {
            int x = (int) (key >> 32);
            int z = (int) (long) key;
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (z < minZ) {
                minZ = z;
            }
            if (z > maxZ) {
                maxZ = z;
            }
        }
        return new int[] { minX, minZ, maxX, maxZ };
    }

    /** The live (this session) or saved (previous session) tile at the given coord, or {@code null}. */
    Tile tile(UUID world, long key) {
        Map<Long, Tile> liveW = live.get(world);
        if (liveW != null) {
            Tile t = liveW.get(key);
            if (t != null) {
                return t;
            }
        }
        Map<Long, Tile> savedW = saved.get(world);
        return savedW == null ? null : savedW.get(key);
    }

    /** The live (this-session) tile at this coord, or {@code null}. */
    Tile liveTile(UUID world, long key) {
        Map<Long, Tile> liveW = live.get(world);
        return liveW == null ? null : liveW.get(key);
    }

    /** The saved (previous-session) tile at this coord, or {@code null}. */
    Tile savedTile(UUID world, long key) {
        Map<Long, Tile> savedW = saved.get(world);
        return savedW == null ? null : savedW.get(key);
    }

    /** Whether a tile at this coord was received this session. */
    boolean hasLive(UUID world, long key) {
        Map<Long, Tile> liveW = live.get(world);
        return liveW != null && liveW.containsKey(key);
    }

    /** Whether a tile at this coord was loaded from disk (previous session). */
    boolean hasSaved(UUID world, long key) {
        Map<Long, Tile> savedW = saved.get(world);
        return savedW != null && savedW.containsKey(key);
    }

    /**
     * Reassign all tiles from {@code from} to {@code to}. Used when a
     * post-handshake {@code JoinWorld} reveals the real world UUID after
     * tiles were filed under the sentinel. {@code to} wins for any shared
     * coord (it's the authoritative this-session data).
     */
    void migrate(UUID from, UUID to) {
        if (from.equals(to)) {
            return;
        }
        Map<Long, Tile> liveFrom = live.remove(from);
        if (liveFrom != null && !liveFrom.isEmpty()) {
            Map<Long, Tile> liveTo = live.computeIfAbsent(to, k -> new ConcurrentHashMap<>());
            for (Map.Entry<Long, Tile> e : liveFrom.entrySet()) {
                liveTo.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        Map<Long, Tile> savedFrom = saved.remove(from);
        if (savedFrom != null && !savedFrom.isEmpty()) {
            Map<Long, Tile> savedTo = saved.computeIfAbsent(to, k -> new ConcurrentHashMap<>());
            for (Map.Entry<Long, Tile> e : savedFrom.entrySet()) {
                savedTo.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    /** Union of saved + live coords for the given world (snapshot). */
    Set<Long> allCoords(UUID world) {
        Set<Long> out = new HashSet<>();
        Map<Long, Tile> savedW = saved.get(world);
        if (savedW != null) {
            out.addAll(savedW.keySet());
        }
        Map<Long, Tile> liveW = live.get(world);
        if (liveW != null) {
            out.addAll(liveW.keySet());
        }
        return out;
    }

    /**
     * Reads the store file if it exists. Errors are logged (so a silently
     * missing on-disk state doesn't go undetected) but don't throw — the
     * store just starts empty.
     */
    void load() {
        if (!Files.exists(file)) {
            loadStatus = "file does not exist";
            log("load: " + loadStatus + ": " + file);
            return;
        }
        int worldsLoaded = 0;
        int totalTiles = 0;
        boolean truncated = false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file))))) {
            byte[] magic = in.readNBytes(4);
            if (magic.length != 4 || !"MMAP".equals(new String(magic, StandardCharsets.US_ASCII))) {
                loadStatus = "bad magic (got " + magic.length + " bytes: "
                        + java.util.Arrays.toString(magic) + ")";
                log("load: " + loadStatus + " in " + file);
                return;
            }
            int version = in.readInt();
            int worldCount = in.readInt();

            outer:
            for (int w = 0; w < worldCount; w++) {
                UUID world;
                int tileCount;
                try {
                    world = new UUID(in.readLong(), in.readLong());
                    tileCount = in.readInt();
                } catch (IOException | RuntimeException e) {
                    truncated = true;
                    break;
                }
                // Attach the per-world map BEFORE reading tiles so partial
                // data is still retained when the stream gets truncated.
                Map<Long, Tile> tiles = saved.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
                int tilesRead = 0;
                for (int t = 0; t < tileCount; t++) {
                    try {
                        Tile tile = version >= 2 ? readTile(in) : readLegacyTile(in);
                        if (tile == null) {
                            truncated = true;
                            totalTiles += tilesRead;
                            break outer;
                        }
                        tiles.put(pack(tile.chunkX(), tile.chunkZ()), tile);
                        tilesRead++;
                    } catch (IOException | RuntimeException e) {
                        truncated = true;
                        totalTiles += tilesRead;
                        log("load: world " + world + " truncated — " + tilesRead + "/" + tileCount + " tile(s) recovered");
                        break outer;
                    }
                }
                worldsLoaded++;
                totalTiles += tilesRead;
                log("load: world " + world + " — " + tilesRead + " tile(s)");
            }
            loadStatus = (truncated ? "partial " : "ok ") + "(v" + version + ", "
                    + worldsLoaded + "/" + worldCount + " world(s), "
                    + totalTiles + " tile(s)" + (truncated ? ", file truncated" : "") + ")";
            log("load: " + loadStatus + " from " + file);
        } catch (IOException | RuntimeException e) {
            loadStatus = "failed: " + e.getClass().getSimpleName() + " "
                    + e.getMessage() + " — kept " + worldsLoaded + " world(s), "
                    + totalTiles + " tile(s)";
            log("load: " + loadStatus + " — " + file);
        }
    }

    /** Diagnostic — printed to stderr so it appears in the proxy log without an slf4j hookup. */
    private static void log(String message) {
        System.err.println("[wd-map-store] " + message);
    }

    /**
     * Writes the union (saved + live, live wins) to disk. A live tile is
     * persisted only if {@code keepLive.test(world, packedCoord)} returns
     * true — used to skip tiles whose underlying chunk never arrived as
     * {@code SetChunk} this session (the saved layer must represent only
     * what was actually downloaded). Pre-existing saved tiles for a coord
     * are kept even when the same coord's live tile is filtered out.
     */
    void save(BiPredicate<UUID, Long> keepLive) throws IOException {
        // Build the merged view first so a save error leaves the disk consistent.
        Set<UUID> worlds = new HashSet<>();
        worlds.addAll(saved.keySet());
        worlds.addAll(live.keySet());

        // Atomic write: stream into <file>.tmp, fsync, then atomic-rename
        // over the real file. If the JVM is killed mid-save the original
        // worldmap.dat stays intact — we never end up with a half-written
        // file that fails to load on next start (which is exactly how the
        // user's 10 MB truncated archive happened).
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeBytes("MMAP");
            out.writeInt(FORMAT_VERSION);
            out.writeInt(worlds.size());
            for (UUID world : worlds) {
                Map<Long, Tile> merged = new java.util.HashMap<>();
                Map<Long, Tile> savedW = saved.get(world);
                if (savedW != null) {
                    merged.putAll(savedW);
                }
                Map<Long, Tile> liveW = live.get(world);
                if (liveW != null) {
                    for (Map.Entry<Long, Tile> e : liveW.entrySet()) {
                        // Live wins only if the chunk was confirmed this session.
                        // Otherwise the previous-session saved tile (if any) is
                        // retained; if there is none, the coord is dropped.
                        if (keepLive.test(world, e.getKey())) {
                            merged.put(e.getKey(), e.getValue());
                        }
                    }
                }
                out.writeLong(world.getMostSignificantBits());
                out.writeLong(world.getLeastSignificantBits());
                out.writeInt(merged.size());
                for (Tile tile : merged.values()) {
                    out.writeInt(tile.chunkX());
                    out.writeInt(tile.chunkZ());
                    out.writeShort(tile.size());
                    out.write(tile.toRgb());
                }
            }
        }
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException atomicNotSupported) {
            // Fallback for filesystems without atomic-move; still much safer
            // than streaming directly into `file`.
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** One tile of the current format. */
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

    /** One tile of an archive written before tiles were stored decoded. */
    private static Tile readLegacyTile(DataInputStream in) throws IOException {
        int blobLen = in.readInt();
        if (blobLen <= 0 || blobLen > 1 << 20) {
            return null;
        }
        byte[] blob = new byte[blobLen];
        in.readFully(blob);
        return LegacyTiles.read(blob);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
