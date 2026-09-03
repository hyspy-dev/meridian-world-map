package meridian.worldmap;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The map on disk: a table of tiles, each with a place of its own.
 *
 * <p>Every tile is addressed by the world and the column it belongs to, so writing one again
 * replaces it rather than adding another copy. That is the whole point of the shape - a map of
 * ground walked over and over used to grow with every visit, and reading it meant reading all of
 * it before anything could be drawn.
 *
 * <p>The file is a header, a run of data segments, and a directory:
 *
 * <pre>
 *   0  "MMP3"                 what this is
 *   4  int    version
 *   8  long   directory offset   &lt;- the commit
 *  16  int    directory length
 *  20  int    segment size
 *  24  (to 64) reserved
 *  64  data segments ...
 *      directory: one fixed record a tile
 * </pre>
 *
 * <p><b>How a write cannot lose the file.</b> Tile payloads go into free segments first, then the
 * directory is written somewhere free, and only then does the header point at it. Every step
 * before the last leaves the file exactly as it was, and the last is a few bytes at a fixed place.
 * A machine that dies mid-write comes back to the previous state, whole.
 *
 * <p>Payloads are deflated with what the JDK ships: the map has to open on a double-click, with
 * nothing on the class path but itself.
 *
 * <p>Everything that touches the directory takes the lock, readers included. A session commits on
 * one thread while the window draws on another, and a reader walking the directory as a commit
 * rewrites it is how the window came to throw on every frame.
 */
final class TableFile implements Closeable {

    /** What a file of this kind starts with, and the version that follows it. */
    private static final byte[] MAGIC = { 'M', 'M', 'P', '3' };
    private static final int VERSION = 3;

    /** Space is handed out in whole segments, so a rewritten tile usually fits where it was. */
    static final int SEGMENT = 512;
    /** Where the data begins; everything before it is the header. */
    private static final long DATA_START = 64;
    /** world (16) + chunk x,z (8) + offset (8) + length (4) + tile size (2) + checksum (4). */
    private static final int RECORD = 42;

    /**
     * Where a tile lives, how much of it there is, and what it looked like.
     *
     * <p>The checksum is what keeps the disk quiet. A server re-sends the same tile every time
     * the player walks past it, and writing an identical picture back over itself is work for
     * nothing - so a tile whose bytes have not changed is not written at all.
     */
    record Slot(long offset, int length, int size, int checksum) {
        int segments() {
            return (length + SEGMENT - 1) / SEGMENT;
        }
    }

    private final Path path;
    private final FileChannel channel;
    /** world → packed chunk coords → where its tile is. */
    private final Map<UUID, Map<Long, Slot>> index = new HashMap<>();
    /** Free runs by offset, in bytes; neighbours are merged as they are given back. */
    private final TreeMap<Long, Integer> free = new TreeMap<>();
    private long end;                       // where the file's used space stops
    private boolean dirty;

