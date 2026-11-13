package app.plusnet.engine;

/**
 * Tracks byte ranges for a download. Chunks are initially spread round-robin over the active
 * networks so the first network whose worker thread happens to run cannot claim the entire file.
 * That is only where each network starts: once its own chunks are gone it takes any waiting chunk,
 * so no network sits idle while there is work left, and the total speed is the sum of the speeds.
 */
final class ChunkMap {
    /** Largest chunk size; smaller files use smaller chunks so both transports can participate. */
    static final int SIZE = 4 << 20;
    private static final int MEDIUM_SIZE = 1 << 20;
    private static final int SMALL_SIZE = 256 << 10;

    private static final byte PENDING = 0, BUSY = 1, DONE = 2;

    final long total;
    final int count;
    final int chunkSize;
    private final byte[] state;
    private final byte[] owner; // network index that delivered each completed chunk
    private final byte[] preferred; // network index initially assigned each chunk; -1 means unassigned
    private final int[] got; // bytes of each chunk already written, from its start
    private int done, busy;
    private long bytes;

    ChunkMap(long total, int[] activeNetworkIndices) {
        this.total = Math.max(0, total);
        if (this.total <= 8L * SIZE) chunkSize = SMALL_SIZE;
        else if (this.total <= 64L * SIZE) chunkSize = MEDIUM_SIZE;
        else chunkSize = SIZE;

        this.count = (int) ((this.total + chunkSize - 1) / chunkSize);
        state = new byte[count];
        owner = new byte[count];
        preferred = new byte[count];
        got = new int[count];

        if (activeNetworkIndices == null || activeNetworkIndices.length == 0) {
            java.util.Arrays.fill(preferred, (byte) -1);
        } else {
            for (int i = 0; i < count; i++) {
                preferred[i] = (byte) activeNetworkIndices[i % activeNetworkIndices.length];
            }
        }
    }

    /** Compatibility constructor for the core unit test: all chunks belong to network zero. */
    ChunkMap(long total) {
        this(total, new int[] {0});
    }

    /**
     * Claim a chunk for one network: its own assigned chunks first, so that every network gets work
     * from the first second, then ANY waiting chunk. A network that is done with its share therefore
     * keeps helping with everyone else's, and the faster network ends up carrying more of the file.
     * A network that is not usable (offline, disabled, failed, or held back by the mode) gets nothing.
     */
    synchronized int claim(int netIndex, boolean[] usable) {
        if (netIndex < 0 || netIndex >= usable.length || !usable[netIndex]) return -1;
        for (int i = 0; i < count; i++) {
            if (state[i] == PENDING && preferred[i] == (byte) netIndex) return markBusy(i);
        }
        for (int i = 0; i < count; i++) if (state[i] == PENDING) return markBusy(i);
        return -1;
    }

    private int markBusy(int i) {
        state[i] = BUSY;
        busy++;
        return i;
    }

    synchronized long from(int i) { return (long) i * chunkSize + got[i]; }

    long to(int i) { return Math.min(total, (long) (i + 1) * chunkSize) - 1; }

    synchronized void add(int i, int len) { got[i] += len; bytes += len; }

    synchronized void finish(int i, int net) { state[i] = DONE; owner[i] = (byte) net; busy--; done++; }

    /** Gives a chunk back (failed, or its stream was stopped); what was written is kept. */
    synchronized void release(int i) {
        state[i] = PENDING;
        busy--;
    }

    synchronized boolean complete() { return done == count; }

    synchronized boolean hasWaiting() { return done + busy < count; }

    synchronized long bytes() { return bytes; }

    /** The file as at most `max` cells: '.', '*' downloading, or a digit once done by that network. */
    synchronized String cells(int max) {
        if (count == 0) return "";
        int group = (count + max - 1) / max;
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < count; s += group) {
            int e = Math.min(count, s + group);
            int[] by = new int[10];
            boolean active = false;
            int finished = 0;
            for (int i = s; i < e; i++) {
                if (state[i] == BUSY) active = true;
                else if (state[i] == DONE) { finished++; by[Math.min(9, owner[i])]++; }
            }
            if (finished == e - s) {
                int top = 0;
                for (int n = 1; n < 10; n++) if (by[n] > by[top]) top = n;
                sb.append((char) ('0' + top));
            } else {
                sb.append(active ? '*' : '.');
            }
        }
        return sb.toString();
    }
}
