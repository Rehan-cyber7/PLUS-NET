package org.libtorrent4j;
import java.io.File; import java.util.List;
/** A stand-in with the shape of libtorrent4j's SessionManager, to exercise the code that reaches the real one by name. */
public class SessionManager {
    public static volatile int started, stopped, removed; public static volatile Priority[] lastPriorities; public static volatile int lastTimeout;
    private AlertListener listener;
    public void addListener(AlertListener l) { listener = l; }
    public void start() { started++; }
    public void stop() { stopped++; }
    public byte[] fetchMagnet(String uri, int timeout, File dir) { lastTimeout = timeout; return uri.startsWith("magnet:") ? new byte[] {1, 2, 3} : null; }
    public void download(TorrentInfo ti, File saveDir, File resume, Priority[] p, List<Object> peers, org.libtorrent4j.swig.torrent_flags_t flags) {
        lastPriorities = p;
        final TorrentHandle h = new TorrentHandle();
        new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException e) { }
            listener.alert(new org.libtorrent4j.alerts.StateUpdateAlert());      // unrelated alerts come first
            listener.alert(new org.libtorrent4j.alerts.AddTorrentAlert(h)); }).start();
    }
    public void remove(TorrentHandle h) { removed++; }
    public void remove(TorrentHandle h, int flags) { }
}