    private TableFile(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    /** Opens a table, or lays down an empty one. */
    static TableFile openOrCreate(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        TableFile table = new TableFile(path, channel);
        if (channel.size() < DATA_START) {
            table.end = DATA_START;
            table.writeHeader(DATA_START, 0);
        } else {
            table.readDirectory();
        }
        return table;
    }

    Path path() {
        return path;
    }

    /** Every world the table holds. */
    synchronized Set<UUID> worlds() {
        return new HashSet<>(index.keySet());
    }

    /** Every column of a world that has a tile. */
    synchronized Set<Long> coords(UUID world) {
        Map<Long, Slot> tiles = index.get(world);
        return tiles == null ? Set.of() : new HashSet<>(tiles.keySet());
    }

    synchronized boolean has(UUID world, long key) {
        Map<Long, Slot> tiles = index.get(world);
        return tiles != null && tiles.containsKey(key);
    }

    synchronized int tileCount() {
        int total = 0;
        for (Map<Long, Slot> tiles : index.values()) {
            total += tiles.size();
        }
        return total;
    }

    /** How much of the file is not in use - what rewriting a tile leaves behind. */
    synchronized long freeBytes() {
        long total = 0;
        for (int bytes : free.values()) {
            total += bytes;
        }
        return total;
    }

    /**
     * One tile, read on demand.
     *
     * <p>Nothing is held in memory but the directory, so opening a map of any size costs the same
     * and only the ground being looked at is ever decoded.
     */
    Tile read(UUID world, long key) {
        Slot slot;
        synchronized (this) {
            Map<Long, Slot> tiles = index.get(world);
            slot = tiles == null ? null : tiles.get(key);
        }
        if (slot == null) {
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocate(slot.length());
            synchronized (this) {
                // A channel may hand back less than was asked for; read until it is all there.
                long at = slot.offset();
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, at);
                    if (read < 0) {
                        return null;        // the file ends before the tile does
                    }
                    at += read;
                }
            }
            byte[] rgb = inflate(buffer.array(), slot.size() * slot.size() * 3);
            return Tile.fromRgb(WorldMapStore.chunkX(key), WorldMapStore.chunkZ(key),
                    slot.size(), rgb);
        } catch (IOException | RuntimeException e) {
            return null;                    // one unreadable tile is a hole, not a broken map
        }
    }

    /**
     * Writes a tile into the table, replacing whatever was in its place.
     *
     * <p>The bytes land in free space and the directory is updated in memory; nothing about the
     * file's meaning changes until {@link #commit} says so.
     */
    synchronized boolean write(UUID world, Tile tile) throws IOException {
        byte[] raw = tile.toRgb();
        int checksum = checksum(raw);
        long key = WorldMapStore.pack(tile.chunkX(), tile.chunkZ());
        Map<Long, Slot> tiles = index.computeIfAbsent(world, k -> new HashMap<>());
        Slot was = tiles.get(key);
        if (was != null && was.checksum() == checksum && was.size() == tile.size()) {
            return false;                   // the same picture as before: leave the disk alone
        }
        byte[] payload = deflate(raw);
        if (was != null && was.segments() * SEGMENT >= payload.length) {
            // It fits where the old one was: the commonest case, and it costs no space at all.
            channel.write(ByteBuffer.wrap(payload), was.offset());
            tiles.put(key, new Slot(was.offset(), payload.length, tile.size(), checksum));
        } else {
            long offset = allocate(payload.length);
            channel.write(ByteBuffer.wrap(payload), offset);
            tiles.put(key, new Slot(offset, payload.length, tile.size(), checksum));
            if (was != null) {
                release(was.offset(), was.segments() * SEGMENT);
            }
        }
        dirty = true;
        return true;
    }

    /** What the table already holds for this column, or 0 when it holds nothing. */
    synchronized int checksumOf(UUID world, long key) {
        Map<Long, Slot> tiles = index.get(world);
        Slot slot = tiles == null ? null : tiles.get(key);
        return slot == null ? 0 : slot.checksum();
    }

    /** The bounds of a world's tiles, worked out once from the directory. */
    synchronized int[] bounds(UUID world) {
        Map<Long, Slot> tiles = index.get(world);
        if (tiles == null || tiles.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : tiles.keySet()) {
            int x = WorldMapStore.chunkX(key);
            int z = WorldMapStore.chunkZ(key);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        return new int[] { minX, minZ, maxX, maxZ };
    }

    /** What a tile looks like, in one number - only ever compared, never trusted with meaning. */
    static int checksum(byte[] raw) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(raw);
        return (int) crc.getValue();
    }

    /** Moves everything filed under one world to another - for a world that gets its name late. */
    synchronized void rename(UUID from, UUID to) {
        Map<Long, Slot> tiles = index.remove(from);
        if (tiles == null || tiles.isEmpty()) {
            return;
        }
        index.computeIfAbsent(to, k -> new HashMap<>()).putAll(tiles);
        dirty = true;
    }

    /**
     * Makes everything written since the last time part of the file.
     *
     * <p>The directory goes somewhere free, then the header is pointed at it. Until that last
     * write the file still means what it meant before, so there is no moment at which it is half
     * of one thing and half of another.
     */
    synchronized void commit() throws IOException {
        if (!dirty) {
            return;
        }
        List<byte[]> records = new ArrayList<>();
        index.forEach((world, tiles) -> tiles.forEach((key, slot) -> {
            ByteBuffer record = ByteBuffer.allocate(RECORD);
            record.putLong(world.getMostSignificantBits());
            record.putLong(world.getLeastSignificantBits());
            record.putInt(WorldMapStore.chunkX(key));
            record.putInt(WorldMapStore.chunkZ(key));
            record.putLong(slot.offset());
            record.putInt(slot.length());
            record.putShort((short) slot.size());
            record.putInt(slot.checksum());
            records.add(record.array());
        }));

        int length = records.size() * RECORD;
        long offset = allocate(Math.max(length, 1));
        ByteBuffer out = ByteBuffer.allocate(Math.max(length, 1));
        records.forEach(out::put);
        out.flip();
        channel.write(out, offset);
        channel.force(true);                // the directory is on the disk before it is pointed at
        writeHeader(offset, length);
        channel.force(true);
        // The directory that was there is space again - but only now, after the new one is in.
        long wasOffset = committedDirectoryOffset;
        int wasLength = committedDirectoryLength;
        if (wasLength > 0 && wasOffset >= DATA_START) {
            release(wasOffset, segmentsFor(wasLength) * SEGMENT);
        }
        committedDirectoryOffset = offset;
        committedDirectoryLength = length;
        dirty = false;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    // ------------------------------------------------------------------

    private long committedDirectoryOffset = DATA_START;
    private int committedDirectoryLength;

    private void readDirectory() throws IOException {
        ByteBuffer header = ByteBuffer.allocate((int) DATA_START);
        channel.read(header, 0);
        header.flip();
        byte[] magic = new byte[4];
        header.get(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("not a map table (bad magic)");
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("map table of version " + version + ", this one speaks " + VERSION);
        }
        long directoryOffset = header.getLong();
        int directoryLength = header.getInt();
        committedDirectoryOffset = directoryOffset;
        committedDirectoryLength = directoryLength;

        ByteBuffer records = ByteBuffer.allocate(Math.max(directoryLength, 1));
        channel.read(records, directoryOffset);
        records.flip();
        List<long[]> used = new ArrayList<>();
        while (records.remaining() >= RECORD) {
            UUID world = new UUID(records.getLong(), records.getLong());
            int chunkX = records.getInt();
            int chunkZ = records.getInt();
            long offset = records.getLong();
            int length = records.getInt();
            int size = records.getShort() & 0xFFFF;
            int checksum = records.getInt();
            index.computeIfAbsent(world, k -> new HashMap<>())
                    .put(WorldMapStore.pack(chunkX, chunkZ),
                            new Slot(offset, length, size, checksum));
            used.add(new long[] { offset, segmentsFor(length) * (long) SEGMENT });
        }
        used.add(new long[] { directoryOffset, segmentsFor(directoryLength) * (long) SEGMENT });
        rebuildFreeList(used);
    }

    /**
     * What the file is not using: everything between the runs the directory names.
     *
     * <p>Worked out at open rather than stored, so a session that died mid-write leaves no stale
     * bookkeeping behind - whatever it wrote but never pointed at is simply free again.
     */
    private void rebuildFreeList(List<long[]> used) throws IOException {
        used.sort((a, b) -> Long.compare(a[0], b[0]));
        free.clear();
        long at = DATA_START;
        for (long[] run : used) {
            if (run[0] > at) {
                free.put(at, (int) Math.min(Integer.MAX_VALUE, run[0] - at));
            }
            at = Math.max(at, run[0] + run[1]);
        }
        end = Math.max(at, channel.size());
        if (end > at) {
            free.put(at, (int) Math.min(Integer.MAX_VALUE, end - at));
        }
        end = at;
    }

    /** A run of whole segments big enough for these bytes: reused if there is a hole, else added. */
    private long allocate(int bytes) throws IOException {
        int wanted = segmentsFor(bytes) * SEGMENT;
        for (Map.Entry<Long, Integer> hole : free.entrySet()) {
            if (hole.getValue() >= wanted) {
                long offset = hole.getKey();
                int left = hole.getValue() - wanted;
                free.remove(offset);
                if (left > 0) {
                    free.put(offset + wanted, left);
                }
                return offset;
            }
        }
        long offset = end;
        end += wanted;
        return offset;
    }

    /** Gives a run back, joining it to its neighbours so holes do not fragment forever. */
    private void release(long offset, int bytes) {
        Map.Entry<Long, Integer> before = free.floorEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            offset = before.getKey();
            bytes += before.getValue();
            free.remove(before.getKey());
        }
        Integer after = free.get(offset + bytes);
        if (after != null) {
            free.remove(offset + bytes);
            bytes += after;
        }
        free.put(offset, bytes);
    }

    private static int segmentsFor(int bytes) {
        return Math.max(1, (bytes + SEGMENT - 1) / SEGMENT);
    }

    private void writeHeader(long directoryOffset, int directoryLength) throws IOException {
        ByteBuffer header = ByteBuffer.allocate((int) DATA_START);
        header.put(MAGIC);
        header.putInt(VERSION);
        header.putLong(directoryOffset);
        header.putInt(directoryLength);
        header.putInt(SEGMENT);
        header.position((int) DATA_START);
        header.flip();
        channel.write(header, 0);
    }

    private static byte[] deflate(byte[] raw) {
        // The ordinary level, not the fast one: a tile is 27 KB and packing it costs about a
        // millisecond either way, while the difference stays on the disk for good.
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            byte[] out = new byte[raw.length + 64];
            int length = deflater.deflate(out);
            byte[] exact = new byte[length];
            System.arraycopy(out, 0, exact, 0, length);
            return exact;
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] packed, int expected) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            byte[] out = new byte[expected];
            int length = 0;
            while (length < expected && !inflater.finished()) {
                int read = inflater.inflate(out, length, expected - length);
                if (read == 0) {
                    break;
                }
                length += read;
            }
            return out;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("a tile could not be unpacked", e);
        } finally {
            inflater.end();
        }
    }
}
