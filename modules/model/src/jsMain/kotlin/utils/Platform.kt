package utils

import kotlin.js.Date

private external fun require(module: String): dynamic

actual object Platform {
    private var counter = 0L

    // Node's; a browser client has no files and must not call the two file functions
    private val fs: dynamic by lazy { require("fs") }

    actual fun nowMillis(): Long = Date.now().toLong()

    actual fun clockMillis(): Double = js("performance.now()") as Double

    actual fun fixed(d: Double, digits: Int): String = d.asDynamic().toFixed(digits) as String

    actual fun nextCount(): Long = counter++

    // where there is Node (Electron's page has it too) the terminal that started the process; else the console
    actual fun warn(text: String) {
        val stderr: dynamic = js("typeof process !== 'undefined' ? process.stderr : undefined")
        if (stderr != null && stderr != undefined) stderr.write(text + "\n") else console.error(text)
    }

    actual fun readText(path: String): String = fs.readFileSync(path, "utf8") as String

    actual fun writeText(path: String, text: String) {
        fs.writeFileSync(path, text, "utf8")
    }
}
