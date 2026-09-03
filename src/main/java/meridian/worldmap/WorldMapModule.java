package meridian.worldmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;
import meridian.api.event.EventPriority;
import meridian.api.event.BackendDisconnectedEvent;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.service.ServiceRegistry;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Coverage;
import meridian.core.api.MarkerSource;
import meridian.core.api.Teleport;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.WorldChunks;
import meridian.core.api.WorldMap;
import org.slf4j.Logger;

/**
 * meridian-world-map - the map of a world, kept, and a window to look at it in.
 *
 * <p>Core draws the map: it reads the server's tiles and holds the session's working copy, and
 * lets go of it when the session ends. Keeping those tiles - across worlds, across sessions, in a
 * file that is meant to grow - is this module's whole job, and the two should not be one file.
 *
 * <p>It leans on its neighbours without needing them. {@link Coverage}, when something provides
 * it, says which ground has actually been collected, and the map can be held to that;
 * {@link MarkerSource}, when something provides it, supplies markers and the pictures they are
 * drawn with. With neither there the map still works, and works fully - it keeps every tile the
 * server drew and draws its own shapes. Nothing here may require a neighbour: a module has to be
 * worth installing on its own.
 *
 * <p>Layer 2: nothing here touches a packet, so one build serves every version of the game. The
 * jar is also a program of its own - double-click it and the window opens over what was collected
 * earlier, with no proxy and no server involved.
 */
public class WorldMapModule implements ProxyModule {

    /** Where the archive lives inside the module's own folder. */
    private static final String ARCHIVE = MapViewer.ARCHIVE;

    /**
     * How often what has been collected is written into the map while the session runs.
     *
     * <p>Often enough that a crash costs a minute of exploring, and cheap enough to do that often:
     * a tile goes to its own place in the table, and one that has not changed is not written at
     * all. Nothing happens at all when nothing has arrived.
     */
    private static final Duration COMMIT = Duration.ofSeconds(60);

    private Logger log;
    private WorldMap map;
    private World world;
    private ServiceRegistry services;
    private volatile Coverage coverageCache;
    private CoreMarkers markers;
    private WorldMapStore store;
    private Executor offload;

    /** Keeping tiles at all; off means core's live map still works, nothing is written down. */
    private volatile boolean keeping = true;
    /**
     * Whether every tile is drawn in full colour, or only the ground somebody has collected.
     *
     * <p>Every tile is <em>kept</em> either way - the map is a record and throwing half of it
     * away to save a setting would be a poor trade. What this changes is the drawing: with it
     * off, ground the server merely showed from a distance is drained of half its colour, so the
     * map says at a glance where you have actually been. With nobody able to tell the two apart,
     * there is nothing to fade and the map is drawn plainly.
     */
    /**
     * Whether every tile is drawn in full colour.
     *
     * <p>True by default: a map is a map. Turning the fading on is for when the question is "which
     * of this have I actually collected" - and even then the fading is gentle, because ground that
     * has faded into the background is not telling anyone anything.
     */
    private volatile boolean everyTile = true;
    private volatile String status = "Idle.";
    /** The open window, so a change of setting can reach it. */
    private volatile MapViewer viewer;
    /** The world the last tile belonged to, so a change of world can be noticed. */
    private volatile UUID lastWorld;
    /** How many tiles core has handed over this session - the first half of the story. */
    private final java.util.concurrent.atomic.AtomicInteger offered =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Whether anything has arrived since the last write.
     *
     * <p>The end of a connection is announced once per stream rather than once per disconnect, so
     * without this a hundred-megabyte archive would be written out several times over for the same
     * departure.
     */
    private final java.util.concurrent.atomic.AtomicBoolean arrived =
            new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.map = ctx.services().require(WorldMap.class);
        this.world = ctx.services().get(World.class).orElse(null);
        this.offload = ctx.offloadExecutor();
        // The neighbours are looked up when they are wanted, not now: this module deliberately
        // does not depend on either of them, so at startup they may not have loaded yet.
        this.services = ctx.services();
        this.markers = new CoreMarkers(ctx.services());

