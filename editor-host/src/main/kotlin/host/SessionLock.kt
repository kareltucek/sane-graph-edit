package host

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Decides which process owns the session of a socket: the one
 * holding the lock on `<socket>.lock`. The operating system drops
 * the lock when that process ends, however it ends.
 */
object SessionLock {
    // referenced forever: a collected channel would release the lock
    private val held: MutableList<FileLock> = mutableListOf()

    /** True if this process now owns the session at [socket]; false if another one does. */
    fun acquire(socket: Path): Boolean {
        Files.createDirectories(socket.parent)
        val channel = FileChannel.open(socket.resolveSibling("${socket.fileName}.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = channel.tryLock()
        if (lock == null) channel.close() else held.add(lock)
        return lock != null
    }
}
