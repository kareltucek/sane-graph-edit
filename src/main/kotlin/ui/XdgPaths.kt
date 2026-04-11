package ui

import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG base-directory helpers.
 *
 * Resolves the standard `$XDG_CONFIG_HOME` and `$XDG_CACHE_HOME`
 * environment variables when set, or falls back to `~/.config` and
 * `~/.cache`. This keeps sane-graph-edit's on-disk footprint
 * consistent with the rest of a typical Linux desktop — and lets
 * tests point at a temp directory by setting the env vars before
 * JVM startup.
 *
 * No platform detection for macOS/Windows: these paths would be
 * `~/Library/Application Support` / `%APPDATA%` respectively, but
 * this tool is Linux-first and the XDG defaults are a reasonable
 * second choice on other platforms too.
 */
object XdgPaths {
    private val home: Path = Paths.get(System.getProperty("user.home"))

    val configHome: Path
        get() = System.getenv("XDG_CONFIG_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: home.resolve(".config")

    val cacheHome: Path
        get() = System.getenv("XDG_CACHE_HOME")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: home.resolve(".cache")

    /** `$XDG_CONFIG_HOME/sane-graph-edit` — session, persistent prefs. */
    val appConfigDir: Path get() = configHome.resolve("sane-graph-edit")

    /** `$XDG_CACHE_HOME/sane-graph-edit` — autosave backups, transient state. */
    val appCacheDir: Path get() = cacheHome.resolve("sane-graph-edit")

    /** `$XDG_CACHE_HOME/sane-graph-edit/backups` — one DOT file per dirty tab. */
    val backupsDir: Path get() = appCacheDir.resolve("backups")
}
