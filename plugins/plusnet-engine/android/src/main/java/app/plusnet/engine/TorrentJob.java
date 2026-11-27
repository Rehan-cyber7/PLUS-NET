package app.plusnet.engine;

import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A torrent (a magnet link, or a link to a .torrent file). It downloads into the app's own storage,
 * then moves the files to where the user keeps downloads and stops: it does not keep seeding, so it
 * does not keep using the connection or the data plan afterwards.
 *
 * Torrents use the phone's main connection. Peers are reached by the torrent library itself, which
 * cannot be told to send a connection over one particular network the way HTTP downloads can.
 */
final class TorrentJob extends Download {
    private final String source; // "magnet:…" or "https://…/file.torrent"
    private byte[] metadata;     // the .torrent file's contents, once known
    private volatile long total = -1;
    private volatile long done;
    private volatile long rate;
    private volatile long peers;
    private volatile String note;
    private volatile Object handle; // the library's handle for the running torrent
    private volatile boolean stop;
    private volatile boolean removed;
    private volatile Thread thread;
    private File dir;
    private final List<Uri> outputs = new ArrayList<>(); // the finished files, where the user keeps downloads

    TorrentJob(Engine eng, String id, String source, String name) {
        super(eng, id);
        this.source = source;
        String n = name != null && !name.trim().isEmpty() ? name : Torrents.magnetName(source);
        this.fileName = Probe.clean(n == null ? "Torrent" : n);
    }

    // ---- state for the UI ----

    @Override long speed() { return status.equals("downloading") ? rate : 0; }

    @Override void sample(double dt) { /* the library reports its own rate */ }

    @Override void setNetwork(String netId, boolean on) { /* torrents use the main connection */ }

    @Override JSONObject snap() {
        try {
            boolean completed = status.equals("completed");
            return new JSONObject().put("id", id).put("url", source).put("fileName", fileName).put("status", status)
                .put("total", total).put("done", completed ? Math.max(total, done) : done).put("speed", speed())
                .put("ranges", true).put("error", error == null ? JSONObject.NULL : error).put("cells", "")
                .put("createdAt", createdAt).put("finishedAt", finishedAt).put("nets", new JSONArray())
                .put("kind", "torrent").put("peers", peers).put("note", note == null ? JSONObject.NULL : note);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override JSONObject toHistory() throws JSONException {
        JSONArray files = new JSONArray();
        for (Uri u : outputs) files.put(u.toString());
        JSONObject o = new JSONObject().put("kind", "torrent").put("id", id).put("url", source).put("fileName", fileName)
            .put("total", total).put("createdAt", createdAt).put("finishedAt", finishedAt).put("files", files);
        if (uri != null) o.put("uri", uri.toString());
        return o;
    }

    static TorrentJob restore(Engine eng, JSONObject o) throws JSONException {
        TorrentJob j = new TorrentJob(eng, o.getString("id"), o.getString("url"), o.getString("fileName"));
        j.status = "completed";
        j.total = o.getLong("total");
        j.done = j.total;
        j.createdAt = o.getLong("createdAt");
        j.finishedAt = o.getLong("finishedAt");
        JSONArray files = o.optJSONArray("files");
        if (files != null) for (int i = 0; i < files.length(); i++) j.outputs.add(Uri.parse(files.getString(i)));
        if (o.has("uri")) j.uri = Uri.parse(o.getString("uri"));
        return j;
    }

    // ---- commands ----

    @Override void pause() {
        String s = status;
        if (s.equals("downloading")) {
            stop = true;
            Object h = handle;
            if (h != null) {
                try { Lt.call(h, "pause"); } catch (IOException ignored) { }
            }
        } else if (!s.equals("queued")) {
            return;
        }
        status = "paused";
        eng.changed(this);
    }

    @Override void resume() {
        if (!status.equals("paused") && !status.equals("error")) return;
        error = null;
        status = "queued";
        eng.changed(this);
    }

    @Override void discard(boolean deleteFile) {
        removed = true;
        stop = true;
        Thread t = thread;
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) { }
        }
        cleanup();
        if (!status.equals("completed") || deleteFile) for (Uri u : outputs) eng.dest().delete(u);
    }

    private void cleanup() {
        Object h = handle;
        if (h != null) {
            Torrents.remove(h);
            handle = null;
        }
        if (dir != null) deleteTree(dir);
    }

    @Override void begin() {
        final Thread prev = thread;
        stop = false;
        status = "downloading";
        error = null;
        Thread t = new Thread(() -> run(prev), "plusnet-torrent-" + id);
        thread = t;
        t.start();
    }

