package ui

import client.Autosave
import client.Fs
import client.Path

/**
 * The window's autosave: [client.Autosave] over the tabs of
 * [tabManager], on Swing's timer, so the snapshots are written on
 * the event thread alongside normal mutations. What is written,
 * where and when is described there.
 */
class AutosaveManager(
    tabManager: TabManager,
    backupDir: Path = Fs.cacheDir.resolve("backups"),
    intervalMs: Int = Autosave.DEFAULT_INTERVAL_MS,
) {
    private val autosave = Autosave({ tabManager.tabs.views }, SwingScheduler, backupDir, intervalMs)

    /** Begin the periodic backups. Safe to call once. */
    fun start() = autosave.start()

    /** Stop them. Call from the window-close handler. */
    fun stop() = autosave.stop()

    /** Write every tab with unsaved changes right now. Safe to call from the EDT. */
    fun flushAll() = autosave.flushAll()
}
