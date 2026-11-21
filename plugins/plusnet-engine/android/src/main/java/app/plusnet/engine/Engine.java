package app.plusnet.engine;

import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.PowerManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Every download, the networks they share, the queue, and the notification that keeps them alive. */
final class Engine {
    interface Sink {
        void update(JSONObject snap);
        void removed(String id);
        void networks(JSONArray networks);
    }

    private static final int MAX_ACTIVE = 3; // the rest wait in the queue
    private static Engine instance;

    static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    final Context ctx;
    final Nets nets;
    final ExecutorService pool = Executors.newCachedThreadPool();
    volatile Sink sink;

    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Limiter> limiters = new ConcurrentHashMap<>();
    private final SharedPreferences prefs;
    private final File historyFile;
    private final PowerManager.WakeLock wake;
    private long lastTick = System.nanoTime();
    private boolean visible = true, serviceUp;
    private volatile int active;

    private Engine(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("plusnet", Context.MODE_PRIVATE);
        historyFile = new File(ctx.getFilesDir(), "history.json");
        wake = ctx.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "plusnet:downloads");
        for (String id : Nets.IDS) {
            Limiter l = new Limiter();
            l.bytesPerSec = prefs.getLong("limit." + id, 0);
            limiters.put(id, l);
        }
        nets = new Nets(ctx, this::netsChanged);
        loadHistory();
        nets.request();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(() -> {
            try { tick(); } catch (RuntimeException ignored) { } // one bad tick must not end the schedule
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    // ---- what the UI asks for ----

    Limiter limiter(String netId) { return limiters.get(netId); }

    void setLimit(String netId, long bytesPerSec) {
        Limiter l = limiters.get(netId);
        if (l == null) return;
        l.bytesPerSec = Math.max(0, bytesPerSec);
        prefs.edit().putLong("limit." + netId, l.bytesPerSec).apply();
    }

    JSONObject limits() throws JSONException {
        JSONObject o = new JSONObject();
        for (String id : Nets.IDS) o.put(id, limiters.get(id).bytesPerSec);
        return o;
    }

    JSONArray networks() {
        // Retry any transport request that previously failed and return the latest native snapshot.
        nets.request();
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < Nets.IDS.length; i++) {
                a.put(new JSONObject().put("id", Nets.IDS[i]).put("name", Nets.NAMES[i]).put("online", nets.online(Nets.IDS[i])));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return a;
    }

    JSONObject probe(String url) throws IOException {
        IOException last = null;
        boolean tried = false;
        for (String id : Nets.IDS) {
            if (!nets.online(id)) continue;
            tried = true;
            try {
                return Probe.run(nets, id, url).json();
            } catch (IOException e) {
                // Try another connected transport; the default network may not be the only route.
                last = e;
            }
        }
        if (!tried) return Probe.run(nets, null, url).json();
        throw last == null ? new IOException("No usable network") : last;
    }

    String start(String url, String name, List<String> use) {
        Job j = new Job(this, UUID.randomUUID().toString(), url, name, use);
        jobs.put(j.id, j);
        changed(j);
        return j.id;
    }

    Job job(String id) { return jobs.get(id); }

    JSONArray list() {
        JSONArray a = new JSONArray();
        for (Job j : snapshot()) a.put(j.snap());
        return a;
    }

    void remove(String id, boolean deleteFile) {
        Job j = jobs.remove(id);
        if (j == null) return;
        j.discard(deleteFile);
        saveHistory();
        Sink s = sink;
        if (s != null) s.removed(id);
        schedule();
    }

    void open(String id) throws IOException {
        Job j = jobs.get(id);
        if (j == null || j.uri == null) throw new IOException("The file is gone");
        Intent view = new Intent(Intent.ACTION_VIEW)
            .setDataAndType(j.uri, Job.mime(j.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(view);
        } catch (ActivityNotFoundException e) {
            throw new IOException("No app on this phone can open that file");
        }
    }

    /** The plugin's host screen is in front (or not): off-screen and idle, the radios are let go. */
    void visible(boolean v) {
        visible = v;
        hold();
    }

    // ---- called by jobs ----

    void emit(Job j) {
        Sink s = sink;
        if (s != null) s.update(j.snap());
    }

    void changed(Job j) {
        emit(j);
        schedule();
    }

    private void netsChanged() {
        Sink s = sink;
        if (s != null) s.networks(networks());
    }

    // ---- queue, ticker, notification ----

    private List<Job> snapshot() {
        synchronized (jobs) { return new ArrayList<>(jobs.values()); }
    }

    private synchronized void schedule() {
        List<Job> all = snapshot();
        int running = 0;
        for (Job j : all) if (j.status.equals("downloading")) running++;
        for (Job j : all) {
            if (running < MAX_ACTIVE && j.status.equals("queued")) {
                j.begin();
                running++;
                emit(j);
            }
        }
        active = running;
        hold();
    }

    private void hold() {
        if (visible || active > 0) nets.request(); else nets.release();
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastTick) / 1e9;
        lastTick = now;
        int running = 0;
        long speed = 0;
        for (Job j : snapshot()) {
            if (!j.status.equals("downloading")) continue;
            j.sample(dt);
            emit(j);
            running++;
            speed += j.speed();
        }
        notifyService(running, speed);
    }

    private synchronized void notifyService(int running, long speed) {
        if (running > 0) {
            if (!serviceUp) {
                try {
                    ctx.startForegroundService(new Intent(ctx, DownloadService.class));
                    serviceUp = true;
                } catch (RuntimeException e) {
                    return; // started from the background and not allowed to: downloads run while the app is open
                }
                wake.acquire(6 * 60 * 60 * 1000L); // timed, so a crash can't leave it held
            }
            String text = running + (running == 1 ? " download" : " downloads") + String.format(Locale.US, " · %.1f MB/s", speed / 1e6);
            ctx.getSystemService(NotificationManager.class).notify(DownloadService.ID, DownloadService.build(ctx, text));
        } else if (serviceUp) {
            ctx.stopService(new Intent(ctx, DownloadService.class));
            serviceUp = false;
            if (wake.isHeld()) wake.release();
        }
    }

    // ---- finished downloads survive a restart; ones in progress start over ----

    synchronized void saveHistory() {
        try {
            JSONArray a = new JSONArray();
            for (Job j : snapshot()) if (j.status.equals("completed") && j.uri != null) a.put(j.toHistory());
            try (FileOutputStream out = new FileOutputStream(historyFile)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (JSONException | IOException ignored) { }
    }

    private void loadHistory() {
        if (!historyFile.exists()) return;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(historyFile.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                Job j = Job.restore(this, a.getJSONObject(i));
                if (stillThere(j)) jobs.put(j.id, j);
            }
        } catch (JSONException | IOException ignored) { }
    }

    /** The user may have deleted the file from the Files app. */
    private boolean stillThere(Job j) {
        try (Cursor c = ctx.getContentResolver().query(j.uri, null, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
