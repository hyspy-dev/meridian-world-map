package meridian.worldmap;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JPanel;

/**
 * Map canvas — viewport over a {@link TilePyramid}, with mouse pan / wheel zoom.
 *
 * <p><b>Async tiles.</b> Painting calls
 * {@link TilePyramid#tileIfCached(int, int, int) tileIfCached} (pure lookup,
 * never blocks) and, on a miss, {@link TilePyramid#requestBuild} which enqueues
 * an async build. New tiles pop in via the pyramid's onTileBuilt callback,
 * which repaints us; the EDT never decodes images or walks recursive build
 * trees.
 *
 * <p>Coordinate model: {@link #centerWorldX}, {@link #centerWorldZ} are the
 * world (block) coordinates at the centre of the visible viewport.
 * {@link #pixelsPerBlock} is the zoom — one world block paints this many
 * screen pixels.
 */
final class MapCanvas extends JPanel {

    /**
     * Hard floor on zoom-out. Below this each screen pixel covers an absurd
     * area and the pyramid would have to build very-high-level super-tiles
     * whose recursive descent fills the cache with 4^k EMPTY sentinels per
     * super-tile — at level 11 that's 4 million entries × ~30 bytes each
     * &asymp; 120 MB <em>per</em> super-tile, and a low-zoom viewport asks
     * for dozens. The hard floor at {@code 0.01} caps the worst level at
     * about 7, which keeps the recursion bounded to ~16k per tile.
     */
    private static final double MIN_PIXELS_PER_BLOCK = 0.01;
    /** Hard ceiling — 1 screen px per block = native resolution of the source MapImage. No upscaling allowed. */
    private static final double MAX_PIXELS_PER_BLOCK = 1.0;
    private static final double ZOOM_FACTOR_PER_NOTCH = 1.25;
    /** A chunk column is 32 blocks per side - which has nothing to do with how many pixels
     *  the server draws for it. */
    private static final int BLOCKS_PER_CHUNK = 32;

    private final TilePyramid pyramid;
    private final WorldMapStore store;
    private final UUID world;
    /** Returns the local player's chunk position as {@code [chunkX, chunkZ]}, or {@code null}. */
    private final Supplier<int[]> playerChunkSupplier;
    /** Called with status text on every paint — lets the viewer chrome show coords / level / count. */
    private final Consumer<String> statusSink;
    /** Where the pointer last was, so a repaint does not wipe the coordinates under it. */
    private int hoverX = -1;
    private int hoverZ = -1;
    /** The markers to draw, or null when the window is looking at a saved map on its own. */
    private final Supplier<List<Place>> markers;
    /**
     * The picture a marker asks for, when anything can supply one.
     *
     * <p>The marker art belongs to whoever keeps the markers; this window only asks. Without an
     * answer - the standalone browser, or a proxy with no markers module - each marker is drawn
     * as its own shape, which is what the map did before there were any pictures at all.
     */
    private final Function<String, Image> icons;

    // Viewport state.
    private double centerWorldX;
    private double centerWorldZ;
    private double pixelsPerBlock = 0.25;

    // Drag state.
    private Point dragStart;
    private double dragStartCenterX;
    private double dragStartCenterZ;

    // Follow-player.
    private boolean followPlayer;

    // Diagnostic for status bar.
    private int lastTilesDrawn;
    private int lastLevel;

    MapCanvas(TilePyramid pyramid,
              WorldMapStore store,
              UUID world,
              Supplier<int[]> playerChunkSupplier,
              Supplier<List<Place>> markers,
              Function<String, Image> icons,
              Consumer<String> statusSink) {
        this.pyramid = pyramid;
        this.store = store;
        this.world = world;
        this.playerChunkSupplier = playerChunkSupplier;
        this.markers = markers;
        this.icons = icons;
        this.statusSink = statusSink;

        setBackground(new Color(18, 20, 24));
        setOpaque(true);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragStartCenterX = centerWorldX;
                dragStartCenterZ = centerWorldZ;
                followPlayer = false;
                requestFocusInWindow();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) {
                    return;
                }
                int dx = e.getX() - dragStart.x;
                int dz = e.getY() - dragStart.y;
                centerWorldX = dragStartCenterX - dx / pixelsPerBlock;
                centerWorldZ = dragStartCenterZ - dz / pixelsPerBlock;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hoverX = e.getX();
                hoverZ = e.getY();
                pushHoverStatus(e.getX(), e.getY());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                int notches = -e.getWheelRotation();
                if (notches == 0) {
                    return;
                }
                int mx = e.getX();
                int mz = e.getY();
                int w = getWidth();
                int h = getHeight();

                // World point under the mouse before zoom — keep it pinned across the zoom step.
                double anchorWorldX = centerWorldX + (mx - w / 2.0) / pixelsPerBlock;
                double anchorWorldZ = centerWorldZ + (mz - h / 2.0) / pixelsPerBlock;

