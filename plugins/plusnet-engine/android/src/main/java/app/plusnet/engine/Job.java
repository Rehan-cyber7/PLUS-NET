package app.plusnet.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.webkit.MimeTypeMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One download. A run (start, or resume after a pause) polls once in a while for networks that
 * have come or gone, and keeps a few streams open on each usable one. Every stream takes the next
 * waiting chunk, so a faster network ends up doing more of the file.
 */
final class Job {
    private static final int STREAMS = 4; // per network
    private static final int CELLS = 120;
    private static final int MAX_FAILS = 6;

    /** One network as this download sees it. `idx` is its place in Nets.IDS, which the cell map uses. */
    static final class Stat {
        final String id;
        final int idx;
        volatile boolean enabled;
        volatile boolean failed;
        volatile String error;
        volatile long speed;
        long last;
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger streams = new AtomicInteger();
        final AtomicInteger fails = new AtomicInteger();

        Stat(String id, int idx) { this.id = id; this.idx = idx; }

        JSONObject json(boolean online, boolean down) throws JSONException {
            String state = !enabled ? "off" : failed ? "failed" : online ? "on" : "offline";
            return new JSONObject().put("id", id).put("enabled", enabled).put("state", state)
                .put("bytes", bytes.get()).put("speed", down ? speed : 0)
                .put("error", error == null ? JSONObject.NULL : error);
        }
    }

    private static final class Run {
        volatile boolean stop;
        final Set<HttpURLConnection> conns = ConcurrentHashMap.newKeySet();
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger live = new AtomicInteger();
        Thread main;

        void stop() {
            stop = true;
            for (HttpURLConnection c : conns) c.disconnect(); // unblocks reads that are waiting
        }

        void joinWorkers() {
            synchronized (threads) {
                for (Thread t : threads) {
                    try { t.join(); } catch (InterruptedException ignored) { }
                }
            }
        }
    }

    final Engine eng;
    final String id;
    final String url;
    volatile String status = "queued"; // queued | downloading | paused | completed | error
    volatile String error;
    volatile String fileName;
    volatile String finalUrl;
    volatile long total = -1;
    volatile boolean ranges;
    long createdAt = System.currentTimeMillis();
    long finishedAt;
    Uri uri;
    final List<Stat> nets = new ArrayList<>();

    private volatile ChunkMap map; // null while unprobed, and when the server can't do ranges
    private boolean sized;
    private long finalBytes;
    private ParcelFileDescriptor pfd;
    private FileChannel ch;
    private final AtomicLong plain = new AtomicLong(); // bytes of a download that isn't split
    private volatile boolean singleDone;
    private volatile boolean removed;
    private volatile Run run;

    Job(Engine eng, String id, String url, String name, List<String> use) {
        this.eng = eng;
        this.id = id;
        this.url = url;
        this.fileName = name == null || name.trim().isEmpty() ? null : Probe.clean(name);
        for (int i = 0; i < Nets.IDS.length; i++) {
            Stat s = new Stat(Nets.IDS[i], i);
            s.enabled = use.contains(s.id);
            nets.add(s);
        }
    }

    // ---- state for the UI ----

    long done() {
        if (status.equals("completed")) return finalBytes;
        return map != null ? map.bytes() : plain.get();
    }

    long speed() {
        long sum = 0;
        if (status.equals("downloading")) for (Stat s : nets) sum += s.speed;
        return sum;
    }

    void sample(double dt) {
        for (Stat s : nets) {
            long b = s.bytes.get();
            s.speed = (long) (s.speed * 0.6 + (b - s.last) / dt * 0.4);
            s.last = b;
        }
    }

