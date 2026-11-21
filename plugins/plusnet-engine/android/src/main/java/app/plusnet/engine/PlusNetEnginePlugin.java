package app.plusnet.engine;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The JS side of the engine: thin on purpose, every decision is made in Engine and Job. */
@CapacitorPlugin(name = "PlusNetEngine")
public class PlusNetEnginePlugin extends Plugin {
    private Engine eng;

    @Override
    public void load() {
        eng = Engine.get(getContext());
        eng.sink = new Engine.Sink() {
            @Override public void update(JSONObject snap) { notifyListeners("update", js(snap)); }
            @Override public void removed(String id) {
                JSObject o = new JSObject();
                o.put("id", id);
                notifyListeners("removed", o);
            }
            @Override public void networks(JSONArray list) {
                JSObject o = new JSObject();
                o.put("networks", list);
                notifyListeners("networks", o);
            }
        };
        // Android 13+: without this the "downloading" notification is hidden (the downloads still run).
        if (Build.VERSION.SDK_INT >= 33
            && getContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && getActivity() != null) {
            getActivity().requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override protected void handleOnPause() { eng.visible(false); }

    @Override protected void handleOnResume() { eng.visible(true); }

    private static JSObject js(JSONObject o) {
        try {
            return new JSObject(o.toString());
        } catch (JSONException e) {
            return new JSObject();
        }
    }

    @PluginMethod
    public void networks(PluginCall call) {
        JSObject o = new JSObject();
        o.put("networks", eng.networks());
        call.resolve(o);
    }

    @PluginMethod
    public void probe(PluginCall call) {
        final String url = call.getString("url");
        if (url == null) { call.reject("Missing link"); return; }
        eng.pool.execute(() -> { // network work: off the plugin thread so other calls aren't held up
            try {
                call.resolve(js(eng.probe(url)));
            } catch (Exception e) {
                call.reject(Probe.message(e));
            }
        });
    }

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        if (url == null) { call.reject("Missing link"); return; }
        if (Build.VERSION.SDK_INT < 29) { call.reject("PlusNet needs Android 10 or newer"); return; }
        List<String> use = new ArrayList<>();
        JSArray a = call.getArray("networks");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                try { use.add(a.getString(i)); } catch (JSONException ignored) { }
            }
        }
        JSObject o = new JSObject();
        o.put("id", eng.start(url, call.getString("fileName"), use));
        call.resolve(o);
    }

    @PluginMethod
    public void pause(PluginCall call) {
        Job j = eng.job(call.getString("id", ""));
        if (j != null) j.pause();
        call.resolve();
    }

    @PluginMethod
    public void resume(PluginCall call) {
        Job j = eng.job(call.getString("id", ""));
        if (j != null) j.resume();
        call.resolve();
    }

    @PluginMethod
    public void remove(PluginCall call) {
        final String id = call.getString("id", "");
        final boolean deleteFile = Boolean.TRUE.equals(call.getBoolean("deleteFile", false));
        eng.pool.execute(() -> { // may wait for the download's threads to stop
            eng.remove(id, deleteFile);
            call.resolve();
        });
    }

    @PluginMethod
    public void setNetwork(PluginCall call) {
        Job j = eng.job(call.getString("id", ""));
        if (j != null) j.setNetwork(call.getString("network", ""), Boolean.TRUE.equals(call.getBoolean("enabled", false)));
        call.resolve();
    }

    @PluginMethod
    public void setLimit(PluginCall call) {
        eng.setLimit(call.getString("network", ""), (long) call.getDouble("bytesPerSec", 0.0).doubleValue());
        call.resolve();
    }

    @PluginMethod
    public void list(PluginCall call) {
        try {
            JSObject o = new JSObject();
            o.put("downloads", eng.list());
            o.put("limits", eng.limits());
            call.resolve(o);
        } catch (JSONException e) {
            call.reject(Probe.message(e));
        }
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        try {
            eng.open(call.getString("id", ""));
            call.resolve();
        } catch (Exception e) {
            call.reject(Probe.message(e));
        }
    }
}
