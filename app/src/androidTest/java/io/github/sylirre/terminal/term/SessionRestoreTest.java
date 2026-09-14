/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.term;

import static io.github.sylirre.terminal.TestUtil.waitFor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.sylirre.terminal.term.SessionRegistry.Entry;

/**
 * Save/restore of the tab set: the replay log's bounded ring, the registry's
 * JSON round trips, and the wiring through {@link SessionManager} — ending
 * with the behavior the whole feature exists for: output recorded by a
 * session whose process died is replayed into the screen of the rebuilt one.
 */
@RunWith(AndroidJUnit4.class)
public class SessionRestoreTest {

    private static final long TIMEOUT_MS = 15_000;
    private static final int CHUNK = 64 * 1024;

    private static final TerminalSession.Listener NOOP =
            new TerminalSession.Listener() {
                @Override public void onUpdate(TerminalSession session) {}
                @Override public void onTitleChanged(TerminalSession session) {}
                @Override public void onBell(TerminalSession session) {}
                @Override public void onExited(TerminalSession session, int exitCode) {}
            };

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        SessionManager manager = SessionManager.get();
        // Close any sessions another suite left alive, then drop the saved
        // set outright: a suite that crashes mid-test must not leak entries
        // this class would read as its own baseline.
        manager.closeAll();
        manager.savedEntries(ctx);
        manager.forgetSaved(ctx);
    }

    @After
    public void tearDown() {
        SessionManager.get().closeAll();
    }

    // --- ReplayLog ---

    @Test
    public void replayLogKeepsContiguousSuffix() throws Exception {
        File dir = new File(ctx.getCacheDir(), "replay-" + System.nanoTime());
        dir.mkdirs();
        ReplayLog log = new ReplayLog(dir, 42);
        int total = 3 * ReplayLog.CAP + CHUNK;   // forces two rotations
        byte[] chunk = new byte[CHUNK];
        for (int pos = 0; pos < total; pos += CHUNK) {
            for (int i = 0; i < CHUNK; i++) {
                chunk[i] = expected(pos + i);
            }
            log.append(chunk, CHUNK);
        }
        byte[] back = log.read();
        assertTrue("suffix shorter than one window: " + back.length,
                back.length >= ReplayLog.CAP);
        assertTrue("suffix larger than two windows plus slack: " + back.length,
                back.length <= 2 * ReplayLog.CAP + 3 * CHUNK);
        byte[] want = new byte[back.length];
        for (int i = 0; i < back.length; i++) want[i] = expected(total - back.length + i);
        assertArrayEquals("replayed bytes are not a contiguous suffix", want, back);
        log.delete();
        assertEquals(0, log.read().length);
        deleteTree(dir);
    }

    // --- SessionRegistry ---

    @Test
    public void registryRoundTripsAndMintsMonotonicIds() {
        SessionRegistry.clear(ctx);
        int id1 = SessionRegistry.mintId(ctx);
        SessionRegistry.add(ctx, id1, true, "bash");
        int id2 = SessionRegistry.mintId(ctx);
        assertTrue("minted ids must grow across adds", id2 > id1);
        SessionRegistry.add(ctx, id2, false, "sh");

        List<Entry> loaded = SessionRegistry.load(ctx);
        assertEquals(2, loaded.size());
        assertEquals(id1, loaded.get(0).id);
        assertTrue(loaded.get(0).userland);
        assertEquals("bash", loaded.get(0).label);
        assertEquals(id2, loaded.get(1).id);
        assertTrue(!loaded.get(1).userland);

        SessionRegistry.remove(ctx, id1);
        loaded = SessionRegistry.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals(id2, loaded.get(0).id);

        // The high-water mark survives removals: a closed tab's id is never
        // reissued while its replay files could still be around.
        int id3 = SessionRegistry.mintId(ctx);
        assertTrue(id3 > id2);
        SessionRegistry.clear(ctx);
        assertEquals(0, SessionRegistry.load(ctx).size());
    }

    // --- SessionManager wiring ---

    @Test
    public void liveShellRegistersMirrorsAndUnregisters() throws Exception {
        SessionManager manager = SessionManager.get();
        TerminalSession s = manager.create(ctx, 80, 24, 8, 16, 10_000,
                false, null, false, NOOP);
        try {
            assertTrue("session must carry a registry id", s.sessionId() != 0);
            List<Entry> saved = manager.savedEntries(ctx);
            assertEquals(1, saved.size());
            assertEquals(s.sessionId(), saved.get(0).id);
            assertEquals("sh", saved.get(0).label);

            String marker = "PROBE" + System.nanoTime();
            waitFor("echo reaches the shell", TIMEOUT_MS, () -> {
                s.write("echo " + marker + "\n");
                return new String(s.readReplay(), StandardCharsets.UTF_8)
                        .contains(marker);
            });

            manager.close(s);
            assertEquals(0, manager.savedEntries(ctx).size());
            assertEquals(0, s.readReplay().length);
        } finally {
            manager.close(s);
        }
    }

    @Test
    public void rebuiltTabShowsDiedTabsReplayedOutput() throws Exception {
        SessionManager manager = SessionManager.get();
        // A session whose shell dies on its own, with nobody around to close
        // the tab, is exactly what a process death leaves behind: the entry
        // and its replay files persist while the PTY is gone.
        TerminalSession dead = manager.create(ctx, 80, 24, 8, 16, 10_000,
                false, null, false, NOOP);
        String marker = "REPLAYED" + System.nanoTime();
        final byte[][] captured = new byte[1][];
        waitFor("output is recorded", TIMEOUT_MS, () -> {
            dead.write("echo " + marker + "\n");
            captured[0] = dead.readReplay();
            return new String(captured[0], StandardCharsets.UTF_8).contains(marker);
        });
        dead.write("exit\n"); // the shell goes; the tab stays "saved"

        List<Entry> saved = manager.savedEntries(ctx);
        assertEquals(1, saved.size());
        byte[] replay = manager.savedReplay(ctx, saved.get(0).id);
        assertTrue("saved replay must carry the marker",
                new String(replay, StandardCharsets.UTF_8).contains(marker));
        manager.forgetSaved(ctx);
        assertEquals(0, SessionRegistry.load(ctx).size());

        // Rebuild: the same API path MainActivity's restore hook drives.
        TerminalSession rebuilt = manager.create(ctx, 80, 24, 8, 16, 10_000,
                false, null, false, NOOP, replay);
        try {
            waitFor("replayed output lands on the new screen", TIMEOUT_MS,
                    () -> screenText(rebuilt).contains(marker));
        } finally {
            manager.close(rebuilt);
            manager.close(dead);
        }
    }

    private String screenText(TerminalSession s) {
        ScreenSnapshot snap = new ScreenSnapshot();
        s.emulator.snapshot(snap);
        return snap.text();
    }

    // Byte at stream position pos: cheap and positional, so any gap,
    // duplication, or reordering in the read-back suffix fails the compare.
    private static byte expected(int pos) {
        return (byte) (pos * 31 + (pos >>> 8));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