        Path dataDir;
        try {
            dataDir = ctx.getDataDir();
            Files.createDirectories(dataDir);
            this.store = new WorldMapStore(dataDir.resolve(ARCHIVE));
            this.store.open();
        } catch (IOException | RuntimeException e) {
            log.error("world-map: cannot use the data directory - disabled: {}", e.toString());
            return;
        }
        log.info("world-map: {} - {} world(s), {} tile(s) kept", dataDir.resolve(ARCHIVE),
                store.worlds().size(), store.tileCount());

        // Every tile core collects is copied into the archive.
        map.onTileChanged(this::onTile);
        // A tile can arrive before the ground under it does, and while it is being held back the
        // open window shows nothing there. When the chunk lands, say so, and the window redraws.
        ctx.services().get(WorldChunks.class).ifPresent(chunks -> chunks.subscribe(
                new WorldChunks.Listener() {
                    @Override
                    public void section(int x, int y, int z, byte[] data, byte[] localLight,
                                        byte[] globalLight) {
                        WorldMapStore open = store;
                        if (open != null) {
                            open.fireUpdate(map.currentWorld(), WorldMap.key(x, z));
                        }
                    }
                }));

        ctx.registerSettings(SettingsSpec.builder()
                .bool("keep", "Keep the map across sessions", true, v -> keeping = v)
                .bool("fadeUncollected", "Fade ground that has not been collected", false,
                        v -> showEveryTile(!v))
                .button("Open map", this::openViewer)
                .liveText("Status", () -> status)
                .persistent("keep", "fadeUncollected")
                .build());

        // While the session runs, what has been collected is written into the map on a timer, so
        // a crash costs a minute of exploring rather than the whole evening.
        ctx.scheduler().scheduleAtFixedRate(
                () -> offload.execute(this::commit), COMMIT, COMMIT);

