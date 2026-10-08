package utils

import java.io.File
import java.util.concurrent.atomic.AtomicLong

actual object Platform {
    private val counter = AtomicLong()

    actual fun nowMillis(): Long = System.currentTimeMillis()

    actual fun fixed(d: Double, digits: Int): String = "%.${digits}f".format(d)

    actual fun nextCount(): Long = counter.getAndIncrement()

    actual fun warn(text: String) = System.err.println(text)

    actual fun readText(path: String): String = File(path).readText()

    actual fun writeText(path: String, text: String) = File(path).writeText(text)
}
