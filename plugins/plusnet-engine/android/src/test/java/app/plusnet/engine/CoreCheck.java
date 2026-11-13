package app.plusnet.engine;

/** Assert-based check of the scheduling core; run with `java -ea` (see README). */
public class CoreCheck {
    public static void main(String[] a) throws Exception {
        long total = 80L * ChunkMap.SIZE + 123; // uses 4 MiB chunks; last chunk is short
        ChunkMap m = new ChunkMap(total, new int[] {0, 1});
        assert m.count == 81;
        assert m.to(80) == total - 1 && m.to(0) == ChunkMap.SIZE - 1;

        boolean[] both = {true, true, false};
        int wifi = m.claim(0, both);
        int cell = m.claim(1, both);
        assert wifi == 0 && cell == 1; // round-robin ownership prevents one transport taking all
        m.add(wifi, 1000);
        m.release(wifi); // failed: a later claim resumes where it stopped
        assert m.claim(0, both) == 0 && m.from(0) == 1000;
        m.finish(0, 0);
        m.finish(1, 1);
        assert m.bytes() == 1000 && !m.complete() && m.hasWaiting();

        int n;
        int turn = 0;
        while ((n = m.claim(turn % 2, both)) >= 0) { m.finish(n, turn % 2); turn++; }
        assert m.complete() && !m.hasWaiting() && m.claim(0, both) == -1;
        assert m.cells(100).length() == 81 && m.cells(4).length() == 4;
        assert m.cells(100).startsWith("01"): m.cells(100);

        ChunkMap small = new ChunkMap(3L * 1024 * 1024, new int[] {0, 1});
        assert small.chunkSize == 256 * 1024;
        assert small.count == 12; // a 3 MiB file can now be shared by two networks
        assert small.claim(0, both) == 0;
        assert small.claim(1, both) == 1;

        ChunkMap empty = new ChunkMap(0, new int[] {0}); // an empty file is already complete
        assert empty.complete() && empty.claim(0, both) == -1 && empty.cells(10).isEmpty();

        ChunkMap offline = new ChunkMap(3L * ChunkMap.SIZE, new int[] {0, 1});
        boolean[] onlyCell = {false, true, false};
        assert offline.claim(0, onlyCell) == -1; // An offline network cannot claim work.
        assert offline.claim(1, onlyCell) == 1; // Cellular first claims its own chunk.
        for (int i = 0; i < 23; i++) assert offline.claim(1, onlyCell) >= 0;
        assert offline.claim(1, onlyCell) == 0; // Wi-Fi-owned work can move to cellular if Wi-Fi is offline.

        Limiter l = new Limiter();
        assert l.reserve(1 << 20) == 0; // no cap, no wait
        l.bytesPerSec = 1_000_000;
        l.reserve(500_000);
        long wait = l.reserve(500_000); // the second half-megabyte waits for the first
        assert wait > 400_000_000L && wait < 600_000_000L : wait;
                // Regression: a network that has finished its own share must keep helping with the other's. Before, it went
        // idle while the other network was still working (the file ended up exactly half and half).
        ChunkMap share = new ChunkMap(40L * ChunkMap.SIZE, new int[] {0, 1});
        int doneByMobile = 0;
        while ((n = share.claim(1, both)) >= 0) { share.finish(n, 1); doneByMobile++; }
        assert doneByMobile == share.count && share.complete(); // mobile data alone carried everything it was allowed to

        // Wi-Fi first with this chunk map: chunks are pre-assigned half to each network, yet while mobile data
        // is not allowed to work it can claim nothing and Wi-Fi ends up carrying the whole file.
        ChunkMap first = new ChunkMap(40L * ChunkMap.SIZE, new int[] {0, 1});
        boolean[] wifiOnlyWorks = {true, false, false};
        assert first.claim(1, wifiOnlyWorks) == -1;
        int takenByWifi = 0;
        while ((n = first.claim(0, wifiOnlyWorks)) >= 0) { first.finish(n, 0); takenByWifi++; }
        assert takenByWifi == first.count && first.complete();
        // ...and when Wi-Fi drops mid-download, mobile data picks up exactly where Wi-Fi left off
        ChunkMap handover = new ChunkMap(40L * ChunkMap.SIZE, new int[] {0, 1});
        int w = handover.claim(0, wifiOnlyWorks);
        handover.add(w, 5000);
        handover.release(w); // Wi-Fi stopped halfway through this chunk
        boolean[] mobileOnlyWorks = {false, true, false};
        int m2 = handover.claim(1, mobileOnlyWorks);
        assert m2 >= 0 && handover.from(m2) == (long) m2 * handover.chunkSize + (m2 == w ? 5000 : 0);
        assert m2 == w ? true : handover.claim(1, mobileOnlyWorks) >= 0; // it can reach Wi-Fi's chunks too

        // The rule itself: only the best usable network may work; the rest wait, and take over when it is gone
        java.util.Set<String> up = new java.util.HashSet<>(java.util.Arrays.asList("wifi", "cellular"));
        assert Policy.allowed(false, "wifi", up::contains) && !Policy.allowed(false, "cellular", up::contains);
        up.remove("wifi"); // Wi-Fi dropped: mobile data takes over
        assert !Policy.allowed(false, "wifi", up::contains) && Policy.allowed(false, "cellular", up::contains);
        up.add("wifi"); // and gives way again when it returns
        assert Policy.allowed(false, "wifi", up::contains) && !Policy.allowed(false, "cellular", up::contains);
        up.add("ethernet"); // a cable beats Wi-Fi
        assert Policy.allowed(false, "ethernet", up::contains) && !Policy.allowed(false, "wifi", up::contains);
        up.clear();
        assert !Policy.allowed(false, "wifi", up::contains); // nothing usable: nothing allowed
        up.addAll(java.util.Arrays.asList("wifi", "cellular")); // together: all of them
        assert Policy.allowed(true, "wifi", up::contains) && Policy.allowed(true, "cellular", up::contains);
        System.out.println("core ok");
    }
}