        // The map is written down when the connection ends and again when the proxy stops. The
        // shutdown one runs here and now: nothing waits for a thread once shutdown has begun.
        ctx.events().subscribe(BackendDisconnectedEvent.class, EventPriority.MONITOR,
                e -> offload.execute(this::commit));
        ctx.onShutdown(this::commit);
        status = describe();
        log.info("meridian-world-map enabled - the map is kept in {}", dataDir.resolve(ARCHIVE));
    }

    @Override
    public void onDisable() {
        commit();
        WorldMapStore open = store;
        if (open != null) {
            open.close();
        }
    }

    // ------------------------------------------------------------------

    /** A tile core has just collected: keep a copy of it. */
    private void onTile(long key) {
        offered.incrementAndGet();
        WorldMapStore open = store;
        if (open == null || !keeping) {
            return;                         // the map is closed, or not being kept
        }
        UUID world = map.currentWorld();
        followWorld(open, world);
        map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key))
                .map(CoreTiles::copyOf)
                .ifPresent(tile -> {
                    open.put(world, tile);
                    arrived.set(true);
                });
    }

    /**
     * Writes the archive out - all of it.
     *
     * <p>Every tile the server drew is kept, including ground only glimpsed: a map is a record,
     * and the difference between seen and collected is worth showing, not worth throwing away.
     */
    /**
     * Keeps up with the world the player is in.
     *
     * <p>The map starts collecting before the server says where the player is: tiles arrive on
     * their own channel and the join lands on another, so the first of them are filed under a
     * placeholder. When the real world is named they are moved across - otherwise they would sit
     * under a world nobody ever looks at, and the window, opened on the real one, would show
     * nothing arriving at all.
     *
     * <p>The open window is told as well, so it follows the player into a new world instead of
     * going quiet on the old one.
     */
    private void followWorld(WorldMapStore open, UUID world) {
        UUID was = lastWorld;
        if (world == null || world.equals(was)) {
            return;
        }
        lastWorld = world;
        if (was != null) {
            log.info("world-map: the world is {} now - moving what was filed under {}",
                    world, was);
            open.migrate(was, world);
        }
        MapViewer window = viewer;
        if (window != null) {
            window.follow(world);
        }
    }

    /**
     * Writes what has been collected into the map.
     *
     * <p>The same thing on the timer and at the end of a session: every tile goes to its own
     * place in the table, so this costs what was collected rather than what the map weighs, and
     * a tile that has not changed since it was written is not written again.
     */
    private void commit() {
        WorldMapStore open = store;
        if (open == null || !open.isOpen() || !arrived.compareAndSet(true, false)) {
            return;                         // nothing new since the last pass
        }
        try {
            long at = System.nanoTime();
            open.commit();
            status = describe();
            log.debug("world-map: committed in {} ms", (System.nanoTime() - at) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            log.warn("world-map: could not write the map: {}", e.toString());
            status = "Could not write the map: " + e;
            arrived.set(true);              // unwritten still, so the next pass tries again
        }
    }

    /** Somebody else's word on what has been collected, if anybody is keeping it. */
    private Coverage coverage() {
        Coverage found = coverageCache;
        if (found == null) {
            found = services.get(Coverage.class).orElse(null);
            coverageCache = found;
        }
        return found;
    }

    /** Opens the window over what is kept, live: tiles arriving now show up as they land. */
    private void openViewer() {
        WorldMapStore open = store;
        if (open == null) {
            return;
        }
        UUID world = map.currentWorld();
        // Offer the right click only when somebody can act on it, so the window can say what a
        // right click would do rather than fail on one.
        boolean canTeleport = services.get(Teleport.class).isPresent();
        SwingUtilities.invokeLater(() -> {
            MapViewer window = new MapViewer(open, world, this::playerChunkIn, this::plain,
                    markers::places, markers::icon, canTeleport ? this::goTo : null);
            viewer = window;
            window.setVisible(true);
        });
    }

    /**
     * A place picked on the map, if anything can take the player there.
     *
     * <p>The map knows a column and nothing more; how high the ground is at it - and what a
     * teleport is allowed to do at all - belongs to whoever provides {@link Teleport}. With no
     * such module installed the map simply says so and stays a map.
     */
    private void goTo(int blockX, int blockZ) {
        Teleport teleport = services.get(Teleport.class).orElse(null);
        if (teleport == null) {
            log.warn("world-map: nothing to teleport with any more");
            return;
        }
        log.info("world-map: going to {}, {}", blockX, blockZ);
        teleport.toColumn(blockX, blockZ);
    }

    /**
     * Whether a chunk's tile is drawn as the server painted it, rather than faded.
     *
     * <p>Collected at any point counts, not just this session: ground downloaded a week ago is
     * ground you have. What is left faded is the rest - seen from afar, never collected.
     */
    private boolean plain(UUID world, int chunkX, int chunkZ) {
        Coverage coverage = coverage();
        return everyTile || coverage == null || coverage.hasEver(world, chunkX, chunkZ);
    }

    /** The setting changed: the open window is redrawn, since its tiles are already built. */
    private void showEveryTile(boolean value) {
        if (everyTile == value) {
            return;
        }
        everyTile = value;
        MapViewer open = viewer;
        if (open != null) {
            open.restyle();
        }
    }

    /**
     * Where the player is, in chunks, for the world being looked at.
     *
     * <p>Null for any other: the window can show a world the player is not in, and drawing them
     * standing in it would be a lie.
     */
    private int[] playerChunkIn(UUID shown) {
        return shown != null && shown.equals(map.currentWorld()) ? playerChunk() : null;
    }

    /** Where the player is, in chunks - for the window's marker and its follow mode. */
    private int[] playerChunk() {
        Vec3 position = world == null ? null
                : world.player().map(Player::position).orElse(null);
        return position == null ? null : new int[] {
                Math.floorDiv((int) position.x(), WorldMap.TILE_BLOCKS),
                Math.floorDiv((int) position.z(), WorldMap.TILE_BLOCKS) };
    }

    private String describe() {
        long spare = store.freeBytes();
        return store.tileCount() + " tile(s) kept, over " + store.worlds().size() + " world(s)"
                + (spare > 0 ? ", " + spare / 1024 + " KB spare inside the file" : "")
                + "   |   this session: " + offered.get() + " offered, "
                + store.takenThisSession() + " new, " + store.unchangedThisSession()
                + " already had";
    }
}
