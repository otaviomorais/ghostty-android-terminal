/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A session's raw PTY output mirrored to disk, bounded to roughly the last
 * two {@value #CAP}-byte windows, so the scrollback can be replayed after the
 * process dies (crash or system kill). The shell itself cannot survive that
 * — its PTY dies with the process — but everything the user saw can.
 *
 * Two files per log: a current one and a previous one. When the current one
 * passes {@link #CAP} it is renamed onto the previous slot and a fresh one
 * starts, so a replay reads previous-then-current and recovers a contiguous
 * suffix of the byte stream. A process kill can only cost the bytes still in
 * the write buffer (one PTY read at a time) at the newest end, or, if it
 * lands mid-rotation, the older window — never a middle gap.
 *
 * The session's reader thread is the only writer. {@link #close()} and
 * {@link #delete()} are synchronized so the main thread can tear a log down
 * when its tab closes, and {@link #read()} may run any time (concurrent with
 * an append it may duplicate a trailing chunk, which a scrollback replay
 * absorbs as one extra repaint).
 *
 * The log never fails a session: every IO error is swallowed — a full or
 * unmounted store costs history, not the shell.
 */
public final class ReplayLog {

    /** Bytes per window; the replayed suffix is between CAP and 2*CAP long. */
    static final int CAP = 1 << 20; // 1 MiB

    private final File dir;
    private final int id;
    private FileOutputStream out;
    private long written;

    ReplayLog(File dir, int id) {
        this.dir = dir;
        this.id = id;
    }

    /** Appends the first {@code n} bytes of {@code buf} to the log. */
    public synchronized void append(byte[] buf, int n) {
        try {
            File main = mainFile();
            if (out == null) {
                dir.mkdirs();
                out = new FileOutputStream(main, true);
                written = main.length();
            }
            out.write(buf, 0, n);
            written += n;
            if (written >= CAP) rotate();
        } catch (IOException e) {
            closeQuietly();
        }
    }

    /**
     * Moves the current window onto the previous slot. Called under the
     * monitor, with the stream still open from {@link #append}.
     */
    private void rotate() {
        closeQuietly();
        File prev = prevFile();
        if (prev.exists() && !prev.delete()) return; // rename would fail too;
        mainFile().renameTo(prev);                    // retried after CAP more
    }

    /** Ends the log but keeps its files (for a restore that has not happened
      * yet). */
    public synchronized void close() {
        closeQuietly();
    }

    /** Ends the log and removes its files (tab closed cleanly, or consumed
      * by a restore). */
    public synchronized void delete() {
        closeQuietly();
        mainFile().delete();
        prevFile().delete();
    }

    /** The recorded suffix, oldest byte first; empty when nothing was kept. */
    public synchronized byte[] read() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copyInto(prevFile(), bytes);
        copyInto(mainFile(), bytes);
        return bytes.toByteArray();
    }

    /**
     * Deletes the files of ids that no longer name a live or saved tab, so a
     * crashed session (which never reached close()) cannot leave a log
     * behind. The registry's id high-water mark makes fresh ids miss existing
     * files on their own, so an id is only reclaimed once its entry is gone.
     */
    public static synchronized void prune(File dir, Collection<Integer> liveIds) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<Integer> keep = new HashSet<>(liveIds);
        for (File f : files) {
            int dot = f.getName().indexOf('.');
            if (dot <= 0) continue; // not "<id>.bin[.old]"
            int id;
            try {
                id = Integer.parseInt(f.getName().substring(0, dot));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!keep.contains(id)) {
                new File(dir, id + ".bin").delete();
                new File(dir, id + ".bin.old").delete();
            }
        }
    }

    /** Removes every log in the directory (restore consumed the saved ids). */
    public static synchronized void deleteAll(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    private File mainFile() {
        return new File(dir, id + ".bin");
    }

    private File prevFile() {
        return new File(dir, id + ".bin.old");
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }

    private static void copyInto(File f, ByteArrayOutputStream into) {
        if (!f.isFile()) return;
        byte[] chunk = new byte[8192];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(chunk)) > 0) into.write(chunk, 0, n);
        } catch (IOException ignored) {
            // Partial read is still a usable suffix.
        }
    }
}
