package meridian.worldmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.swing.SwingUtilities;
import meridian.api.event.EventPriority;
import meridian.api.event.BackendDisconnectedEvent;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.service.ServiceRegistry;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.MapTints;
import meridian.core.api.MarkerSource;
import meridian.core.api.Teleport;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.WorldMap;
import org.slf4j.Logger;

/**
 * meridian-world-map - the map of a world, kept, and a window to look at it in.
 *
 * <p>Core draws the map: it reads the server's tiles and holds the session's working copy, and
 * lets go of it when the session ends. Keeping those tiles - across worlds, across sessions, in a
 * file that is meant to grow - is this module's whole job, and the two should not be one file.
 *
 * <p>It leans on its neighbours without needing them. {@link MapTints} is how they lean back:
 * anything that knows something about a column - that it has not been downloaded, that it does
 * not match the seed - registers a layer and the map draws it, without the map knowing what any
 * of it means. {@link MarkerSource}, when something provides it, supplies markers and the
 * pictures they are drawn with. With neither there the map still works, and works fully - it
 * keeps every tile the server drew and draws its own shapes. Nothing here may require a
 * neighbour: a module has to be worth installing on its own.
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
    private CoreMarkers markers;
    /** What other modules say about the ground, and the arithmetic that makes it one colour. */
    private Tints tints;
    private WorldMapStore store;
    private Executor offload;

    /** Keeping tiles at all; off means core's live map still works, nothing is written down. */
    private volatile boolean keeping = true;
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

    /**
     * Columns core has drawn a tile for, waiting to be copied into the map.
     *
     * <p>Nothing here happens on the thread that reads the packets. A tile arriving used to be
     * copied, checksummed and filed there and then - and once the window was open, that same
     * thread went on to read the map file and repaint the wide view. Which is to say the game
     * stopped while the map thought about itself, for as long as the file took to answer. The
     * connection's thread now writes a number into a set and goes back to the game.
     */
    private final Set<Long> collected = ConcurrentHashMap.newKeySet();
    /** Columns a layer has changed its mind about, waiting to be drawn again. */
    private final Set<Long> repaint = ConcurrentHashMap.newKeySet();
    /** Whether somebody is already working through them, so only ever one is. */
    private final java.util.concurrent.atomic.AtomicBoolean collecting =
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

        // What anybody else knows about the ground - which of it has been downloaded, which of it
        // does not match the seed - is theirs to know and this module's to draw. They say so in
        // colour through here; nothing about what any of it means lives in the map.
        this.tints = new Tints(
                (world, key) -> {                   // one column has changed
                    repaint.add(key);
                    wake();
                },
                world -> {                          // all of it has
                    MapViewer open = viewer;
                    if (open != null) {
                        open.restyle();
                    }
                },
                said -> log.info("world-map: {}", said));
        ctx.services().provide(MapTints.class, tints);

        ctx.registerSettings(SettingsSpec.builder()
                .bool("keep", "Keep the map across sessions", true, v -> keeping = v)
                .button("Open map", this::openViewer)
                .liveList("Colours on the map", this::legend)
                .liveText("Status", () -> status)
                .persistent("keep")
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

    /** A tile core has just collected: note it, and copy it over away from this thread. */
    private void onTile(long key) {
        offered.incrementAndGet();
        if (store == null || !keeping) {
            return;                         // the map is closed, or not being kept
        }
        collected.add(key);
        wake();
    }

    /**
     * Sets somebody to work through what has been noted, unless somebody already is.
     *
     * <p>One at a time and no queue: what is waiting is a set, so a column noted twice before
     * anyone gets to it is one piece of work, and a player flying through a hundred chunks a
     * second cannot pile up a hundred tasks a second behind them.
     */
    private void wake() {
        if (collecting.compareAndSet(false, true)) {
            offload.execute(this::collect);
        }
    }

    /**
     * Copies over every tile noted since the last pass, and says what has landed.
     *
     * <p>Off the network thread, on the executor meant for work that touches a disk - which this
     * does, by way of the checksum the map holds for a tile it already has.
     */
    private void collect() {
        try {
            for (;;) {
                WorldMapStore open = store;
                if (open == null) {
                    return;
                }
                List<Long> tiles = take(collected);
                List<Long> ground = take(repaint);
                if (tiles.isEmpty() && ground.isEmpty()) {
                    return;
                }
                UUID world = map.currentWorld();
                followWorld(open, world);
                for (long key : tiles) {
                    map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key))
                            .map(CoreTiles::copyOf)
                            .ifPresent(tile -> {
                                open.put(world, tile);
                                arrived.set(true);
                            });
                }
                for (long key : ground) {
                    // No new picture, only a new opinion about the old one: the window is told,
                    // and the tile is built again with whatever the layers now say.
                    open.fireUpdate(world, key);
                }
            }
        } finally {
            collecting.set(false);
            // Anything noted while the last pass was finishing found somebody working and went
            // away again; look once more, and start again if it is still there.
            if (!collected.isEmpty() || !repaint.isEmpty()) {
                wake();
            }
        }
    }

    /** Empties a set into a list - what was waiting when this was called, and nothing after. */
    private static List<Long> take(Set<Long> from) {
        if (from.isEmpty()) {
            return List.of();
        }
        List<Long> batch = new ArrayList<>(from);
        from.removeAll(batch);
        return batch;
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

    /**
     * What the colours on the map mean, as the layers themselves describe them.
     *
     * <p>Read straight off the registered layers, so it says what is actually being drawn rather
     * than what somebody wrote down here once.
     */
    private List<String> legend() {
        Tints open = tints;
        if (open == null || !open.any()) {
            return List.of("Nothing is colouring the map.");
        }
        List<String> rows = new ArrayList<>();
        for (meridian.core.api.MapTint layer : open.layers()) {
            rows.add(layer.name());
            for (meridian.core.api.MapTint.Paint paint : layer.states()) {
                rows.add("    " + paint.label() + "   #"
                        + String.format("%08X", paint.argb()).substring(2));
            }
        }
        return rows;
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
            MapViewer window = new MapViewer(open, world, this::playerChunkIn, tints,
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