                double factor = Math.pow(ZOOM_FACTOR_PER_NOTCH, notches);
                double newZoom = clamp(pixelsPerBlock * factor,
                        MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK);
                if (newZoom == pixelsPerBlock) {
                    return;
                }
                pixelsPerBlock = newZoom;

                centerWorldX = anchorWorldX - (mx - w / 2.0) / pixelsPerBlock;
                centerWorldZ = anchorWorldZ - (mz - h / 2.0) / pixelsPerBlock;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /**
     * Reset zoom & centre. If the player position is known we centre on them
     * at a comfortable mid-zoom; otherwise we auto-fit the saved tile extent
     * so a freshly-opened viewer over offline {@code worldmap.dat} shows
     * something useful instead of an empty area around world origin.
     *
     * <p>If the canvas hasn't been laid out yet ({@code getWidth() == 0}) the
     * fit-to-data path can't compute zoom; we defer with {@code invokeLater}
     * until Swing finishes layout.
     */
    void resetView() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            javax.swing.SwingUtilities.invokeLater(this::resetView);
            return;
        }
        int[] pc = playerChunkSupplier.get();
        if (pc != null) {
            centerWorldX = pc[0] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
            centerWorldZ = pc[1] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
            pixelsPerBlock = 0.25;
        } else {
            fitToTiles();
        }
        repaint();
    }

    private void fitToTiles() {
        int[] bounds = store.tileBounds(world);
        if (bounds == null) {
            centerWorldX = 0;
            centerWorldZ = 0;
            pixelsPerBlock = 0.25;
            return;
        }
        int minBlockX = bounds[0] * BLOCKS_PER_CHUNK;
        int minBlockZ = bounds[1] * BLOCKS_PER_CHUNK;
        int maxBlockX = (bounds[2] + 1) * BLOCKS_PER_CHUNK;
        int maxBlockZ = (bounds[3] + 1) * BLOCKS_PER_CHUNK;
        centerWorldX = (minBlockX + maxBlockX) / 2.0;
        centerWorldZ = (minBlockZ + maxBlockZ) / 2.0;
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        double zoomX = (double) w / (maxBlockX - minBlockX);
        double zoomZ = (double) h / (maxBlockZ - minBlockZ);
        // 0.92 leaves a small margin around the data so edge tiles aren't clipped.
        pixelsPerBlock = clamp(Math.min(zoomX, zoomZ) * 0.92,
                MIN_PIXELS_PER_BLOCK, MAX_PIXELS_PER_BLOCK);
    }

    void setFollowPlayer(boolean follow) {
        followPlayer = follow;
        if (follow) {
            recenterOnPlayer();
        }
    }

    boolean isFollowPlayer() {
        return followPlayer;
    }

    /** Drives follow-player; safe to call regardless of state. */
    void tick() {
        if (followPlayer) {
            recenterOnPlayer();
            return;                     // recentring repaints on its own
        }
        // A marker placed - or a player who has taken a step - while the window sits still would
        // otherwise wait for the next pan. People move, so their positions are what is watched.
        List<Place> now = markers == null ? List.of() : markers.get();
        int state = now.size();
        for (Place place : now) {
            if (place.person()) {
                state = state * 31 + (int) place.x();
                state = state * 31 + (int) place.z();
            }
        }
        if (state != markersDrawn) {
            markersDrawn = state;
            repaint();
        }
    }

    /** What the markers looked like when the map was last drawn: how many, and where people are. */
    private int markersDrawn;

    private void recenterOnPlayer() {
        int[] pc = playerChunkSupplier.get();
        if (pc == null) {
            return;
        }
        centerWorldX = pc[0] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
        centerWorldZ = pc[1] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // Pick pyramid level: one pyramid pixel ≈ one screen pixel (or up to
        // 2× downscaled). Each level halves resolution, so:
        //   level = max(0, ceil(-log2(pixelsPerBlock)))
        // Also cap by the data's natural extent — building higher levels is
        // pointless once a single super-tile already covers the whole world.
        int desiredLevel = Math.max(0, (int) Math.ceil(-log2(pixelsPerBlock)));
        int level = Math.min(desiredLevel, maxUsefulLevel());
        int blocksPerTile = BLOCKS_PER_CHUNK << level;
        double screenPxPerTile = blocksPerTile * pixelsPerBlock;

        double worldMinX = centerWorldX - w / 2.0 / pixelsPerBlock;
        double worldMinZ = centerWorldZ - h / 2.0 / pixelsPerBlock;
        double worldMaxX = worldMinX + w / pixelsPerBlock;
        double worldMaxZ = worldMinZ + h / pixelsPerBlock;

        int superMinX = (int) Math.floor(worldMinX / blocksPerTile);
        int superMaxX = (int) Math.floor(worldMaxX / blocksPerTile);
        int superMinZ = (int) Math.floor(worldMinZ / blocksPerTile);
        int superMaxZ = (int) Math.floor(worldMaxZ / blocksPerTile);

        // Clip the iteration to the actual data bounds — super-tiles outside
        // captured chunks are guaranteed empty, requesting them would just
        // populate the pyramid cache with millions of EMPTY sentinels.
        int[] dataBounds = store.tileBounds(world);
        if (dataBounds != null) {
            int dataMinSuperX = dataBounds[0] >> level;
            int dataMaxSuperX = dataBounds[2] >> level;
            int dataMinSuperZ = dataBounds[1] >> level;
            int dataMaxSuperZ = dataBounds[3] >> level;
            superMinX = Math.max(superMinX, dataMinSuperX);
            superMaxX = Math.min(superMaxX, dataMaxSuperX);
            superMinZ = Math.max(superMinZ, dataMinSuperZ);
            superMaxZ = Math.min(superMaxZ, dataMaxSuperZ);
        }

        Graphics2D g2 = (Graphics2D) g.create();
        // Nearest-neighbor when each tile pixel maps to ≥1 screen pixel (no
        // upscaling), bilinear when downscaling (smoother biome boundaries).
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                screenPxPerTile >= pyramid.tileSize()
                        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int drawn = 0;
        for (int sz = superMinZ; sz <= superMaxZ; sz++) {
            for (int sx = superMinX; sx <= superMaxX; sx++) {
                BufferedImage tile = pyramid.tileIfCached(level, sx, sz);
                if (tile == null) {
                    // Kick off async build — next paint will pick it up when ready.
                    pyramid.requestBuild(level, sx, sz);
                    continue;
                }
                double tileWorldX = (long) sx * blocksPerTile;
                double tileWorldZ = (long) sz * blocksPerTile;
                double screenX = (tileWorldX - worldMinX) * pixelsPerBlock;
                double screenZ = (tileWorldZ - worldMinZ) * pixelsPerBlock;
                int dx0 = (int) Math.floor(screenX);
                int dz0 = (int) Math.floor(screenZ);
                int dx1 = (int) Math.floor(screenX + screenPxPerTile);
                int dz1 = (int) Math.floor(screenZ + screenPxPerTile);
                if (dx1 == dx0) {
                    dx1 = dx0 + 1;
                }
                if (dz1 == dz0) {
                    dz1 = dz0 + 1;
                }
                g2.drawImage(tile, dx0, dz0, dx1 - dx0, dz1 - dz0, null);
                drawn++;
            }
        }
        g2.dispose();

        // Player marker on top.
        int[] pc = playerChunkSupplier.get();
        if (pc != null) {
            double playerWorldX = pc[0] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
            double playerWorldZ = pc[1] * (double) BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2.0;
            double sx = (playerWorldX - centerWorldX) * pixelsPerBlock + w / 2.0;
            double sz = (playerWorldZ - centerWorldZ) * pixelsPerBlock + h / 2.0;
            int radius = 5;
            g.setColor(Color.RED);
            g.fillOval((int) sx - radius, (int) sz - radius, radius * 2 + 1, radius * 2 + 1);
            g.setColor(Color.WHITE);
            g.drawOval((int) sx - radius, (int) sz - radius, radius * 2 + 1, radius * 2 + 1);
        }

        drawMarkers(g, w, h);

        lastTilesDrawn = drawn;
        lastLevel = level;
        // With where the pointer is, not without: a repaint used to blank the coordinates, so
        // they only showed while the mouse was actually moving.
        pushHoverStatus(hoverX, hoverZ);
    }

    /**
     * The map's markers, each a small diamond in its own colour with its name beside it.
     *
     * <p>Drawn over the tiles and under nothing: on a map of downloaded ground, the places
     * somebody thought worth naming are the most useful thing on it.
     */
    private void drawMarkers(Graphics g, int w, int h) {
        List<Place> shown = markers == null ? null : markers.get();
        if (shown == null || shown.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Places first, people over them: a player standing on a marker should not be hidden by
        // the place they are standing on.
        for (Place place : shown) {
            if (!place.person()) {
                draw(g2, place, w, h, false);
            }
        }
        for (Place place : shown) {
            if (place.person()) {
                draw(g2, place, w, h, true);
            }
        }
        g2.dispose();
    }

    /**
     * One marker: a diamond for a place, a circle for a person.
     *
     * <p>A player who has gone offline is drawn faded rather than removed - their last known
     * position is still worth something, and the map says plainly that it is old.
     */
    private void draw(Graphics2D g2, Place marker, int w, int h, boolean person) {
        double sx = (marker.x() - centerWorldX) * pixelsPerBlock + w / 2.0;
        double sz = (marker.z() - centerWorldZ) * pixelsPerBlock + h / 2.0;
        if (sx < -40 || sz < -40 || sx > w + 40 || sz > h + 40) {
            return;                             // off the window: nothing to draw
        }
        boolean faded = person && !marker.online();
        int size = person ? 5 : 4;
        int alpha = faded ? 110 : 255;
        Image picture = icons == null ? null : icons.apply(marker.icon());
        if (picture != null && drawIcon(g2, picture, sx, sz, alpha)) {
            label(g2, marker, sx, sz, ICON_PX / 2, alpha, faded);
            return;                             // the marker's own picture, in place of a shape
        }
        g2.setColor(new Color((marker.colourRgb() & 0xFFFFFF) | (alpha << 24), true));
        if (person) {
            g2.fillOval((int) sx - size, (int) sz - size, size * 2, size * 2);
            g2.setColor(new Color(255, 255, 255, alpha));
            g2.drawOval((int) sx - size, (int) sz - size, size * 2, size * 2);
        } else {
            int[] xs = { (int) sx, (int) sx + size, (int) sx, (int) sx - size };
            int[] zs = { (int) sz - size, (int) sz, (int) sz + size, (int) sz };
            g2.fillPolygon(xs, zs, 4);
            g2.setColor(Color.WHITE);
            g2.drawPolygon(xs, zs, 4);
        }

        label(g2, marker, sx, sz, size, alpha, faded);
    }

    /** How big a marker picture is drawn, whatever size the file happens to be. */
    private static final int ICON_PX = 16;

    /**
     * Draws a marker's own picture, centred on where it stands.
     *
     * @return whether there was anything to draw - an image still being decoded has no size yet,
     *         and the caller falls back to a shape for this pass; the next repaint has it
     */
    private boolean drawIcon(Graphics2D g2, Image picture, double sx, double sz, int alpha) {
        if (picture.getWidth(this) <= 0) {
            return false;
        }
        java.awt.Composite was = g2.getComposite();
        if (alpha < 255) {
            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, alpha / 255f));
        }
        g2.drawImage(picture, (int) sx - ICON_PX / 2, (int) sz - ICON_PX / 2,
                ICON_PX, ICON_PX, this);
        g2.setComposite(was);
        return true;
    }

    /** The marker's name beside it: dark behind, light in front, so it reads against any ground. */
    private void label(Graphics2D g2, Place marker, double sx, double sz, int size,
                       int alpha, boolean faded) {
        String name = marker.name();
        if (name == null || name.isBlank() || pixelsPerBlock <= 0.05) {
            return;
        }
        int tx = (int) sx + size + 2;
        int tz = (int) sz + 4;
        g2.setColor(new Color(0, 0, 0, faded ? 90 : 160));
        g2.drawString(name, tx + 1, tz + 1);
        g2.setColor(new Color(255, 255, 255, alpha));
        g2.drawString(name, tx, tz);
    }

    private void pushHoverStatus(int mouseX, int mouseY) {
        if (statusSink == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("zoom=").append(String.format("%.3f", pixelsPerBlock))
                .append(" px/block · level=").append(lastLevel)
                .append(" · tiles=").append(lastTilesDrawn);
        if (mouseX >= 0 && mouseY >= 0) {
            int w = getWidth();
            int h = getHeight();
            double worldX = centerWorldX + (mouseX - w / 2.0) / pixelsPerBlock;
            double worldZ = centerWorldZ + (mouseY - h / 2.0) / pixelsPerBlock;
            int chunkX = (int) Math.floor(worldX / BLOCKS_PER_CHUNK);
            int chunkZ = (int) Math.floor(worldZ / BLOCKS_PER_CHUNK);
            sb.append("   |   cursor: block ")
                    .append((int) Math.floor(worldX)).append(", ")
                    .append((int) Math.floor(worldZ))
                    .append("   chunk ").append(chunkX).append(", ").append(chunkZ);
        }
        statusSink.accept(sb.toString());
    }

    /**
     * Maximum pyramid level worth building given the data's extent. Once a
     * single super-tile already covers every captured chunk, going higher
     * just wastes recursion (4&times; more EMPTY-sentinel lookups per step,
     * for zero new pixels). Returns {@code 0} when the store is empty —
     * paintComponent then has no tiles to draw anyway.
     */
    private int maxUsefulLevel() {
        int[] bounds = store.tileBounds(world);
        if (bounds == null) {
            return 0;
        }
        int spanX = bounds[2] - bounds[0] + 1;     // in chunks
        int spanZ = bounds[3] - bounds[1] + 1;
        int spanChunks = Math.max(spanX, spanZ);
        // ceil(log2(spanChunks)) — fits-in-1-tile threshold at level k where 2^k >= spanChunks.
        int level = 0;
        while ((1 << level) < spanChunks) {
            level++;
        }
        return level;
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
