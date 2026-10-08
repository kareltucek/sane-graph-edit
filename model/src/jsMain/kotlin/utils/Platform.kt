package utils

import kotlin.js.Date

private external fun require(module: String): dynamic

actual object Platform {
    private var counter = 0L

    // Node's; a browser client has no files and must not call the two file functions
    private val fs: dynamic by lazy { require("fs") }

    actual fun nowMillis(): Long = Date.now().toLong()

    actual fun fixed(d: Double, digits: Int): String = d.asDynamic().toFixed(digits) as String

    actual fun nextCount(): Long = counter++

    actual fun readText(path: String): String = fs.readFileSync(path, "utf8") as String

    actual fun writeText(path: String, text: String) {
        fs.writeFileSync(path, text, "utf8")
    }
}
