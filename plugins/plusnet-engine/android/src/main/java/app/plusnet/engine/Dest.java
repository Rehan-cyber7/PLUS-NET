package app.plusnet.engine;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;

/**
 * Where finished files go: the Downloads/PlusNet folder by default, or any folder the user picked
 * (Android's folder picker gives the app lasting access to just that folder).
 */
final class Dest {
    private static final String KEY = "dest.tree";

    private final Context ctx;
    private final SharedPreferences prefs;

    Dest(Context ctx, SharedPreferences prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    /** The folder the user picked, or null for the default. */
    Uri tree() {
        String s = prefs.getString(KEY, null);
        return s == null ? null : Uri.parse(s);
    }

    boolean custom() { return tree() != null; }

    void choose(Uri tree) { prefs.edit().putString(KEY, tree.toString()).apply(); }

    void reset() { prefs.edit().remove(KEY).apply(); }

    /** A name to show for the current folder. */
    String label() {
        Uri t = tree();
        if (t == null) return "Downloads/PlusNet";
        try (Cursor c = ctx.getContentResolver().query(
            DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)),
            new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String id = DocumentsContract.getTreeDocumentId(t); // "primary:Movies"
        int colon = id.indexOf(':');
        return colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
    }

    /**
     * An empty file ready to be written, in `subdir` (may be empty, or "A/B") under the destination.
     * In Downloads it is hidden from other apps until seal().
     */
    Uri create(String subdir, String name, String mime) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri tree = tree();
        if (tree == null) {
            if (Build.VERSION.SDK_INT < 29) throw new IOException("PlusNet needs Android 10 or newer");
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/PlusNet" + (subdir.isEmpty() ? "" : "/" + subdir));
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("Couldn't create the file");
            return u;
        }
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            if (!subdir.isEmpty()) for (String part : subdir.split("/")) if (!part.isEmpty()) parent = dir(cr, tree, parent, part);
            Uri u = DocumentsContract.createDocument(cr, parent, mime, name);
            if (u == null) throw new IOException("Couldn't create the file");
            return u;
        } catch (IOException | RuntimeException e) { // permission gone, folder deleted, storage removed
            throw new IOException("PlusNet can't write to \"" + label() + "\" any more. Choose the folder again in Settings.");
        }
    }

    /** The finished file becomes visible to other apps (Downloads only; a picked folder shows it at once). */
    void seal(Uri u) {
        if (!"media".equals(u.getAuthority())) return;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(u, v, null, null);
    }

    void delete(Uri u) {
        try {
            if (DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(ctx.getContentResolver(), u);
            else ctx.getContentResolver().delete(u, null, null);
        } catch (Exception ignored) { }
    }

    /** The sub-folder called `name` inside `parent`, made if it isn't there. */
    private Uri dir(ContentResolver cr, Uri tree, Uri parent, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor c = cr.query(children, new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                }
            }
        }
        Uri made = DocumentsContract.createDocument(cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (made == null) throw new IOException("no folder");
        return made;
    }
}
