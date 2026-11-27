package app.plusnet.engine;

import java.io.File;
import java.io.IOException;

/** Runs Torrents/Lt (which reach the torrent library by name) against a stand-in with the library's shape. Run with `java -ea`. */
public class TorrentsCheck {
    public static void main(String[] a) throws Exception {
        assert Torrents.available() && Lt.present();

        // what a magnet link says it is called, and what counts as a magnet link
        assert Torrents.isMagnet("magnet:?xt=urn:btih:abc") && Torrents.isMagnet("MAGNET:?xt=x") && !Torrents.isMagnet("https://x/y.torrent");
        assert "Other Mommy 2026".equals(Torrents.magnetName("magnet:?xt=urn:btih:abc&dn=Other+Mommy+2026&tr=udp%3A%2F%2Ft"));
        assert "A&B".equals(Torrents.magnetName("magnet:?dn=A%26B"));
        assert Torrents.magnetName("magnet:?xt=urn:btih:abc") == null && Torrents.magnetName("magnet:nothing") == null;

        // a magnet link is looked up: the Integer timeout reaches an `int` parameter, null comes back when no one answers
        byte[] meta = Torrents.fetchMagnet("magnet:?xt=urn:btih:abc", 120, new File("."));
        assert meta != null && meta.length == 3 && org.libtorrent4j.SessionManager.lastTimeout == 120;
        assert Torrents.fetchMagnet("https://not-a-magnet", 5, new File(".")) == null;

        // the torrent's contents are read through a static method
        Object ti = Torrents.info(meta);
        assert "Test Torrent".equals(Lt.call(ti, "name")) && Lt.num(Lt.call(ti, "totalSize")) == 12345 && Lt.num(Lt.call(ti, "numFiles")) == 2;

        // adding: the listener proxy receives the library's alerts, picks out the "torrent added" one, and hands over the handle
        Object h = Torrents.add(ti, new File("."));
        assert h instanceof org.libtorrent4j.TorrentHandle && Lt.bool(Lt.call(h, "isValid"));
        assert org.libtorrent4j.SessionManager.lastPriorities.length == 2 && org.libtorrent4j.SessionManager.lastPriorities[0] == org.libtorrent4j.Priority.NORMAL;

        // what the download loop reads
        Object st = Lt.call(h, "status");
        assert Lt.num(Lt.call(st, "totalDone")) == 500 && Lt.num(Lt.call(st, "downloadRate")) == 100
            && Lt.num(Lt.call(st, "numPeers")) == 7 && !Lt.bool(Lt.call(st, "isFinished"));
        Lt.call(h, "pause");
        assert ((org.libtorrent4j.TorrentHandle) h).paused;
        Lt.call(h, "resume");
        assert !((org.libtorrent4j.TorrentHandle) h).paused;

        Torrents.remove(h);
        assert org.libtorrent4j.SessionManager.removed == 1;
        assert org.libtorrent4j.SessionManager.started == 1 : "one shared session expected, started " + org.libtorrent4j.SessionManager.started;

        // the session is stopped once idle, and a busy one is left alone
        Torrents.idle(true);
        assert org.libtorrent4j.SessionManager.stopped == 0;

        // a method the library does not have is reported by name, so it can be found and fixed from the message
        try {
            Lt.call(h, "noSuchMethod");
            assert false;
        } catch (IOException e) {
            assert e.getMessage().contains("TorrentHandle.noSuchMethod") : e.getMessage();
        }
        System.out.println("torrents ok");
    }
}
