package meridian.worldmap;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Map viewer — a JFrame over a {@link MapCanvas}, with toolbar (follow / reset
 * view) and a status bar showing zoom, level, tile count and cursor coords.
 *
 * <p>Two entry points:
 * <ul>
 *   <li><b>In-proxy.</b> {@link WorldMapModule} opens the viewer with a player-position
 *       supplier wired to core's player, so the map updates live as tiles arrive and
 *       {@code Follow player} works.</li>
 *   <li><b>On its own.</b> {@link #main} opens the archive lying beside the jar - or, when
 *       there is none, asks for one to be dropped on the window - and browses it read-only:
 *       no live updates and no player, just whatever was collected before.</li>
 * </ul>
 */
public final class MapViewer extends JFrame {

    private final WorldMapStore store;
    private final TilePyramid pyramid;
    private final MapCanvas canvas;
    private final BiConsumer<UUID, Long> storeListener;
    private final Timer followTimer;
    private final UUID world;

    /**
     * Opens a viewer over {@code store} for {@code world}. {@code playerChunk}
     * returns {@code [chunkX, chunkZ]} of the local player or {@code null}
     * when unknown — pass a no-op supplier in CLI mode. {@code chunkGate}
     * decides whether a <em>live</em> tile is rendered: a tile whose chunk
     * hasn't arrived in this session is hidden. Saved (previous-session)
     * tiles bypass the gate. CLI mode passes a permissive gate
     * ({@code (x, z) -> true}) so the offline browser shows every persisted
     * tile.
     */
    public MapViewer(WorldMapStore store, UUID world, Supplier<int[]> playerChunk,
                     TilePyramid.ChunkPredicate chunkGate) {
        this(store, world, playerChunk, chunkGate, null);
    }

    /**
     * @param markers the places worth naming on this map, or null when there is nobody to ask -
     *                the offline browser has only the tiles it was given
     */
    public MapViewer(WorldMapStore store, UUID world, Supplier<int[]> playerChunk,
                     TilePyramid.ChunkPredicate chunkGate,
                     Supplier<java.util.List<Place>> markers) {
        this(store, world, playerChunk, chunkGate, markers, null);
    }

    /**
     * @param icons the picture each marker asks for, or null when nothing can supply one and the
     *              map should draw its own shapes instead
     */
    public MapViewer(WorldMapStore store, UUID world, Supplier<int[]> playerChunk,
                     TilePyramid.ChunkPredicate chunkGate,
                     Supplier<java.util.List<Place>> markers,
                     java.util.function.Function<String, java.awt.Image> icons) {
        super("Meridian — World Map");
        this.store = store;

        // If the passed world has no tiles (e.g., proxy started but no
        // JoinWorld yet, so currentWorld is the SENTINEL — yet the persisted
        // worldmap.dat is keyed under the real UUID) fall back to whichever
        // world actually has data. Otherwise the viewer would open over an
        // empty UUID and look broken.
        UUID effectiveWorld = world;
        if (store.tileBounds(world) == null) {
            UUID fallback = pickWorld(store, store.worlds());
            if (fallback != null) {
                effectiveWorld = fallback;
            }
        }
        this.world = effectiveWorld;
        final UUID viewWorld = effectiveWorld;

        JLabel status = new JLabel(" ");
        // Forward declaration: pyramid needs the canvas for its onTileBuilt
        // callback, canvas needs the pyramid for lookups. Resolved via array
        // box so we can capture & set inside lambdas before assignment.
        MapCanvas[] canvasBox = new MapCanvas[1];
        this.pyramid = new TilePyramid(store, viewWorld, chunkGate,
                () -> SwingUtilities.invokeLater(() -> {
                    if (canvasBox[0] != null) {
                        canvasBox[0].repaint();
                    }
                }));
        this.canvas = new MapCanvas(pyramid, store, viewWorld, playerChunk, markers, icons,
                status::setText);
        canvasBox[0] = canvas;

        // Live capture: invalidate pyramid + repaint when a tile changes.
        this.storeListener = (uuid, key) -> {
            if (!uuid.equals(viewWorld)) {
                return;
            }
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) (long) key;
            pyramid.invalidateChunk(chunkX, chunkZ);
            SwingUtilities.invokeLater(canvas::repaint);
        };
        store.addUpdateListener(storeListener);

        // Toolbar.
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JToggleButton followBtn = new JToggleButton("Follow player");
        // Always enabled — if the player position isn't known yet at click
        // time (pre-handshake, or just no ClientMovement seen), the toggle
        // is harmless: setFollowPlayer keeps the flag on, the 250 ms
        // follow-tick polls the supplier, and the first non-null result
        // snaps the view to the player. Don't disable the button up front
        // based on a one-shot check — the supplier becomes valid later.
        followBtn.setToolTipText("Centre on the player and keep following at the current zoom");
        followBtn.addActionListener(e -> canvas.setFollowPlayer(followBtn.isSelected()));
        toolbar.add(followBtn);
        JButton resetBtn = new JButton("Reset view");
        resetBtn.addActionListener(e -> canvas.resetView());
        toolbar.add(resetBtn);

        JPanel root = new JPanel(new BorderLayout());
        root.add(toolbar, BorderLayout.NORTH);
        root.add(canvas, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1100, 850);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        // Title bar = diagnostic dashboard. Shows file path, file size,
        // total world count, the world we ended up rendering, and its bounds
        // — so any "I see nothing" issue is one screenshot away from a fix.
        StringBuilder title = new StringBuilder("Meridian — World Map");
        try {
            java.nio.file.Path file = store.file();
            long bytes = java.nio.file.Files.exists(file)
                    ? java.nio.file.Files.size(file) : -1;
            title.append("   |   ").append(file);
            title.append(" (").append(bytes >= 0 ? (bytes + " B") : "missing").append(")");
        } catch (java.io.IOException ignored) {
        }
        title.append("   |   load=").append(store.loadStatus());
        title.append("   |   worlds=").append(store.worlds().size());
        int[] b = store.tileBounds(viewWorld);
        if (b != null) {
            title.append("   |   view=").append(viewWorld)
                    .append(" (").append(store.allCoords(viewWorld).size())
                    .append(" tiles, chunks [").append(b[0]).append(",").append(b[1])
                    .append("] → [").append(b[2]).append(",").append(b[3]).append("])");
        } else {
            title.append("   |   view=").append(viewWorld).append(" (no tiles)");
        }
        setTitle(title.toString());
        // Initial centring runs once the canvas has a real width/height so
        // the auto-fit-to-data branch has something to compute against.
        SwingUtilities.invokeLater(canvas::resetView);
        followBtn.setSelected(canvas.isFollowPlayer());

        // Follow-player tick — drives the centre-on-player update when the
        // toggle is on. No-op when off, so we can poll fast for smoothness;
        // recenter+repaint is cheap (one volatile read + one center update).
        this.followTimer = new Timer(100, e -> canvas.tick());
        followTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                store.removeUpdateListener(storeListener);
                followTimer.stop();
                pyramid.shutdown();
            }
        });
    }

    /**
     * The map on its own: a double-click on the jar, with no proxy and no server behind it.
     *
     * <p>Looks for the archive where the module keeps it - beside the jar, in the module's own
     * folder - and opens it. When there is none to find, it asks: a window that takes an archive
     * dropped onto it, and a markers file with it if there is one. A path given on the command
     * line is taken as it stands and skips the looking.
     */
    public static void main(String[] args) {
        Path file = args.length > 0 ? Path.of(args[0]) : ownArchive();
        SwingUtilities.invokeLater(() -> {
            if (file != null && java.nio.file.Files.isReadable(file)
                    && open(file, java.util.List.of()) != null) {
                return;
            }
            new OpenPrompt(file).setVisible(true);
        });
    }

    /**
     * Opens a saved archive read-only, or returns null after saying why it could not.
     *
     * <p>Every tile in the file is shown: each passed the module's filter on the way in, so there
     * is nothing further to hold back, and no live session here to hold it against.
     */
    static MapViewer open(Path file, java.util.List<Place> markers) {
        WorldMapStore store = new WorldMapStore(file);
        store.load();
        Set<UUID> worlds = store.worlds();
        if (worlds.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "There is no map in " + file + " - it holds no worlds.",
                    "Nothing to show", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        // The world with the most tiles is almost certainly the one that was played in; the rest
        // tend to be a single tile filed under a placeholder.
        UUID best = pickWorld(store, worlds);
        java.util.List<Place> shown = java.util.List.copyOf(markers);
        MapViewer viewer = new MapViewer(store, best, () -> null, (x, z) -> true, () -> shown);
        if (!shown.isEmpty()) {
            viewer.setTitle(viewer.getTitle() + "   |   " + shown.size() + " marker(s)");
        }
        viewer.setVisible(true);
        if (worlds.size() > 1) {
            JOptionPane.showMessageDialog(viewer,
                    worlds.size() + " worlds in this archive; showing the largest (" + best + ").",
                    "Multiple worlds", JOptionPane.INFORMATION_MESSAGE);
        }
        return viewer;
    }

    /**
     * The archive this jar's own module keeps, if the jar is sitting where modules live.
     *
     * <p>The proxy gives every module a folder of its own beside the jars, so from where this
     * class was loaded the archive is one predictable step away. A guess, and treated as one:
     * when it is wrong the window simply asks.
     */
    private static Path ownArchive() {
        try {
            Path jar = Path.of(MapViewer.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path modules = jar.getParent();
            return modules == null ? null
                    : modules.resolve(MODULE_NAME).resolve("data").resolve(ARCHIVE);
        } catch (RuntimeException | java.net.URISyntaxException e) {
            return null;                    // started from somewhere unusual: just ask
        }
    }

    /** The module's folder and its file, named the way the proxy and the module itself do. */
    static final String MODULE_NAME = "Meridian World Map";
    static final String ARCHIVE = "worldmap.dat";

    private static UUID pickWorld(WorldMapStore store, Set<UUID> worlds) {
        UUID best = null;
        int bestCount = -1;
        for (UUID w : worlds) {
            int count = store.allCoords(w).size();
            if (count > bestCount) {
                bestCount = count;
                best = w;
            }
        }
        return best;
    }
}
