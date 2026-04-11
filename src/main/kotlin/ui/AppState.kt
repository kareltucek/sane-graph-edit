package ui

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Minimal persistent-preferences store, backed by a plain
 * `~/.sanegrapedit.properties` file. Exists so that "Open" and "Save"
 * dialogs start in the directory the user was last browsing, rather
 * than re-defaulting to the process CWD every time.
 *
 * Deliberately simple: no JSON, no config schema, no versioning. Two
 * fields today, and if it grows beyond a handful of fields we should
 * bring in a real config library. Reads/writes best-effort swallow
 * IO errors — losing a last-directory hint is never worth crashing.
 */
object AppState {
    private val file: File = File(System.getProperty("user.home"), ".sanegrapedit.properties")
    private val props: Properties = Properties()

    init {
        if (file.exists()) {
            try {
                file.inputStream().use(props::load)
            } catch (_: Throwable) {
                // ignore — start from empty
            }
        }
    }

    var lastOpenDir: Path?
        get() = props.getProperty("lastOpenDir")?.let(Paths::get)
        set(value) {
            if (value != null) {
                props.setProperty("lastOpenDir", value.toString())
                save()
            }
        }

    var lastSaveDir: Path?
        get() = props.getProperty("lastSaveDir")?.let(Paths::get)
        set(value) {
            if (value != null) {
                props.setProperty("lastSaveDir", value.toString())
                save()
            }
        }

    private fun save() {
        try {
            file.outputStream().use { props.store(it, "sane-graph-edit preferences") }
        } catch (_: Throwable) {
            // ignore
        }
    }
}
