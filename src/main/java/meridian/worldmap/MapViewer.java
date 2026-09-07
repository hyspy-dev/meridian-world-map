package meridian.worldmap;

import java.awt.BorderLayout;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The map window — a {@link MapCanvas} over a {@link TilePyramid}, with a toolbar (which world,
 * follow the player, reset the view) and a status line showing zoom, level, tile count and the
 * coordinates under the cursor.
 *
 * <p><b>Worlds.</b> An archive holds every world that was played in, and the window shows one of
 * them at a time - picked from the toolbar. Switching worlds builds a fresh pyramid for it: the
 * tiles, the markers and the player all belong to a world, and none of them mean anything in
 * another one.
 *
 * <p>Two entry points:
 * <ul>
 *   <li><b>In-proxy.</b> {@link WorldMapModule} opens the window on the world being played, with
 *       the player's position wired in, so the map fills in live and {@code Follow player}
 *       works.</li>
 *   <li><b>On its own.</b> {@link #main} opens the archive lying beside the jar - or, when there
 *       is none, asks for one to be dropped on the window - and browses it read-only: no live
 *       updates and no player, just whatever was collected before.</li>
 * </ul>
 */
public final class MapViewer extends JFrame {

    /** The module's folder and its file, named the way the proxy and the module itself do. */
    static final String MODULE_NAME = "Meridian World Map";
    static final String ARCHIVE = "worldmap.dat";

    private final WorldMapStore store;
    private final Function<UUID, int[]> playerChunk;
    private final TilePyramid.ChunkPredicate chunkGate;
    private final Function<UUID, List<Place>> markers;
    private final Function<String, Image> icons;
    private final BiConsumer<Integer, Integer> teleport;

    private final JLabel status = new JLabel(" ");
    private final JPanel root = new JPanel(new BorderLayout());
    private final JComboBox<WorldChoice> worlds = new JComboBox<>();
    private final JToggleButton followBtn = new JToggleButton("Follow player");
    private final Timer followTimer;
    private final Timer repaintTimer;
    /** Set when tiles have come in; cleared by the redraw that shows them. */
    private final java.util.concurrent.atomic.AtomicBoolean arrived =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** How often the window fills in what is missing and draws what has arrived. */
    private static final int REDRAW_MILLIS = 100;

    /** How often it says in the log how it is getting on - and only when that has changed. */
    private static final long SAY_EVERY_MILLIS = 1000;

    private long saidAt;
    private String lastSaid = "";
    private final BiConsumer<UUID, Long> storeListener;

    /** The world on screen, and the two things built for it. */
    private UUID world;
    private TilePyramid pyramid;
    private MapCanvas canvas;
    /** Set while the combo is being rebuilt, so its own events are not mistaken for a choice. */
    private boolean picking;
    /** Set once somebody chooses a world themselves; the window stops following after that. */
    private boolean picked;

    /**
     * Opens the window over {@code store}, showing {@code world}.
     *
     * @param playerChunk {@code [chunkX, chunkZ]} of the player in that world, or null when they
     *                    are not in it - the window asks per world, since the player is only ever
     *                    in one
     * @param chunkGate   whether a chunk's tile is drawn in full colour rather than faded
     * @param markers     the places worth naming in a world, or null when there is nobody to ask
     * @param icons       the picture a marker asks for, or null to draw plain shapes
     * @param teleport    where a right click sends the player, or null when nothing can send them
     */
    public MapViewer(WorldMapStore store, UUID world, Function<UUID, int[]> playerChunk,
                     TilePyramid.ChunkPredicate chunkGate, Function<UUID, List<Place>> markers,
                     Function<String, Image> icons, BiConsumer<Integer, Integer> teleport) {
        super("Meridian - World Map");
        this.store = store;
        this.playerChunk = playerChunk == null ? w -> null : playerChunk;
        this.chunkGate = chunkGate;
        this.markers = markers;
        this.icons = icons;
        this.teleport = teleport;

        // A world with no tiles is no use to look at: the proxy may still be waiting for its first
        // join, and the archive is keyed by the real world. Fall back to whichever has the most.
        UUID opening = world;
        if (store.tileBounds(opening) == null) {
            UUID fallback = pickWorld(store, store.worlds());
            if (fallback != null) {
                opening = fallback;
            }
        }

        buildToolbar();
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1100, 850);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // A tile arriving refreshes the map. It comes from the archive, which is written to while
        // the window is open - and from whichever thread wrote it, so this does as little as it
        // can and leaves the rest to the window's own clock. Asking the archive how many worlds
        // it holds is one of the things it must not do: that reads the file's directory under its
        // lock, and it was being asked once per arriving tile.
        this.storeListener = (uuid, key) -> {
            if (!uuid.equals(this.world)) {
                return;
            }
            pyramid.invalidateChunk((int) (key >> 32), (int) (long) key);
            // Marked, not drawn. A player flying fast brings tiles in by the hundred, and
            // redrawing the window for each one keeps it too busy to draw any of them.
            arrived.set(true);
        };
        store.addUpdateListener(storeListener);

        // What arrived since the last frame is drawn on the next one, no more often than the eye
        // can tell - which is what keeps a busy flight from making the window unusable.
        this.repaintTimer = new Timer(REDRAW_MILLIS, e -> tick());
        repaintTimer.start();

        show(opening);
        listWorlds();

        // Follow-player tick — drives the centre-on-player update when the toggle is on. A no-op
        // when it is off, so polling this fast costs nothing.
        this.followTimer = new Timer(100, e -> canvas.tick());
        followTimer.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                store.removeUpdateListener(storeListener);
                followTimer.stop();
                repaintTimer.stop();
                pyramid.shutdown();
            }
        });
    }

    /**
     * Follows the player into a world, unless somebody has chosen one to look at.
     *
     * <p>The window opens on whatever world is known at the time, which early in a session may be
     * a placeholder or the last one played. When the real one is named, the window goes there -
     * but only while nobody has picked a world from the toolbar: someone browsing an old world
     * should not be dragged away from it.
     */
    public void follow(UUID played) {
        if (played == null || played.equals(world) || picked) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!picked && !played.equals(world)) {
                show(played);
                listWorlds();
            }
        });
    }

    /**
     * One turn of the window's own clock: fill in what the screen is short of, draw what has
     * come in, and every so often say in the log how it is getting on.
     *
     * <p>Everything the window does to keep itself up to date happens here, on a timer, rather
     * than hanging off whatever finished last. That is deliberate: a map that fills itself only
     * when something else speaks up stops filling the moment nothing does.
     */
    private void tick() {
        int asked = canvas.topUp();
        if (asked > 0) {
            arrived.set(true);              // something is coming; draw it when it lands
        }
        if (arrived.compareAndSet(true, false)) {
            canvas.repaint();
        }
        long now = System.currentTimeMillis();
        if (now - saidAt >= SAY_EVERY_MILLIS) {
            saidAt = now;
            if (worlds.getItemCount() != store.worlds().size()) {
                listWorlds();               // a world nobody had seen before has tiles now
            }
            String line = "world " + (world == null ? "?" : world.toString().substring(0, 8))
                    + "  " + canvas.frame() + "  " + pyramid.work()
                    + "  asked=" + asked;
            if (!line.equals(lastSaid)) {   // a still window has nothing to say twice
                lastSaid = line;
                System.out.println("[world-map] " + line);
            }
        }
    }

    /** Draws the map again from scratch, for when what counts as collected ground has changed. */
    public void restyle() {
        pyramid.invalidateAll();
        SwingUtilities.invokeLater(canvas::repaint);
    }

    // ------------------------------------------------------------------
    // Worlds
    // ------------------------------------------------------------------

    /** One world as the picker shows it: its id, shortened, and how much of it there is. */
    private record WorldChoice(UUID id, int tiles) {
        @Override
        public String toString() {
            return id.toString().substring(0, 8) + "…  " + tiles + " tiles";
        }
    }

    /** Puts a world on screen, building everything that belongs to it. */
    private void show(UUID chosen) {
        if (pyramid != null) {
            pyramid.shutdown();
        }
        if (canvas != null) {
            root.remove(canvas);
        }
        this.world = chosen;

        // The pyramid needs the canvas for its "a tile is ready" callback and the canvas needs the
        // pyramid to draw; the box breaks the circle.
        MapCanvas[] box = new MapCanvas[1];
        // A finished build asks for a redraw, and the redraw asks for the next few tiles. It is
        // marked rather than drawn: sixty finished tiles are one frame's worth of news.
        this.pyramid = new TilePyramid(store, chosen, chunkGate, () -> arrived.set(true));
        this.canvas = new MapCanvas(pyramid, store, chosen,
                () -> playerChunk.apply(chosen),
                markers == null ? null : () -> markers.apply(chosen),
                icons, teleport, status::setText);
        box[0] = canvas;

        root.add(canvas, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        followBtn.setSelected(canvas.isFollowPlayer());
        setTitle(describe(chosen));
        // Centring waits for the canvas to have a size, so fitting to the data has something to
        // measure against.
        SwingUtilities.invokeLater(canvas::resetView);
    }

    /** Fills the picker from the archive, keeping the world on screen selected. */
    private void listWorlds() {
        List<WorldChoice> choices = new ArrayList<>();
        for (UUID id : store.worlds()) {
            choices.add(new WorldChoice(id, store.allCoords(id).size()));
        }
        choices.sort(Comparator.comparingInt(WorldChoice::tiles).reversed());
        picking = true;
        try {
            worlds.setModel(new DefaultComboBoxModel<>(choices.toArray(new WorldChoice[0])));
            for (WorldChoice choice : choices) {
                if (choice.id().equals(world)) {
                    worlds.setSelectedItem(choice);
                    break;
                }
            }
        } finally {
            picking = false;
        }
        worlds.setEnabled(choices.size() > 1);
        worlds.setToolTipText(choices.size() > 1
                ? "Which world to look at - the archive holds " + choices.size()
                : "The archive holds one world");
    }

    private void buildToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("World: "));
        worlds.addActionListener(e -> {
            WorldChoice chosen = (WorldChoice) worlds.getSelectedItem();
            if (!picking && chosen != null && !chosen.id().equals(world)) {
                picked = true;              // a choice of their own outranks following the player
                show(chosen.id());
            }
        });
        toolbar.add(worlds);
        toolbar.addSeparator();

        // Always enabled: the player's position may not be known yet, and the toggle simply waits
        // for it - the follow tick snaps the view as soon as there is one.
        followBtn.setToolTipText("Centre on the player and keep following at the current zoom");
        followBtn.addActionListener(e -> canvas.setFollowPlayer(followBtn.isSelected()));
        toolbar.add(followBtn);
        JButton resetBtn = new JButton("Reset view");
        resetBtn.addActionListener(e -> canvas.resetView());
        toolbar.add(resetBtn);
        root.add(toolbar, BorderLayout.NORTH);
    }

    /**
     * The title bar, which doubles as the diagnostics: where the archive is, how it loaded, how
     * many worlds it holds, and what is on screen. Any "I see nothing" is then one screenshot
     * away from an answer.
     */
    private String describe(UUID shown) {
        StringBuilder title = new StringBuilder("Meridian - World Map");
        try {
            Path file = store.file();
            long bytes = java.nio.file.Files.exists(file) ? java.nio.file.Files.size(file) : -1;
            title.append("   |   ").append(file)
                    .append(" (").append(bytes >= 0 ? (bytes + " B") : "missing").append(")");
        } catch (java.io.IOException ignored) {
            // The size is a courtesy; a title is not worth failing over.
        }
        title.append("   |   load=").append(store.loadStatus());
        title.append("   |   worlds=").append(store.worlds().size());
        int[] bounds = store.tileBounds(shown);
        if (bounds != null) {
            title.append("   |   view=").append(shown)
                    .append(" (").append(store.allCoords(shown).size())
                    .append(" tiles, chunks [").append(bounds[0]).append(",").append(bounds[1])
                    .append("] -> [").append(bounds[2]).append(",").append(bounds[3]).append("])");
        } else {
            title.append("   |   view=").append(shown).append(" (no tiles)");
        }
        return title.toString();
    }

    // ------------------------------------------------------------------
    // On its own
    // ------------------------------------------------------------------

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
                    && open(file, List.of()) != null) {
                return;
            }
            new OpenPrompt(file).setVisible(true);
        });
    }

    /**
     * Opens a saved archive read-only, or returns null after saying why it could not.
     *
     * <p>Every world in it is there to pick from; the one with the most tiles opens first.
     */
    static MapViewer open(Path file, List<Place> markers) {
        WorldMapStore store = new WorldMapStore(file);
        store.open();
        Set<UUID> worlds = store.worlds();
        if (worlds.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "There is no map in " + file + " - it holds no worlds.",
                    "Nothing to show", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        List<Place> shown = List.copyOf(markers);
        MapViewer viewer = new MapViewer(store, pickWorld(store, worlds), w -> null,
                (w, x, z) -> true, shown.isEmpty() ? null : w -> shown, null, null);
        if (!shown.isEmpty()) {
            viewer.setTitle(viewer.getTitle() + "   |   " + shown.size() + " marker(s)");
        }
        viewer.setVisible(true);
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

    /** The world with the most tiles - almost certainly the one that was played in. */
    private static UUID pickWorld(WorldMapStore store, Set<UUID> worlds) {
        UUID best = null;
        int bestCount = -1;
        for (UUID world : worlds) {
            int count = store.allCoords(world).size();
            if (count > bestCount) {
                bestCount = count;
                best = world;
            }
        }
        return best;
    }
}
