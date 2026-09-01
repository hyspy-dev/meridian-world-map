package meridian.worldmap;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Async, multi-level downsampled cache of map tiles for one world — gives the
 * viewer "insane zoom-out" without ever blocking the EDT.
 *
 * <p><b>Levels.</b> Every level holds 32&times;32-pixel tiles:
 * <ul>
 *   <li>Level 0: one tile per chunk column, pixels of the original
 *       {@link MapChunk#image} decoded by {@link MapImageDecoder}. Covers
 *       32&times;32 blocks of world space.</li>
 *   <li>Level k &gt; 0: one tile per 2<sup>k</sup>&times;2<sup>k</sup> chunks
 *       — four level-(k-1) tiles downscaled to 16&times;16 and stitched into
 *       a fresh 32&times;32. Covers 2<sup>k</sup>&middot;32 blocks per side.</li>
 * </ul>
 *
 * <p><b>Async model.</b> Painting calls {@link #tileIfCached} which is a
 * pure lookup — returns the cached image or {@code null}. If null, the paint
 * also calls {@link #requestBuild} which enqueues an async build on a single
 * daemon worker thread and returns immediately. When the build finishes the
 * pyramid invokes the {@code onTileBuilt} callback so the canvas can repaint
 * and the new tile pops into view. This way the EDT never decodes images or
 * walks recursive trees of 10k+ tiles.
 *
 * <p>Invalidation cascades from level 0 ({@link #invalidateChunk}) so a live
 * capture refresh poisons every super-tile that sampled the stale chunk;
 * those super-tiles get rebuilt on next access.
 */
final class TilePyramid {

    /**
     * Pixel size of every cached tile, at every level - learned from the first tile that arrives.
     *
     * <p>Not a constant: the server picks how big it draws a tile, and this build draws 96 pixels
     * a side. Assuming 32 made level 0 keep its native size while every level above it was built
     * at 32, so the picture changed sharpness as you zoomed.
     */
    private volatile int tileSize = 32;

    /** Sentinel "this slot was probed and the source is empty" — avoids re-probing. */
    private static final BufferedImage EMPTY = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    /**
     * Functional gate "did this chunk's data actually arrive?" — passed by
     * the proxy so the viewer can refuse to render live tiles for chunks
     * the proxy never received block data for. Saved (previous-session)
     * tiles bypass this gate; they're historical evidence of a downloaded
     * chunk and a future re-arrival isn't required.
     */
    @FunctionalInterface
    interface ChunkPredicate {
        boolean test(int chunkX, int chunkZ);
    }

    private final WorldMapStore store;
    private final UUID world;
    private final ChunkPredicate columnGate;
    private final Runnable onTileBuilt;
    private final ExecutorService builder;

    /** {@code levels[k]} = level-k tile cache, keyed by packed (superX, superZ). */
    private final List<Map<Long, BufferedImage>> levels = new ArrayList<>();
    /** {@code pending[k]} = keys currently building on the worker — avoids duplicate enqueues. */
    private final List<Set<Long>> pending = new ArrayList<>();

    TilePyramid(WorldMapStore store, UUID world, ChunkPredicate columnGate, Runnable onTileBuilt) {
        this.store = store;
        this.world = world;
        this.columnGate = columnGate;
        this.onTileBuilt = onTileBuilt;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "wd-tile-builder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        this.builder = Executors.newSingleThreadExecutor(tf);
    }

    /** Stops the worker thread; safe to call multiple times. */
    void shutdown() {
        builder.shutdownNow();
    }

    /**
     * Pure cache lookup. Returns the cached tile, or {@code null} if it
     * hasn't been built yet (or the source area is empty, which is cached
     * the same way). Callers should follow up with {@link #requestBuild}
     * when they get {@code null} to trigger an async build.
     */
    synchronized BufferedImage tileIfCached(int level, int superX, int superZ) {
        Map<Long, BufferedImage> levelMap = levelMapOrNull(level);
        if (levelMap == null) {
            return null;
        }
        BufferedImage cached = levelMap.get(pack(superX, superZ));
        return cached == EMPTY ? null : cached;
    }

    /**
     * Enqueues a build for ({@code level}, {@code superX}, {@code superZ}) if
     * it isn't already cached or pending. The build runs on the worker
     * thread; on completion {@link #onTileBuilt} fires and the canvas
     * repaints (where the next paint will then find the tile cached).
     */
    void requestBuild(int level, int superX, int superZ) {
        long key = pack(superX, superZ);
        synchronized (this) {
            if (getLevelMap(level).containsKey(key)) {
                return;             // already built or empty
            }
            if (!getPending(level).add(key)) {
                return;             // build already in flight
            }
        }
        builder.execute(() -> runBuild(level, superX, superZ, key));
    }

    private void runBuild(int level, int superX, int superZ, long key) {
        BufferedImage built;
        try {
            built = buildRecursively(level, superX, superZ);
        } catch (RuntimeException t) {
            built = null;
        }
        synchronized (this) {
            getLevelMap(level).put(key, built != null ? built : EMPTY);
            getPending(level).remove(key);
        }
        if (built != null && onTileBuilt != null) {
            onTileBuilt.run();
        }
    }

    /**
     * Synchronously builds (or fetches cached) the tile at this position —
     * always invoked from the worker thread, so a recursive cascade to level 0
     * is fine: it just keeps the EDT untouched.
     */
    private BufferedImage buildRecursively(int level, int superX, int superZ) {
        long key = pack(superX, superZ);
        synchronized (this) {
            Map<Long, BufferedImage> levelMap = getLevelMap(level);
            BufferedImage cached = levelMap.get(key);
            if (cached == EMPTY) {
                return null;
            }
            if (cached != null) {
                return cached;
            }
        }
        BufferedImage built = (level == 0) ? loadLevel0(superX, superZ)
                                           : composeSuperTile(level, superX, superZ);
        synchronized (this) {
            // Memoise both the hit and the miss — without the EMPTY entry the
            // next request would re-walk the same recursive tree.
            getLevelMap(level).put(key, built != null ? built : EMPTY);
        }
        return built;
    }

    private BufferedImage loadLevel0(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        // A live tile is only honoured if the chunk actually arrived this
        // session — otherwise the viewer would advertise downloaded coverage
        // for a chunk whose block data we don't have. Saved tiles (from a
        // previous session that already passed the same filter at write
        // time) are always allowed.
        Tile tile = null;
        Tile liveT = store.liveTile(world, key);
        if (liveT != null && columnGate.test(chunkX, chunkZ)) {
            tile = liveT;
        }
        if (tile == null) {
            tile = store.savedTile(world, key);
        }
        if (tile == null) {
            return null;
        }
        BufferedImage image = tile.toImage();
        adoptSize(image);
        return image;
    }

    /** How big a tile is, in pixels. */
    int tileSize() {
        return tileSize;
    }

    /**
     * Takes the size from the tiles themselves, the first time one is seen.
     *
     * <p>Anything cached before that was built to the wrong measure, so it goes.
     */
    private void adoptSize(BufferedImage tile) {
        int side = tile.getWidth();
        if (side <= 0 || side == tileSize) {
            return;
        }
        synchronized (this) {
            tileSize = side;
            levels.forEach(Map::clear);
        }
    }

    private BufferedImage composeSuperTile(int level, int superX, int superZ) {
        int half = tileSize / 2;
        BufferedImage[] quads = new BufferedImage[4];
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            int dx = q & 1;
            int dz = q >> 1;
            BufferedImage child = buildRecursively(level - 1, superX * 2 + dx, superZ * 2 + dz);
            quads[q] = child;
            if (child != null) {
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        BufferedImage out = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (int q = 0; q < 4; q++) {
            BufferedImage child = quads[q];
            if (child == null) {
                continue;
            }
            int dx = (q & 1) * half;
            int dz = (q >> 1) * half;
            g.drawImage(child, dx, dz, half, half, null);
        }
        g.dispose();
        return out;
    }

    /**
     * Invalidates the chunk-column tile at ({@code chunkX}, {@code chunkZ})
     * and every super-tile up the pyramid that contains it. The viewer hooks
     * this to {@link WorldMapStore#addUpdateListener} so live capture refreshes
     * everywhere a player can zoom to.
     */
    synchronized void invalidateChunk(int chunkX, int chunkZ) {
        for (int level = 0; level < levels.size(); level++) {
            int sx = chunkX >> level;
            int sz = chunkZ >> level;
            levels.get(level).remove(pack(sx, sz));
        }
    }

    private Map<Long, BufferedImage> getLevelMap(int level) {
        while (levels.size() <= level) {
            levels.add(new HashMap<>());
        }
        return levels.get(level);
    }

    private Map<Long, BufferedImage> levelMapOrNull(int level) {
        return level < levels.size() ? levels.get(level) : null;
    }

    private Set<Long> getPending(int level) {
        while (pending.size() <= level) {
            pending.add(new HashSet<>());
        }
        return pending.get(level);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
