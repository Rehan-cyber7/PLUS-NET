package app.plusnet.engine;

import android.net.Uri;
import org.json.JSONException;
import org.json.JSONObject;

/** What the engine needs from anything in the downloads list: a normal download (Job) or a torrent (TorrentJob). */
abstract class Download {
    final Engine eng;
    final String id;
    volatile String status = "queued"; // queued | downloading | paused | completed | error
    volatile String error;
    volatile String fileName;
    long createdAt = System.currentTimeMillis();
    long finishedAt;
    Uri uri; // the finished file, when there is exactly one

    Download(Engine eng, String id) {
        this.eng = eng;
        this.id = id;
    }

    abstract void begin();
    abstract void pause();
    abstract void resume();
    abstract void setNetwork(String netId, boolean on);
    /** Stops it and drops what was downloaded, unless it is a finished one the user wants to keep. */
    abstract void discard(boolean deleteFile);
    abstract JSONObject snap();
    abstract long speed();
    abstract void sample(double dt);
    abstract JSONObject toHistory() throws JSONException;

    static Download restore(Engine eng, JSONObject o) throws JSONException {
        return "torrent".equals(o.optString("kind", "")) ? TorrentJob.restore(eng, o) : Job.restore(eng, o);
    }
}
