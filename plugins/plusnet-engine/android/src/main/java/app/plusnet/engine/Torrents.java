package app.plusnet.engine;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The one BitTorrent session of the app (libtorrent4j), started when the first torrent needs it and
 * stopped again after a while with nothing to do. Reached through Lt (see there).
 */
final class Torrents {
    private static final long IDLE_STOP_MS = 90_000;

    private static Object session;
    private static long lastUse;
    private static volatile CountDownLatch added;
    private static volatile Object addedHandle;

    private Torrents() { }

    static boolean available() { return Lt.present(); }

    static boolean isMagnet(String s) { return s.regionMatches(true, 0, "magnet:", 0, 7); }

    /** The "dn=" (display name) of a magnet link, if it has one. */
    static String magnetName(String magnet) {
        int q = magnet.indexOf('?');
        if (q < 0) return null;
        for (String kv : magnet.substring(q + 1).split("&")) {
            if (kv.startsWith("dn=")) {
                try { return URLDecoder.decode(kv.substring(3), "UTF-8"); } catch (Exception e) { return null; }
            }
        }
        return null;
    }


    private static synchronized Object session() throws IOException {
        if (session == null) {
            Object s;
            try {
                s = Lt.find("org.libtorrent4j.SessionManager").getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IOException("The torrent engine could not start: " + e);
            }
            Class<?> listener = Lt.find("org.libtorrent4j.AlertListener");
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (p, m, a) -> {
                if (m.getDeclaringClass() == Object.class) {
                    switch (m.getName()) {
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == a[0];
                        default: return "PlusNetTorrentAlerts";
                    }
                }
                if (m.getName().equals("alert") && a != null && a.length == 1 && a[0] != null) onAlert(a[0]);
                return null; // types(): null means every kind of alert
            });
            Lt.call(s, "addListener", proxy);
            Lt.call(s, "start");
            session = s;
        }
        lastUse = System.currentTimeMillis();
        return session;
    }

    private static void onAlert(Object alert) {
        if (!alert.getClass().getSimpleName().equals("AddTorrentAlert")) return;
        try {
            addedHandle = Lt.call(alert, "handle");
        } catch (IOException e) {
            addedHandle = null;
        }
        CountDownLatch l = added;
        if (l != null) l.countDown();
    }

    /** The .torrent file's contents of a magnet link, found through the network (can take a minute). Null if no one answered. */
    static byte[] fetchMagnet(String magnet, int seconds, File tempDir) throws IOException {
        return (byte[]) Lt.call(session(), "fetchMagnet", magnet, seconds, tempDir);
    }

    static Object info(byte[] torrentFile) throws IOException {
        return Lt.callStatic(Lt.find("org.libtorrent4j.TorrentInfo"), "bdecode", torrentFile);
    }

    /** Starts downloading into `saveDir`; the returned handle is what status and pause are asked of. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static synchronized Object add(Object torrentInfo, File saveDir) throws IOException {
        Object s = session();
        Class<?> priority = Lt.find("org.libtorrent4j.Priority");
        int files = (int) Lt.num(Lt.call(torrentInfo, "numFiles"));
        Object priorities = Array.newInstance(priority, files);
        Object normal = Enum.valueOf((Class<? extends Enum>) priority, "NORMAL");
        for (int i = 0; i < files; i++) Array.set(priorities, i, normal);
        Object flags = Lt.callStatic(Lt.find("org.libtorrent4j.swig.torrent_flags_t"), "from_int", 0);

        CountDownLatch latch = new CountDownLatch(1);
        added = latch;
        addedHandle = null;
        Lt.call(s, "download", torrentInfo, saveDir, null, priorities, null, flags);
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IOException("The torrent engine did not answer");
        } catch (InterruptedException e) {
            throw new IOException("Interrupted");
        }
        Object h = addedHandle;
        if (h == null || !Lt.bool(Lt.call(h, "isValid"))) throw new IOException("The torrent engine did not accept this torrent. Is it already downloading?");
        return h;
    }

    /** Stops it and lets go (no seeding); files already on disk stay. */
    static void remove(Object handle) {
        try {
            Lt.call(session(), "remove", handle);
        } catch (IOException ignored) { }
    }

    /** Called every tick: stops the session once nothing has used it for a while, so it isn't running for nothing. */
    static synchronized void idle(boolean busy) {
        if (session == null) return;
        long now = System.currentTimeMillis();
        if (busy) { lastUse = now; return; }
        if (now - lastUse < IDLE_STOP_MS) return;
        try { Lt.call(session, "stop"); } catch (IOException ignored) { }
        session = null;
    }
}