    // ---- the run ----

    private void run(Thread prev) {
        String failure = null;
        boolean saved = false;
        try {
            if (prev != null) prev.join();
            eng.emit(this);
            if (handle == null) start(); else Lt.call(handle, "resume");
            note = null;
            boolean finished = false;
            while (!stop && !removed) {
                Object st = Lt.call(handle, "status");
                done = Lt.num(Lt.call(st, "totalDone"));
                rate = Lt.num(Lt.call(st, "downloadRate"));
                peers = Lt.num(Lt.call(st, "numPeers"));
                if (Lt.bool(Lt.call(st, "isFinished")) || (total > 0 && done >= total)) { finished = true; break; }
                Thread.sleep(500);
            }
            if (finished && !removed) {
                save();
                saved = true;
            }
        } catch (InterruptedException e) {
            // stopped
        } catch (Exception e) {
            if (!removed) failure = Probe.message(e);
        } finally {
            finish(saved, failure);
        }
    }

    /** Finds the torrent (a magnet link has to be looked up on the network first) and starts it. */
    private void start() throws IOException {
        File base = new File(eng.ctx.getExternalFilesDir(null), "torrents");
        dir = new File(base, id);
        dir.mkdirs();
        if (metadata == null) {
            if (Torrents.isMagnet(source)) {
                note = "Looking for this torrent on the network…";
                eng.emit(this);
                File meta = new File(base, "meta");
                meta.mkdirs();
                metadata = Torrents.fetchMagnet(source, 120, meta);
            } else {
                note = "Fetching the torrent file…";
                eng.emit(this);
                metadata = eng.fetchTorrentFile(source);
            }
            if (metadata == null) throw new IOException("Couldn't find this torrent: no one answered in 2 minutes. It may have no one sharing it.");
        }
        Object ti = Torrents.info(metadata);
        fileName = Probe.clean(String.valueOf(Lt.call(ti, "name")));
        total = Lt.num(Lt.call(ti, "totalSize"));
        if (removed) return;
        handle = Torrents.add(ti, dir);
    }

    /** Moves the finished files to the user's download folder, keeping the torrent's folder structure. */
    private void save() throws IOException {
        Object h = handle;
        if (h != null) { // stop sharing before the files are moved
            Torrents.remove(h);
            handle = null;
        }
        List<File> files = new ArrayList<>();
        collect(dir, files);
        String root = dir.getAbsolutePath() + File.separator;
        int n = 0;
        for (File f : files) {
            if (removed) return;
            n++;
            note = "Saving file " + n + " of " + files.size() + " to " + eng.dest().label() + "…";
            String rel = f.getAbsolutePath().substring(root.length()).replace(File.separatorChar, '/');
            int slash = rel.lastIndexOf('/');
            StringBuilder sub = new StringBuilder();
            if (slash >= 0) {
                for (String part : rel.substring(0, slash).split("/")) {
                    if (part.isEmpty()) continue;
                    if (sub.length() > 0) sub.append('/');
                    sub.append(Probe.clean(part));
                }
            }
            String name = Probe.clean(rel.substring(slash + 1));
            Uri u = eng.dest().create(sub.toString(), name, Job.mime(name));
            try (InputStream in = new FileInputStream(f); OutputStream out = eng.ctx.getContentResolver().openOutputStream(u, "w")) {
                if (out == null) throw new IOException("Couldn't open " + name);
                byte[] buf = new byte[256 * 1024];
                int r;
                while ((r = in.read(buf)) >= 0) out.write(buf, 0, r);
            }
            eng.dest().seal(u);
            outputs.add(u);
            f.delete(); // frees the space as it goes: the files exist twice only while one is being moved
        }
        deleteTree(dir);
        if (outputs.size() == 1) uri = outputs.get(0);
    }

    private void finish(boolean saved, String failure) {
        if (removed) {
            cleanup();
            return;
        }
        note = null;
        rate = 0;
        peers = 0;
        if (saved && failure == null) {
            done = total;
            finishedAt = System.currentTimeMillis();
            status = "completed";
            eng.saveHistory();
        } else if (failure != null) {
            error = failure;
            status = "error";
        } else {
            status = "paused";
        }
        eng.changed(this);
    }

    // ---- files in the app's own storage ----

    private static void collect(File d, List<File> out) {
        File[] list = d.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else if (!f.getName().startsWith(".") && !f.getName().endsWith(".parts")) out.add(f); // the library's bookkeeping files
        }
    }

    private static void deleteTree(File d) {
        File[] list = d.listFiles();
        if (list != null) for (File f : list) deleteTree(f);
        d.delete();
    }
}