    JSONObject snap() {
        try {
            boolean down = status.equals("downloading");
            JSONArray a = new JSONArray();
            for (Stat s : nets) {
                boolean on = eng.nets.online(s.id);
                if (s.enabled || on || s.bytes.get() > 0) a.put(s.json(on, down));
            }
            return new JSONObject().put("id", id).put("url", url).put("fileName", fileName == null ? "…" : fileName)
                .put("status", status).put("total", total).put("done", done()).put("speed", speed())
                .put("ranges", ranges || finalUrl == null) // unknown until probed: don't warn yet
                .put("error", error == null ? JSONObject.NULL : error)
                .put("cells", map == null ? "" : map.cells(CELLS))
                .put("createdAt", createdAt).put("finishedAt", finishedAt).put("nets", a);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    JSONObject toHistory() throws JSONException {
        JSONObject by = new JSONObject();
        for (Stat s : nets) by.put(s.id, s.bytes.get());
        return new JSONObject().put("id", id).put("url", url).put("fileName", fileName).put("uri", uri.toString())
            .put("total", total).put("done", finalBytes).put("createdAt", createdAt).put("finishedAt", finishedAt).put("bytes", by);
    }

    static Job restore(Engine eng, JSONObject o) throws JSONException {
        Job j = new Job(eng, o.getString("id"), o.getString("url"), o.getString("fileName"), Collections.<String>emptyList());
        j.uri = Uri.parse(o.getString("uri"));
        j.total = o.getLong("total");
        j.finalBytes = o.getLong("done");
        j.createdAt = o.getLong("createdAt");
        j.finishedAt = o.getLong("finishedAt");
        j.status = "completed";
        j.ranges = true;
        JSONObject by = o.optJSONObject("bytes");
        if (by != null) for (Stat s : j.nets) s.bytes.set(by.optLong(s.id, 0));
        return j;
    }

    // ---- commands ----

    void pause() {
        if (status.equals("downloading")) {
            Run r = run;
            if (r != null) r.stop();
        } else if (!status.equals("queued")) {
            return;
        }
        status = "paused";
        eng.changed(this);
    }

    void resume() {
        if (!status.equals("paused") && !status.equals("error")) return;
        for (Stat s : nets) { s.failed = false; s.error = null; s.fails.set(0); }
        error = null;
        status = "queued";
        eng.changed(this);
    }

    void setNetwork(String netId, boolean on) {
        for (Stat s : nets) {
            if (!s.id.equals(netId)) continue;
            s.enabled = on;
            s.failed = false; // switching it off and on is how a refused network is tried again
            s.error = null;
            s.fails.set(0);
        }
        eng.emit(this);
    }

    /** Stops the run and drops the file, unless it is a finished one the user wants to keep. */
    void discard(boolean deleteFile) {
        removed = true;
        Run r = run;
        if (r != null) {
            r.stop();
            try { if (r.main != null) r.main.join(3000); } catch (InterruptedException ignored) { }
        }
        if (uri != null && (!status.equals("completed") || deleteFile)) {
            try { eng.ctx.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) { }
        }
    }

    /** Called by the engine when a slot is free. */
    void begin() {
        final Run prev = run;
        final Run r = new Run();
        run = r;
        status = "downloading";
        error = null;
        r.main = new Thread(() -> execute(r, prev), "plusnet-" + id);
        r.main.start();
    }

    // ---- the run ----

    private void execute(Run r, Run prev) {
        String failure = null;
        try {
            if (prev != null && prev.main != null) prev.main.join(); // a paused run still winding down
            eng.emit(this);
            if (finalUrl == null) probe();
            openFile();
            while (!r.stop && !complete()) {
                spawn(r);
                if (r.live.get() == 0 && anyEnabled() && allFailed()) throw new IOException(lastError());
                Thread.sleep(300);
            }
        } catch (InterruptedException e) {
            // stopped
        } catch (Exception e) {
            if (!r.stop) failure = Probe.message(e);
        } finally {
            r.stop();
            r.joinWorkers();
            closeFile();
            finish(r, failure);
        }
    }

    private void probe() throws IOException {
        Probe firstPlain = null;
        Probe firstRange = null;
        IOException last = null;
        List<Integer> rangeNetworks = new ArrayList<>();
        int attempted = 0;

        // Probe each selected transport independently from the ORIGINAL URL. Many download hosts
        // redirect to short-lived or source-IP-bound CDN URLs. Reusing the Wi-Fi redirect URL for
        // cellular makes the CDN reject the cellular request even though the original link works.
        for (Stat s : nets) {
            if (!s.enabled || !eng.nets.online(s.id)) continue;
            attempted++;
            try {
                Probe p = Probe.run(eng.nets, s.id, url);
                s.error = null;
                s.failed = false;
                s.fails.set(0);

                if (p.ranges && p.total >= 0) {
                    if (firstRange == null) firstRange = p;
                    if (p.total == firstRange.total) {
                        rangeNetworks.add(s.idx);
                    } else {
                        s.failed = true;
                        s.fails.set(MAX_FAILS);
                        s.error = "This network returned a different file size; it cannot safely share this download";
                    }
                } else if (firstPlain == null) {
                    firstPlain = p;
                }
            } catch (IOException e) {
                // A route can be reported as connected while its DNS/Internet path or download
                // host denies it. Keep the error attached to that transport and try the others.
                s.error = Probe.message(e);
                s.failed = true;
                s.fails.set(MAX_FAILS);
                last = e;
            }
        }

        if (firstRange != null) {
            // Only transports that independently returned valid byte ranges for the same length
            // may participate in a split download. A 200 response or a 403 route is not eligible.
            for (Stat s : nets) {
                if (s.enabled && !rangeNetworks.contains(s.idx) && !s.failed) {
                    s.failed = true;
                    s.fails.set(MAX_FAILS);
                    s.error = "This network does not support byte-range downloads for this link";
                }
            }
            finalUrl = firstRange.finalUrl; // retained for link metadata; workers use url below
            total = firstRange.total;
            ranges = true;
            if (fileName == null) fileName = firstRange.fileName;
            int[] activeNetworks = rangeNetworks.stream().mapToInt(Integer::intValue).toArray();
            map = new ChunkMap(total, activeNetworks);
            return;
        }

        if (firstPlain != null) {
            // If the host does not support ranges at all, retain the normal single-stream path.
            finalUrl = firstPlain.finalUrl;
            total = firstPlain.total;
            ranges = false;
            if (fileName == null) fileName = firstPlain.fileName;
            map = null;
            return;
        }

        if (last != null) throw last;
        throw new IOException(attempted == 0
            ? "None of the selected networks is currently connected to the internet"
            : "None of the selected networks can access this download link");
    }

    private boolean complete() { return ranges ? map.complete() : singleDone; }

    private boolean usable(Stat s) { return s.enabled && !s.failed && eng.nets.online(s.id); }

    private boolean anyEnabled() {
        for (Stat s : nets) if (s.enabled) return true;
        return false;
    }

    private boolean allFailed() {
        for (Stat s : nets) if (s.enabled && !s.failed) return false;
        return true;
    }

    private String lastError() {
        for (Stat s : nets) if (s.enabled && s.error != null) return s.error;
        return "Every network failed";
    }

    private void spawn(Run r) {
        if (!ranges) { // one stream, on the first network that works
            if (r.live.get() == 0 && !singleDone) {
                for (Stat s : nets) if (usable(s)) { launch(r, s, true); break; }
            }
            return;
        }
        if (!map.hasWaiting()) return;
        for (Stat s : nets) {
            if (usable(s) && s.streams.get() == 0) for (int k = 0; k < STREAMS; k++) launch(r, s, false);
        }
    }

    private void launch(Run r, Stat s, boolean single) {
        r.live.incrementAndGet();
        s.streams.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                if (single) single(r, s); else work(r, s);
            } finally {
                s.streams.decrementAndGet();
                r.live.decrementAndGet();
            }
        }, "plusnet-" + id + "-" + s.id);
        r.threads.add(t);
        t.start();
    }

    private void work(Run r, Stat s) {
        try {
            while (!r.stop && s.enabled) {
                boolean[] usableNetworks = new boolean[Nets.IDS.length];
                for (Stat candidate : nets) usableNetworks[candidate.idx] = usable(candidate);
                int c = map.claim(s.idx, usableNetworks);
                if (c < 0) return;
                try {
                    fetch(r, s, c);
                    map.finish(c, s.idx);
                    s.fails.set(0);
                } catch (Exception e) {
                    map.release(c); // what was written stays: the next try starts after it
                    if (r.stop || !s.enabled || !eng.nets.online(s.id)) return; // spawn() restarts it when it's back
                    s.error = Probe.message(e);
                    if (s.fails.incrementAndGet() >= MAX_FAILS) { s.failed = true; return; }
                    Thread.sleep(Math.min(8000, 500L << s.fails.get()));
                }
            }
        } catch (InterruptedException ignored) { }
    }

    private void fetch(Run r, Stat s, int c) throws IOException, InterruptedException {
        long from = map.from(c), to = map.to(c);
        if (from > to) return; // all of it was written already
        Nets.Resp resp = eng.nets.open(s.id, url, "bytes=" + from + "-" + to);
        r.conns.add(resp.conn);
        try {
            if (resp.code != 206) {
                throw new IOException(resp.code == 200 ? "The server stopped allowing range requests" : Probe.describe(resp.code));
            }
            try (InputStream in = resp.conn.getInputStream()) {
                copy(r, s, in, from, to - from + 1, c);
            }
        } finally {
            r.conns.remove(resp.conn);
            resp.conn.disconnect();
        }
    }

    private void single(Run r, Stat s) {
        try {
            plain.set(0); // no ranges: a retry starts the file again
            Nets.Resp resp = eng.nets.open(s.id, url, null);
            r.conns.add(resp.conn);
            try {
                if (resp.code != 200) throw new IOException(Probe.describe(resp.code));
                try (InputStream in = resp.conn.getInputStream()) {
                    copy(r, s, in, 0, -1, -1);
                }
                if (total >= 0 && plain.get() != total) throw new IOException("The connection closed early");
                singleDone = true;
            } finally {
                r.conns.remove(resp.conn);
                resp.conn.disconnect();
            }
        } catch (Exception e) {
            if (r.stop || !s.enabled) return;
            s.error = Probe.message(e);
            if (s.fails.incrementAndGet() >= MAX_FAILS) s.failed = true;
        }
    }

    /** Streams `len` bytes (all of them when len < 0) to the file at `pos`, under the network's speed cap. */
    private void copy(Run r, Stat s, InputStream in, long pos, long len, int chunk) throws IOException, InterruptedException {
        final FileChannel out = ch;
        final Limiter lim = eng.limiter(s.id);
        byte[] buf = new byte[32 * 1024];
        long left = len < 0 ? Long.MAX_VALUE : len;
        while (left > 0) {
            if (r.stop || !s.enabled) throw new IOException("Stopped");
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                if (len < 0) return;
                throw new IOException("The connection closed early");
            }
            long wait = lim.reserve(n);
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            long at = pos;
            while (bb.hasRemaining()) at += out.write(bb, at);
            pos += n;
            left -= n;
            if (chunk >= 0) map.add(chunk, n); else plain.addAndGet(n);
            s.bytes.addAndGet(n);
        }
    }

    private void finish(Run r, String failure) {
        if (removed || run != r) return; // removed, or a newer run has taken over
        if (complete()) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                eng.ctx.getContentResolver().update(uri, v, null, null);
                finalBytes = map != null ? map.bytes() : plain.get();
                if (total < 0) total = finalBytes;
                finishedAt = System.currentTimeMillis();
                status = "completed";
            } catch (RuntimeException e) {
                error = Probe.message(e);
                status = "error";
            }
        } else if (failure != null) {
            error = failure;
            status = "error";
        } else {
            status = "paused";
        }
        for (Stat s : nets) s.speed = 0;
        if (status.equals("completed")) eng.saveHistory();
        eng.changed(this);
    }

    // ---- the file: a pending entry in the shared Downloads folder, written at any offset ----

    private void openFile() throws IOException {
        ContentResolver cr = eng.ctx.getContentResolver();
        if (uri == null) {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("PlusNet needs Android 10 or newer");
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime(fileName));
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PlusNet");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Couldn't create the file");
            String shown = displayName(cr, uri); // Android renames a clash: "name (1).zip"
            if (shown != null) fileName = shown;
        }
        pfd = cr.openFileDescriptor(uri, "rw"); // "rw" keeps what is there; "rwt" would empty it
        if (pfd == null) throw new IOException("Couldn't open the file");
        ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
        if (!sized && total > 0) {
            try {
                Os.ftruncate(pfd.getFileDescriptor(), total); // claims the space now: fails early if it isn't there
                sized = true;
            } catch (ErrnoException e) {
                throw new IOException("Not enough free space");
            }
        }
    }

    private void closeFile() {
        try {
            if (ch != null) ch.close();
            if (pfd != null) pfd.close();
        } catch (IOException ignored) { }
        ch = null;
        pfd = null;
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        String m = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
