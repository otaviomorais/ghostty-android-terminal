/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Process-wide session list backing the tab strip.
 *
 * A singleton (not Activity state) so shells survive rotation and Activity
 * recreation. They do not survive process death — a shell's PTY dies with the
 * process, and resurrecting the shell itself would need a detached supervisor
 * the platform would kill with everything else on force-stop anyway. What
 * does survive it is the tab layout and the scrollback: every registered
 * session mirrors its raw PTY output to a {@link ReplayLog} and keeps an
 * entry in {@link SessionRegistry}, and a cold start can rebuild each tab as
 * a fresh shell with the old bytes replayed above its first prompt
 * ({@link #savedEntries}, {@link #savedReplay}, {@link #forgetSaved}).
 */
public final class SessionManager {
    private static final SessionManager INSTANCE = new SessionManager();

    public static SessionManager get() {
        return INSTANCE;
    }

    private final List<TerminalSession> sessions = new ArrayList<>();

    /** Application context kept from the first registering create(), so
      * close() can update the registry without a caller threading one. */
    private volatile Context appContext;
    private volatile File replayDir;

    private SessionManager() {}

    /**
     * Spawns a shell at the given grid size. Callers should pass the real
     * view size: spawning at a wrong size triggers a SIGWINCH on first
     * layout, and mksh reacts by wiping its initial prompt.
     *
     * @param userland Login shell under the userland engine (rootfs must be
     *               installed) instead of /system/bin/sh.
     * @param userlandOptions engine inputs (login shell, identity, home,
     *                   working directory, /proc isolation, storage binding);
     *                   used only when {@code userland} is true.
     * @param scrollbackLines lines of history the new session keeps.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener) throws IOException {
        return create(context, cols, rows, cellWidthPx, cellHeightPx, scrollbackLines,
                userland, userlandOptions, terminateProcessesOnExit, listener, null);
    }

    /**
     * As {@link #create}, but rebuilding a tab a dead process left behind:
     * {@code replay} carries that tab's recorded output and is rendered above
     * the new shell's prompt. The session still registers normally, so it can
     * be rebuilt again if this process dies too.
     */
    public TerminalSession create(Context context, int cols, int rows,
            int cellWidthPx, int cellHeightPx, int scrollbackLines, boolean userland,
            UserlandOptions userlandOptions, boolean terminateProcessesOnExit,
            TerminalSession.Listener listener, byte[] replay) throws IOException {
        SessionCommand command = userland
                ? UserlandRootfs.command(context, userlandOptions)
                : SessionCommand.androidShell(
                        context.getFilesDir().getAbsolutePath(),
                        context.getCacheDir().getAbsolutePath());
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, command, terminateProcessesOnExit,
                replay, listener);
        synchronized (this) {
            sessions.add(s);
        }
        register(context, s, userland);
        return s;
    }

    /**
     * Attaches a tab to one terminal of the running guest machine.
     *
     * Nothing is spawned here — the machine booted its own gettys, and this
     * only opens a view onto one of them, so unlike {@link #create} it cannot
     * fail for want of a rootfs or a shell. {@code terminal} indexes the
     * machine's terminals: 0 is the serial console the guest boots on.
     *
     * VM tabs are deliberately not registered: a guest machine spans an
     * emulator process of its own, and replaying a getty's scrollback into a
     * machine that no longer exists would show a prompt that answers nothing.
     */
    public TerminalSession attachVm(VmMachine machine, int terminal, int cols,
            int rows, int cellWidthPx, int cellHeightPx, int scrollbackLines,
            TerminalSession.Listener listener) throws IOException {
        TerminalSession s = new TerminalSession(cols, rows, cellWidthPx,
                cellHeightPx, scrollbackLines, machine, terminal, listener);
        synchronized (this) {
            sessions.add(s);
        }
        return s;
    }

    /**
     * The machine terminals a tab is currently open on, so a caller can pick
     * one that is not. Detaching frees an index for reuse: the guest side is
     * untouched by a tab closing, so reattaching lands back in the same shell.
     */
    public boolean isVmTerminalOpen(int terminal) {
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.isVm() && s.vmTerminal() == terminal) return true;
            }
        }
        return false;
    }

    public List<TerminalSession> sessions() {
        synchronized (this) {
            return new ArrayList<>(sessions);
        }
    }

    public int indexOf(TerminalSession s) {
        synchronized (this) {
            return sessions.indexOf(s);
        }
    }

    public boolean close(TerminalSession s) {
        boolean removed;
        synchronized (this) {
            removed = sessions.remove(s);
        }
        if (removed) {
            s.close();
            unregister(s);
        }
        return removed;
    }

    /**
     * Closes every session and empties the list, leaving a running guest
     * machine alone: a VM tab only detaches, so the machine keeps running and
     * a later tab finds its shells where they were. For callers that need the
     * tabs gone but have no business with the machine — replacing the userland
     * rootfs, which the machine does not touch.
     */
    public void closeSessions() {
        List<TerminalSession> copy;
        synchronized (this) {
            copy = new ArrayList<>(sessions);
            sessions.clear();
        }
        for (TerminalSession s : copy) {
            s.close();
            unregister(s);
        }
    }

    /**
     * Kills every shell and empties the list. Used by the "Exit" action in
     * the foreground-service notification, which can fire while no Activity
     * is alive — so it must leave no dead sessions behind for a later
     * relaunch to re-attach to. Closing through here also clears the saved
     * registry: an explicit "Exit" is a clean end, and the next launch starts
     * empty.
     */
    public void closeAll() {
        closeSessions();
        // Closing a VM tab only detaches it, by design — so the machine would
        // otherwise outlive the app that started it, with no tab left to reach
        // it from and no way to stop it. "Exit" means exit.
        VmMachine.stopIfRunning();
        Context c = appContext;
        if (c != null) {
            SessionRegistry.clear(c);
            deleteReplayDir();
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return sessions.isEmpty();
        }
    }

    // --- save/restore -------------------------------------------------------

    /**
     * The tabs a dead process left saved, in tab order. Empty on a clean
     * launch (every exit path unregisters) or before a first session ever
     * ran. A cold start calls this, reads the replays with
     * {@link #savedReplay}, then {@link #forgetSaved}s before spawning.
     */
    public List<SessionRegistry.Entry> savedEntries(Context context) {
        appContext = context.getApplicationContext();
        return SessionRegistry.load(context);
    }

    /** The recorded output of a saved tab, or empty if none was kept. */
    public byte[] savedReplay(Context context, int id) {
        File dir = prepareReplayDir(context);
        ReplayLog log = new ReplayLog(dir, id);
        byte[] bytes = log.read();
        log.close();
        return bytes;
    }

    /**
     * Consumes the saved set: the entries are dropped and every replay file
     * deleted. Call once the saved tabs have been read, before spawning the
     * rebuilt ones — a crash mid-restore then falls back to a plain launch
     * rather than replaying stale history forever.
     */
    public void forgetSaved(Context context) {
        SessionRegistry.clear(context);
        deleteReplayDir();
    }

    private void register(Context context, TerminalSession s, boolean userland) {
        appContext = context.getApplicationContext();
        File dir = prepareReplayDir(context);
        int id = SessionRegistry.mintId(context);
        SessionRegistry.add(context, id, userland, s.label());
        // Attached after the spawn: the shell's first reads may precede it,
        // which only loses output a fresh shell reprints (see attachReplayLog).
        s.attachReplayLog(new ReplayLog(dir, id), id);
        pruneReplays(context);
    }

    private void unregister(TerminalSession s) {
        int id = s.sessionId();
        s.dropReplayLog();
        Context c = appContext;
        if (c != null && id != 0) SessionRegistry.remove(c, id);
    }

    /**
     * Deletes the logs of sessions that died without being closed (a crashed
     * process's last tab, before the next launch got to forgetSaved). Runs on
     * every register because the registry is also where the survivor set
     * lives.
     */
    private void pruneReplays(Context context) {
        Set<Integer> live = new HashSet<>();
        synchronized (this) {
            for (TerminalSession s : sessions) {
                if (s.sessionId() != 0) live.add(s.sessionId());
            }
        }
        for (SessionRegistry.Entry e : SessionRegistry.load(context)) {
            live.add(e.id);
        }
        ReplayLog.prune(prepareReplayDir(context), live);
    }

    private File prepareReplayDir(Context context) {
        File dir = replayDir;
        if (dir == null) {
            dir = new File(context.getFilesDir(), "session-replay");
            dir.mkdirs(); // best effort: IO errors are swallowed by design
            replayDir = dir;
        }
        return dir;
    }

    private void deleteReplayDir() {
        File dir = replayDir;
        if (dir != null) ReplayLog.deleteAll(dir);
    }
}
