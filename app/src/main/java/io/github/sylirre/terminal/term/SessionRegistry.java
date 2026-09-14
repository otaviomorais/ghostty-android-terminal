/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of open tabs, persisted to app storage so a cold start after a
 * process death can rebuild them.
 *
 * What is saved is only each session's <em>shape</em>: an id (which also keys
 * its {@link ReplayLog} files), whether it was a userland or an Android
 * shell tab, and the initial tab label. The command, environment and engine
 * options are rebuilt from the current settings at restore time — a restored
 * tab is a new shell that looks like the old one, not the old shell itself
 * (which died with the process; keeping <em>it</em> alive would need a
 * detached supervisor holding the PTY, which is out of scope).
 *
 * The file is rewritten atomically (temp + rename), the same publish style
 * the rootfs installer uses. All callers are on the main thread. A registry
 * that fails to parse reads as empty — a stale file must never wedge launch.
 */
public final class SessionRegistry {

    /** One saved tab. {@code id} is stable for the tab's lifetime and keys
      * the replay files; ids are assigned by {@link SessionManager}. */
    public static final class Entry {
        public final int id;
        public final boolean userland;
        public final String label;

        Entry(int id, boolean userland, String label) {
            this.id = id;
            this.userland = userland;
            this.label = label;
        }
    }

    private static final String FILE_NAME = "session_registry.json";
    private static final int VERSION = 1;

    /** The saved tabs, in tab order. Empty when nothing was ever saved. */
    public static List<Entry> load(Context context) {
        List<Entry> out = new ArrayList<>();
        File f = file(context);
        if (!f.isFile()) return out;
        JSONObject root;
        try {
            root = new JSONObject(read(f));
        } catch (JSONException | IOException e) {
            return out; // unreadable registry == no registry
        }
        JSONArray entries = root.optJSONArray("entries");
        if (entries == null) return out;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null || e.optInt("id", 0) <= 0) continue;
            out.add(new Entry(e.optInt("id"),
                    e.optBoolean("userland", false),
                    e.optString("label", "sh")));
        }
        return out;
    }

    /** Records a newly opened tab. {@code id} must be one returned by
      * {@link #mintId}. */
    public static synchronized void add(Context context, int id, boolean userland,
            String label) {
        try {
            JSONObject root = readRoot(context);
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) entries = new JSONArray();
            entries.put(new JSONObject()
                    .put("id", id)
                    .put("userland", userland)
                    .put("label", label));
            root.put("entries", entries);
            writeRoot(context, root);
        } catch (JSONException | IOException e) {
            // A registry that cannot be written costs restore-after-crash for
            // this tab, nothing else; the session itself is already running.
        }
    }

    /** Mints an id for a tab about to open, persisting the new high-water
      * mark immediately so a crash right after this call cannot make the
      * next process hand the same id out again. */
    public static synchronized int mintId(Context context) {
        int id;
        try {
            JSONObject root = readRoot(context);
            id = Math.max(root.optInt("nextId", 1), 1);
            root.put("nextId", id + 1);
            root.put("version", VERSION);
            writeRoot(context, root);
        } catch (JSONException | IOException e) {
            id = (int) (System.nanoTime() & 0x7FFFFFFF); // never 0, never collide on disk
            if (id == 0) id = 1;
        }
        return id;
    }

    /** Drops one tab's entry (it closed or exited; its replay files are the
      * caller's business, see {@link ReplayLog#delete}). */
    public static synchronized void remove(Context context, int id) {
        try {
            JSONObject root = readRoot(context);
            JSONArray entries = root.optJSONArray("entries");
            if (entries == null) return;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                if (e != null && e.optInt("id", -1) != id) kept.put(e);
            }
            root.put("entries", kept);
            writeRoot(context, root);
        } catch (JSONException | IOException e) {
            // As in add(): losing bookkeeping never affects a running session.
        }
    }

    /** Forgets every saved tab (called once a restore has consumed them, or
      * after an unclean install reset). Does not touch replay files. */
    public static synchronized void clear(Context context) {
        file(context).delete();
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static JSONObject readRoot(Context context) throws IOException {
        File f = file(context);
        if (!f.isFile()) return new JSONObject();
        try {
            return new JSONObject(read(f));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void writeRoot(Context context, JSONObject root)
            throws IOException {
        try {
            root.put("version", VERSION);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
        File dir = context.getFilesDir();
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(root.toString());
        }
        File out = file(context);
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("registry publish failed");
        }
    }

    private static String read(File f) throws IOException {
        byte[] raw = new byte[(int) Math.min(f.length(), 1 << 20)];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n <= 0) break;
                off += n;
            }
            return new String(raw, 0, off, StandardCharsets.UTF_8);
        }
    }

    private SessionRegistry() {}
}
