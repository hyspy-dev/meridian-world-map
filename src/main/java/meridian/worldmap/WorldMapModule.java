package meridian.worldmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * Whether to keep every tile the server draws, or only the ones whose ground was downloaded.
     *
     * <p>They are different maps. The server draws tiles well beyond what it sends blocks for, so
     * keeping everything gives a wide, thin map of places only glimpsed; holding to collected
     * ground gives a map that matches the world in hand, tile for chunk. Only whoever is
     * collecting it can tell the two apart, so with nobody to ask this makes no difference and
     * everything is kept.
     */
    private volatile boolean everyTile;
    private volatile String status = "Idle.";
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
            this.store.load();
        } catch (IOException | RuntimeException e) {
            log.error("world-map: cannot use the data directory - disabled: {}", e.toString());
            return;
        }
        log.info("world-map: {} - {} world(s), {} tile(s) kept", dataDir.resolve(ARCHIVE),
                store.worlds().size(), tilesKept());

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
                .bool("everyTile", "Keep every tile the server draws, downloaded or not", false,
                        v -> everyTile = v)
                .button("Open map", this::openViewer)
                .liveText("Status", () -> status)
                .persistent("keep", "everyTile")
                .build());

        // The map is written down when the connection ends and again when the proxy stops. The
        // shutdown one runs here and now: nothing waits for a thread once shutdown has begun.
        ctx.events().subscribe(BackendDisconnectedEvent.class, EventPriority.MONITOR,
                e -> offload.execute(this::save));
        ctx.onShutdown(this::save);
        status = describe();
        log.info("meridian-world-map enabled - the map is kept in {}", dataDir.resolve(ARCHIVE));
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------------

    /** A tile core has just collected: keep a copy of it. */
    private void onTile(long key) {
        WorldMapStore open = store;
        if (open == null || !keeping) {
            return;
        }
        UUID world = map.currentWorld();
        map.tile(WorldMap.chunkX(key), WorldMap.chunkZ(key))
                .map(CoreTiles::copyOf)
                .ifPresent(tile -> {
                    open.put(world, tile);
                    arrived.set(true);
                });
    }

    /**
     * Writes the archive out.
     *
     * <p>What is kept from this session is decided here: everything, or only the tiles whose
     * ground was actually downloaded. Tiles from earlier sessions are kept either way - they
     * passed this same test when they were written, and a map does not un-remember.
     */
    private void save() {
        WorldMapStore open = store;
        if (open == null || !arrived.compareAndSet(true, false)) {
            return;
        }
        try {
            open.save(this::keep);
            status = describe();
        } catch (IOException | RuntimeException e) {
            log.warn("world-map: could not write the map: {}", e.toString());
            status = "Could not write the map: " + e;
            arrived.set(true);              // unwritten still, so the next pass tries again
        }
    }

    private boolean keep(UUID world, long key) {
        Coverage coverage = coverage();
        if (everyTile || coverage == null) {
            return true;                    // nobody is downloading: every tile there is
        }
        return coverage.hasNow(world, WorldMap.chunkX(key), WorldMap.chunkZ(key));
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
        SwingUtilities.invokeLater(() -> new MapViewer(open, world, this::playerChunk,
                this::show, () -> markers.places(map.currentWorld()), markers::icon)
                .setVisible(true));
    }

    /**
     * Whether a tile of this session may be drawn.
     *
     * <p>The same question the archive asks, for the same reason: a tile the server drew for
     * ground it never sent is a picture of somewhere nobody has been. Tiles kept from earlier
     * sessions are past this gate already and are drawn regardless.
     */
    private boolean show(int chunkX, int chunkZ) {
        Coverage coverage = coverage();
        return everyTile || coverage == null || coverage.hasNow(map.currentWorld(), chunkX, chunkZ);
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
        return tilesKept() + " tile(s) kept, over " + store.worlds().size() + " world(s)"
                + (coverage() == null || everyTile ? "" : " - downloaded ground only");
    }

    private int tilesKept() {
        int tiles = 0;
        for (UUID world : store.worlds()) {
            tiles += store.allCoords(world).size();
        }
        return tiles;
    }
}
