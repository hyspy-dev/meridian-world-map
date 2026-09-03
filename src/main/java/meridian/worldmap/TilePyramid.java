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
     * How long a square found empty is taken at its word.
     *
     * <p>Being short of ground is not a fact about a square, it is a fact about a moment: ground
     * arrives all the time. A square drawn from three of its four quarters is not empty - it is a
     * finished picture with a black corner - so nothing ever asked for it again, and the corner
     * stayed black for as long as the window was open. Empty is only the extreme of the same
     * thing. Both are held for a couple of seconds and then asked about again, which costs a
     * lookup that finds nothing and means no hole can outlive that.
     */
    private static final long HALF_A_PICTURE_HOLDS_FOR_NANOS = 2_000_000_000L;

    /** When each square was last drawn short of ground, so that answer can go stale. */
    private final List<Map<Long, Long>> incompleteSince = new ArrayList<>();

    /**
     * Whether a chunk's tile is drawn as the server painted it.
     *
     * <p>Every tile is kept and every tile is shown - what changes is how. Ground somebody has
     * actually collected is drawn as it is; ground only glimpsed from a distance is drawn drained
     * of half its colour, so one look at the map says which is which. Answering true for
     * everything - which is what the map does when nothing can tell it apart - draws the plain
     * map, and costs nothing.
     */
    @FunctionalInterface
    interface ChunkPredicate {
        /**
         * @param world the world the tile belongs to - an archive holds several, and what has
         *              been collected is asked per world
         */
        boolean test(UUID world, int chunkX, int chunkZ);
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
    /**
     * Keys the ground changed under while they were being drawn.
     *
     * <p>A tile arrives, and the picture covering it is already halfway through being made out of
     * what was there a moment ago. The rebuild cannot start - one is running - and the running
     * one finishes with the old answer, which for a square whose ground had not arrived yet is
     * nothing at all. That is the hole: it is remembered as empty and nothing ever asks again.
     * Noting it here means the build that lands puts itself back in the queue.
     */
    private final List<Set<Long>> staleAgain = new ArrayList<>();

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
        this.overview = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wd-map-overview");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Paints the wide view, once, by reading every tile of the world in turn.
     *
     * <p>Bottom up, not top down. Each tile is read once, painted into the one wide picture that
     * covers it and let go of again - so the work is what the map holds, not what the screen asks
     * for, and nothing is kept but the wide pictures themselves. There are 4^{@value #COARSE}
     * times fewer of those than there are tiles.
     *
     * <p>It shows as it goes: the window draws the wide pictures while they are still filling, so
     * the map appears at once and sharpens over the next few seconds instead of arriving in
     * pieces over the next minute.
     */
    void sweepOverview() {
        if (!sweeping.compareAndSet(false, true)) {
            return;                         // painted already, or being painted now
        }
        overview.execute(this::runSweep);
    }

    private void runSweep() {
        List<Long> keys = new ArrayList<>(store.allCoords(world));
        sweepTotal = keys.size();
        int done = 0;
        for (long key : keys) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Tile tile = store.tile(world, key);
            if (tile != null) {
                paintWide(tile);
            }
            sweptTiles = ++done;
            if ((done & 0xFF) == 0 && onTileBuilt != null) {
                onTileBuilt.run();          // let the window show what has been painted so far
            }
        }
        if (onTileBuilt != null) {
            onTileBuilt.run();
        }
    }

    /** Lays one tile into the wide picture that covers it, at the size it is worth there. */
    private void paintWide(Tile tile) {
        BufferedImage source = tile.toImage();
        adoptSize(source);
        int side = tileSize;
        int cell = Math.max(1, side >> COARSE);
        if (!columnGate.test(world, tile.chunkX(), tile.chunkZ())) {
            source = drained(source);
        }
        int superX = tile.chunkX() >> COARSE;
        int superZ = tile.chunkZ() >> COARSE;
        long key = pack(superX, superZ);
        BufferedImage into;
        synchronized (this) {
            into = coarse.get(key);
            if (into == null || into.getWidth() != side) {
                into = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                coarse.put(key, into);
            }
        }
        int atX = Math.floorMod(tile.chunkX(), 1 << COARSE) * cell;
        int atZ = Math.floorMod(tile.chunkZ(), 1 << COARSE) * cell;
        synchronized (into) {
            Graphics2D g = into.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, atX, atZ, cell, cell, null);
            g.dispose();
        }
    }

    /** How far along the wide view is, for the line the window prints. */
    String sweep() {
        if (!sweeping.get()) {
            return "wide=not started";
        }
        synchronized (this) {
            return sweptTiles >= sweepTotal && sweepTotal > 0
                    ? "wide=" + coarse.size() + " done"
                    : "wide=" + sweptTiles + "/" + sweepTotal;
        }
    }

    /**
     * How far beyond the window built tiles are kept: one screen on every side, so panning has
     * something ready and a map of twenty thousand tiles does not sit in memory whole.
     */
    private static final double KEPT_SCREENS = 1.0;

    /** Sweeping is cheap but not free; once a second is plenty when the view is moving. */
    private static final long SWEEP_NANOS = 1_000_000_000L;

    /** Below this many tiles there is nothing worth sweeping for. */
    private static final int SWEEP_FLOOR = 256;

    private long sweptAt;

    /**
     * The level from which the map is drawn as one picture rather than assembled.
     *
     * <p>A tile at level k stands for 4^k columns. Building level five by asking for four
     * children, each of which asks for four of its own, walks thirteen hundred pictures and keeps
     * every one of them - so a map seen whole took tens of seconds to appear and held tens of
     * thousands of images while it did. Above this level that is cheap, because it bottoms out
     * here rather than at the ground.
     */
    private static final int COARSE = 3;

    /**
     * The widest picture the map ever builds: {@value #COARSE}, and there are four steps up to it.
     *
     * <p>Past this, zooming out shrinks these pictures instead of stitching wider ones. A step is
     * four times the ground for the same pixels, so four of them span sixty-four to one - past
     * that the whole world is a thumbnail and building a picture for each further step buys
     * nothing but the memory it costs.
     */
    int topLevel() {
        return COARSE;
    }

    /** The wide view, painted once from the ground up and kept: level {@value #COARSE}. */
    private final Map<Long, BufferedImage> coarse = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean sweeping =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile int sweptTiles;
    private volatile int sweepTotal;
    /** Its own thread: the wide view is a long job and must not sit in front of a near one. */
    private final ExecutorService overview;

    // What the pyramid has been doing, for the line the window prints once a second.
    private final java.util.concurrent.atomic.AtomicInteger built =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger empty =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger dropped =
            new java.util.concurrent.atomic.AtomicInteger();

    /** What has happened since the last time anybody asked, and it resets on being asked. */
    String work() {
        return "built=" + built.getAndSet(0) + " empty=" + empty.getAndSet(0)
                + " dropped=" + dropped.getAndSet(0)
                + " waiting=" + pending() + " held=" + cached() + " tile=" + tileSize + "px "
                + sweep();
    }

    /** How many tiles are waiting to be built - what the window is still short of. */
    synchronized int pending() {
        int total = 0;
        for (Set<Long> level : pending) {
            total += level.size();
        }
        return total;
    }

    /** How many built tiles are being held, across every level. */
    synchronized int cached() {
        int total = 0;
        for (Map<Long, BufferedImage> level : levels) {
            total += level.size();
        }
        return total + coarse.size();
    }

    /**
     * Lets go of everything far from what is being looked at.
     *
     * <p>A built tile is an image, and a large map has tens of thousands of them: kept for the
     * whole session they would fill memory with ground nobody is looking at any more. What is on
     * screen, and one screen out in each direction, stays; the rest is dropped and simply built
     * again if it comes back. Called after painting, and it does its work at most once a second.
     *
     * @param minX the world rectangle on screen, in blocks
     */
    void retain(double minX, double minZ, double maxX, double maxZ) {
        long now = System.nanoTime();
        synchronized (this) {
            if (now - sweptAt < SWEEP_NANOS || cached() <= SWEEP_FLOOR) {
                return;
            }
            sweptAt = now;
            double marginX = (maxX - minX) * KEPT_SCREENS;
            double marginZ = (maxZ - minZ) * KEPT_SCREENS;
            double keepMinX = minX - marginX;
            double keepMaxX = maxX + marginX;
            double keepMinZ = minZ - marginZ;
            double keepMaxZ = maxZ + marginZ;
            // The wide view is not swept away: it is the whole map at a glance, it cost a pass
            // over the file to paint, and it is small - one picture for every sixty-four tiles.
            for (int level = 0; level < levels.size(); level++) {
                int span = Tile.BLOCKS << level;      // blocks a super-tile covers here
                long lowX = (long) Math.floor(keepMinX / span);
                long highX = (long) Math.floor(keepMaxX / span);
                long lowZ = (long) Math.floor(keepMinZ / span);
                long highZ = (long) Math.floor(keepMaxZ / span);
                int was = levels.get(level).size();
                levels.get(level).keySet().removeIf(key -> {
                    int x = (int) (key >> 32);
                    int z = (int) (long) key;
                    return x < lowX || x > highX || z < lowZ || z > highZ;
                });
                dropped.addAndGet(was - levels.get(level).size());
                // And what was remembered about the squares out there goes with them.
                getIncompleteSince(level).keySet().removeIf(key -> {
                    int x = (int) (key >> 32);
                    int z = (int) (long) key;
                    return x < lowX || x > highX || z < lowZ || z > highZ;
                });
            }
        }
    }

    /**
     * Throws away every built tile, so they are drawn again.
     *
     * <p>For the moment the map is asked to show its colours differently: what is cached was
     * built under the old answer and would otherwise stay on screen until it was panned away.
     */
    void invalidateAll() {
        synchronized (this) {
            for (Map<Long, BufferedImage> level : levels) {
                level.clear();
            }
            incompleteSince.forEach(Map::clear);
            coarse.clear();
        }
        sweeping.set(false);                // the wide view was painted under the old answer
        sweptTiles = 0;
        sweepOverview();
    }

    /** Stops the worker thread; safe to call multiple times. */
    void shutdown() {
        builder.shutdownNow();
        overview.shutdownNow();
    }

    /**
     * Pure cache lookup. Returns the cached tile, or {@code null} if it
     * hasn't been built yet (or the source area is empty, which is cached
     * the same way). Callers should follow up with {@link #requestBuild}
     * when they get {@code null} to trigger an async build.
     */
    BufferedImage tileIfCached(int level, int superX, int superZ) {
        if (level >= COARSE) {
            sweepOverview();                // the first wide look is what starts the painting
            synchronized (this) {
                return coarse.get(pack(superX, superZ));
            }
        }
        return cachedTile(level, superX, superZ);
    }

    private synchronized BufferedImage cachedTile(int level, int superX, int superZ) {
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
    /**
     * Asks for a tile to be built, unless it is built already or on its way.
     *
     * @return whether this call is what put it in the queue - the caller counts those, and only
     *         those, against how much work it may start on one frame. Counting a tile that was
     *         already queued would spend the whole allowance on the same few tiles every frame,
     *         and the map would never fill out past them.
     */
    boolean requestBuild(int level, int superX, int superZ) {
        if (level >= COARSE) {
            sweepOverview();                // nothing to queue: the sweep paints these
            return false;
        }
        long key = pack(superX, superZ);
        synchronized (this) {
            if (getLevelMap(level).containsKey(key) && !wornOut(level, key)) {
                return false;       // drawn, and drawn from everything there was
            }
            if (!getPending(level).add(key)) {
                return false;       // build already in flight
            }
        }
        builder.execute(() -> runBuild(level, superX, superZ, key, false));
        return true;
    }

    /**
     * Builds a tile again although one is already there, and swaps it in when it is ready.
     *
     * <p>For ground that has changed. Taking the old picture away first is what made the map
     * flicker black while a player flew: a tile arrives, its picture and every larger one
     * covering it are dropped, and the window has nothing to draw there until the rebuild lands.
     * The stale picture is a great deal better than a hole, and it is only stale for a moment.
     */
    private void rebuild(int level, int superX, int superZ) {
        long key = pack(superX, superZ);
        synchronized (this) {
            if (!getPending(level).add(key)) {
                getStale(level).add(key);   // being drawn right now, from what is already old
                return;
            }
        }
        builder.execute(() -> runBuild(level, superX, superZ, key, true));
    }

    /**
     * @param afresh whether to draw the picture again rather than hand back the one in memory.
     *               A rebuild that goes through {@link #buildRecursively} finds the old picture
     *               cached and puts it straight back, which is no rebuild at all - it is why
     *               ground that arrived was never drawn and ground that became collected stayed
     *               faded, both for as long as the window stayed open.
     */
    /**
     * Whether what is held for a square is worth asking about again.
     *
     * <p>A whole picture never is - it is everything there was. One drawn short of ground is,
     * once it has been on screen for a moment.
     */
    private boolean wornOut(int level, long key) {
        Long when = getIncompleteSince(level).get(key);
        return when != null && System.nanoTime() - when > HALF_A_PICTURE_HOLDS_FOR_NANOS;
    }

    private Map<Long, Long> getIncompleteSince(int level) {
        while (incompleteSince.size() <= level) {
            incompleteSince.add(new HashMap<>());
        }
        return incompleteSince.get(level);
    }

    /** Remembers whether a square was drawn from all of its ground or only some of it. */
    private synchronized void noteWhole(int level, long key, boolean whole) {
        if (whole) {
            getIncompleteSince(level).remove(key);
        } else {
            getIncompleteSince(level).putIfAbsent(key, System.nanoTime());
        }
    }

    private void runBuild(int level, int superX, int superZ, long key, boolean afresh) {
        BufferedImage built;
        try {
            built = afresh ? draw(level, superX, superZ)
                           : buildRecursively(level, superX, superZ);
        } catch (RuntimeException t) {
            built = null;
        }
        boolean again;
        synchronized (this) {
            getLevelMap(level).put(key, built != null ? built : EMPTY);
            getPending(level).remove(key);
            again = getStale(level).remove(key);
        }
        if (again) {
            // The ground moved under it while it was being drawn: draw it once more, now.
            builder.execute(() -> runBuild(level, superX, superZ, key, true));
        }
        if (built != null) {
            this.built.incrementAndGet();
        } else {
            this.empty.incrementAndGet();
        }
        if (onTileBuilt != null) {
            // Whatever came of it, even nothing. The window fills itself out by asking for a
            // little at a time and asking again when work finishes; staying silent about a tile
            // that turned out to be empty stops that chain dead, and the map simply stops
            // growing - which is exactly what it did.
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
            if (cached != null && !wornOut(level, key)) {
                return cached == EMPTY ? null : cached;
            }
            // An old "there is nothing here" is worth nothing: the ground may have arrived since,
            // and this is the one place that can notice. Every hole on screen was a square that
            // took its own first look as the last word - the children of a square as much as the
            // square itself, which is why the holes survived being asked for again.
        }
        BufferedImage built = draw(level, superX, superZ);
        synchronized (this) {
            // Memoise both the hit and the miss — without the EMPTY entry the
            // next request would re-walk the same recursive tree.
            getLevelMap(level).put(key, built != null ? built : EMPTY);
        }
        return built;
    }

    /**
     * The picture itself, drawn now: from the ground at level 0, from four smaller ones above it,
     * and from the wide view at {@value #COARSE} and beyond.
     */
    private BufferedImage draw(int level, int superX, int superZ) {
        if (level >= COARSE) {
            sweepOverview();
            synchronized (this) {
                return coarse.get(pack(superX, superZ));   // painted by the sweep, never composed
            }
        }
        long key = pack(superX, superZ);
        if (level == 0) {
            BufferedImage tile = loadLevel0(superX, superZ);
            noteWhole(level, key, tile != null);           // a column is all there or not there
            return tile;
        }
        int[] missing = new int[1];
        BufferedImage built = composeSuperTile(level, superX, superZ, missing);
        noteWhole(level, key, built != null && missing[0] == 0);
        return built;
    }

    private BufferedImage loadLevel0(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        Tile tile = store.liveTile(world, key);
        if (tile == null) {
            tile = store.savedTile(world, key);
        }
        if (tile == null) {
            return null;
        }
        BufferedImage image = tile.toImage();
        adoptSize(image);
        image = toGrid(image);
        return columnGate.test(world, chunkX, chunkZ) ? image : drained(image);
    }

    /**
     * The same tile, faded: half its colour gone and half its light.
     *
     * <p>Colour alone is not enough - map tiles are muted to begin with, and taking half the
     * saturation out of stone and dirt changes nothing anyone can see. Dimming as well is what
     * makes the two kinds of ground tell themselves apart at a glance, while the shape of the
     * land still reads.
     *
     * <p>Done once, when the tile is built, because doing it on every repaint would cost the
     * whole map on every pan.
     */
    private static BufferedImage drained(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int grey = (r * 30 + g * 59 + b * 11) / 100;
                out.setRGB(x, y, (dim(r, grey) << 16) | (dim(g, grey) << 8) | dim(b, grey));
            }
        }
        return out;
    }

    /** Part of the way to grey, and a little darker - enough to tell apart, not to lose. */
    private static int dim(int channel, int grey) {
        double toned = channel * (1 - FADED_COLOUR) + grey * FADED_COLOUR;
        return Math.max(0, Math.min(255, (int) (toned * FADED_LIGHT)));
    }

    /**
     * How much light and colour a tile keeps when it is faded.
     *
     * <p>Gently. Halving both, which is what this used to do, turned dark ground - water, forest,
     * anything in shadow - into the window's own background: the map looked like a thin trail of
     * explored land in a black void. Faded ground has to stay ground.
     */
    private static final double FADED_LIGHT = 0.78;
    private static final double FADED_COLOUR = 0.35;

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
        if (side <= 0 || side <= tileSize) {
            return;                         // never shrink: a small tile is stretched, not obeyed
        }
        synchronized (this) {
            tileSize = side;
            levels.forEach(Map::clear);     // what was built was built to a smaller measure
            incompleteSince.forEach(Map::clear);
            coarse.clear();
        }
    }

    /**
     * A tile at the size the rest of the map is drawn at.
     *
     * <p>Tiles are nearly always the same size, but not quite always - a server can send a
     * smaller one, and an old map can hold one. Laid into the grid as it came, such a tile fills
     * a third of its square and the rest stays black, which is what those occasional wrong-looking
     * tiles were. Stretching it to the grid keeps the map whole; it is one tile, slightly soft.
     */
    private BufferedImage toGrid(BufferedImage tile) {
        int side = tileSize;
        if (tile.getWidth() == side && tile.getHeight() == side) {
            return tile;
        }
        BufferedImage scaled = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(tile, 0, 0, side, side, null);
        g.dispose();
        return scaled;
    }

    /**
     * @param missing filled in with how many of the four quarters had no ground yet - a picture
     *                that is short of one is finished to look at and unfinished in fact, and
     *                telling the two apart is what stops a black corner living forever
     */
    private BufferedImage composeSuperTile(int level, int superX, int superZ, int[] missing) {
        BufferedImage[] quads = new BufferedImage[4];
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            int dx = q & 1;
            int dz = q >> 1;
            BufferedImage child = buildRecursively(level - 1, superX * 2 + dx, superZ * 2 + dz);
            quads[q] = child;
            if (child != null) {
                any = true;
            } else {
                missing[0]++;
            }
        }
        if (!any) {
            return null;
        }
        // The size is read now, not before: how big a tile is is only known once one has been
        // loaded, and the children above are what loads the first of them. Measuring first gave
        // a quarter the size it should be - four children squeezed into the corner of the picture
        // and seven eighths of every tile left black.
        int side = tileSize;
        int half = side / 2;
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
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
    void invalidateChunk(int chunkX, int chunkZ) {
        if (sweeping.get()) {
            // Into the wide view as well, in place: only this tile - a wide view repainted whole
            // for every arriving tile would cost more than it saves. Whether or not the sweep has
            // reached this part of the world yet: ground collected somewhere nobody has been is
            // exactly the ground that has to appear, and waiting for a sweep that has already
            // gone past is waiting forever.
            Tile tile = store.tile(world, pack(chunkX, chunkZ));
            if (tile != null) {
                overview.execute(() -> {
                    paintWide(tile);
                    if (onTileBuilt != null) {
                        onTileBuilt.run();  // and say so, or the window never draws it
                    }
                });
            }
        }
        for (int level = 0; level < levels.size(); level++) {
            int sx = chunkX >> level;
            int sz = chunkZ >> level;
            long key = pack(sx, sz);
            boolean known;
            synchronized (this) {
                if (getPending(level).contains(key)) {
                    // Being drawn, or queued to be: whatever it is drawn from is already out of
                    // date, and the picture it produces will be too. Noted rather than dropped -
                    // dropping it is what left a chequerboard of black squares behind a player
                    // flying into land nobody had mapped, one for every square whose ground
                    // turned up while its turn in the queue was still coming.
                    getStale(level).add(key);
                    continue;
                }
                known = levels.get(level).containsKey(key);
            }
            if (known) {
                rebuild(level, sx, sz);     // replaced when ready, never taken away first
            }
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

    private Set<Long> getStale(int level) {
        while (staleAgain.size() <= level) {
            staleAgain.add(new HashSet<>());
        }
        return staleAgain.get(level);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
