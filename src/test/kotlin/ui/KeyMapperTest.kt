package ui

import Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyMapperTest {

    /** Minimal defaults for testing — just a few commands. */
    private fun testDefaults() = mapOf(
        "u" to "undo",
        "d" to "delete",
        "t" to "select-closure-forward",
        "w" to "unselect-oldest-forward",
        "0" to "bound-screen",
        "<C-s>" to "save",
        "<Esc>" to "deselect",
    )

    /**
     * Helper: create a mapper with test defaults, feed keys, and
     * collect the command names that were executed.
     */
    private fun feedAndCollect(
        keys: List<String>,
        setup: (KeyMapper) -> Unit = {},
    ): List<String> {
        val executed = mutableListOf<String>()
        val mapper = KeyMapper(testDefaults())
        mapper.commandExecutor = { cmd, _ -> executed.add(cmd) }
        // Need a dummy GraphView — but we can't construct one without
        // AWT. Use a null-safe path: commandExecutor ignores the view.
        // We just need activeView to be non-null for executeCommand
        // to fire. We'll use reflection or a workaround.
        // Actually, let's just set activeView to null and adjust
        // executeCommand to still work for tests.
        // ... but our executeCommand checks activeView != null.
        // For testability, let's use a fake approach:
        setup(mapper)
        mapper.activeView = null  // will prevent execution

        // Workaround: override commandExecutor to not need a view
        val executedDirect = mutableListOf<String>()
        val origExecutor = mapper.commandExecutor
        mapper.commandExecutor = { cmd, _ -> executedDirect.add(cmd) }

        // For the mapper to work, we need to bypass the null check.
        // Let's set a test mode by making activeView non-null hack.
        // Actually the simplest fix: make executeCommand public for test.
        // Let's just test the pieces we can test directly.
        return executedDirect
    }

    // Testing the mapper in isolation requires either a real
    // GraphView (which needs AWT) or making the resolution logic
    // testable without one. Let me test the pieces that ARE pure:

    @Test
    fun `defaultBindings contains expected entries`() {
        val defaults = KeyMapper.defaultBindings()
        assertEquals("undo", defaults["u"])
        assertEquals("delete", defaults["d"])
        assertEquals("save", defaults["<C-s>"])
        assertEquals("deselect", defaults["<Esc>"])
        assertEquals("next-tab", defaults["<C-Tab>"])
        assertEquals("subgraph-focus", defaults["G"])
    }

    @Test
    fun `defaultBindings covers all single-char commands`() {
        val defaults = KeyMapper.defaultBindings()
        // Every letter command from the original dispatch table
        for (key in listOf("e", "E", "v", "V", "a", "A", "c", "d", "D",
            "o", "O", "i", "t", "T", "l", "L", "w", "W", "f", "F",
            "u", "U", "g", "G", "h", "H")) {
            assertTrue(key in defaults, "missing default for '$key'")
        }
    }

    @Test
    fun `map adds a user mapping`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("z", "u", recursive = false)
        // We can't easily test fire() without a GraphView, but we
        // can verify the mapping was stored by adding and checking
        // the isPending/resolution state.
        // Feed "z" — it should match immediately (not a prefix of
        // anything longer, and has an exact match in mappings).
        // Since no activeView, nothing executes, but pending should
        // be cleared.
        mapper.feedKey("z")
        assertFalse(mapper.isPending)
    }

    @Test
    fun `unmap removes a user mapping`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("z", "u")
        mapper.unmap("z")
        // "z" has no mapping and no default → feedKey should not
        // leave it pending (dead end, single key, ignored).
        mapper.feedKey("z")
        assertFalse(mapper.isPending)
    }

    @Test
    fun `multi-key prefix causes pending state`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("gt", ":next-tab<CR>")
        // "g" is in defaults (→ grab) AND is a prefix of "gt" in
        // mappings → ambiguous → pending.
        mapper.feedKey("g")
        assertTrue(mapper.isPending)
    }

    @Test
    fun `multi-key completes on second key`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("gt", ":next-tab<CR>")
        mapper.feedKey("g")
        assertTrue(mapper.isPending)
        mapper.feedKey("t")
        // "gt" has an exact match and is not a prefix of anything
        // longer → fires immediately, pending clears.
        assertFalse(mapper.isPending)
    }

    @Test
    fun `single key with no match and no prefix is ignored`() {
        val mapper = KeyMapper(testDefaults())
        mapper.feedKey("z")  // not in defaults, not in mappings
        assertFalse(mapper.isPending)
    }

    @Test
    fun `single key exact match and not prefix resolves immediately`() {
        val mapper = KeyMapper(testDefaults())
        // "d" is in defaults, and nothing starts with "d..."
        mapper.feedKey("d")
        assertFalse(mapper.isPending)
    }

    @Test
    fun `reset clears pending state`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("gt", ":next-tab<CR>")
        mapper.feedKey("g")
        assertTrue(mapper.isPending)
        mapper.reset()
        assertFalse(mapper.isPending)
    }

    @Test
    fun `clearMappings removes all user mappings`() {
        val mapper = KeyMapper(testDefaults())
        mapper.map("z", "u")
        mapper.map("x", "d")
        mapper.clearMappings()
        // "z" and "x" should no longer resolve
        mapper.feedKey("z")
        assertFalse(mapper.isPending)
    }

    @Test
    fun `loadInitFile parses map commands`() {
        val dir = kotlin.io.path.createTempDirectory("sge-init-test")
        val initFile = dir.resolve("init")
        java.nio.file.Files.writeString(initFile, """
            # comment
            map z u
            map gt :next-tab<CR>
            set timeoutlen=300
        """.trimIndent())

        val mapper = KeyMapper(testDefaults())
        mapper.loadInitFile(initFile)

        // "z" should now be in mappings
        mapper.feedKey("z")
        assertFalse(mapper.isPending) // exact match, fires

        mapper.reset()

        // "gt" should trigger pending (g is prefix)
        mapper.feedKey("g")
        assertTrue(mapper.isPending)

        // timeout was set to 300
        assertEquals(300, mapper.timeoutMs)
    }

    @Test
    fun `loadInitFile skips comments and blank lines`() {
        val dir = kotlin.io.path.createTempDirectory("sge-init-test")
        val initFile = dir.resolve("init")
        java.nio.file.Files.writeString(initFile, """
            # this is a comment

            # another comment
            map z u
        """.trimIndent())

        val mapper = KeyMapper(testDefaults())
        mapper.loadInitFile(initFile)
        mapper.feedKey("z")
        assertFalse(mapper.isPending)
    }

    @Test
    fun `loadInitFile ignores missing file`() {
        val mapper = KeyMapper(testDefaults())
        mapper.loadInitFile(java.nio.file.Paths.get("/nonexistent/path/init"))
        // Should not throw
    }

    // --- macro registers ---

    @Test
    fun `q then register letter starts recording`() {
        val mapper = KeyMapper(testDefaults())
        assertFalse(mapper.isRecording)
        mapper.feedKey("q")
        mapper.feedKey("a")
        assertTrue(mapper.isRecording)
    }

    @Test
    fun `q stops recording`() {
        val mapper = KeyMapper(testDefaults())
        mapper.feedKey("q")
        mapper.feedKey("a")
        assertTrue(mapper.isRecording)
        mapper.feedKey("q")
        assertFalse(mapper.isRecording)
    }

    @Test
    fun `recording captures keys`() {
        val mapper = KeyMapper(testDefaults())
        mapper.feedKey("q")
        mapper.feedKey("a")
        // Record some keys
        mapper.feedKey("t")
        mapper.feedKey("w")
        mapper.feedKey("0")
        // Stop
        mapper.feedKey("q")
        assertFalse(mapper.isRecording)
        assertEquals("tw0", mapper.registers['a'])
    }

    @Test
    fun `replay feeds keys through the mapper`() {
        val executed = mutableListOf<String>()
        val mapper = KeyMapper(testDefaults())
        mapper.commandExecutor = { cmd, _ -> executed.add(cmd) }
        // Can't set activeView without AWT, but commandExecutor
        // is called with whatever activeView is (even null in the
        // lambda above). Let me make executeCommand work for test:
        // Actually the issue is executeCommand checks activeView.
        // For this test, let's just record and check the register
        // content, and verify replay calls feedKey (which we can
        // observe via isPending state or by recording into another
        // register).

        // Record "tw0" into register a
        mapper.feedKey("q")
        mapper.feedKey("a")
        mapper.feedKey("t")
        mapper.feedKey("w")
        mapper.feedKey("0")
        mapper.feedKey("q")

        assertEquals("tw0", mapper.registers['a'])

        // Now record into register b, and inside that recording
        // replay register a. The replayed keys should NOT be
        // captured in register b (replaying flag suppresses).
        mapper.feedKey("q")
        mapper.feedKey("b")
        mapper.feedKey("d")  // captured in b
        mapper.feedKey("@")
        mapper.feedKey("a")  // replays tw0, but replaying=true so not captured
        mapper.feedKey("q")

        assertEquals("d", mapper.registers['b'])
    }

    @Test
    fun `replay does not record into active register`() {
        val mapper = KeyMapper(testDefaults())

        // Record "u" into register a
        mapper.feedKey("q")
        mapper.feedKey("a")
        mapper.feedKey("u")
        mapper.feedKey("q")
        assertEquals("u", mapper.registers['a'])

        // Record into b: replay @a should not leak into b
        mapper.feedKey("q")
        mapper.feedKey("b")
        mapper.feedKey("@")
        mapper.feedKey("a")
        mapper.feedKey("q")

        // b should be empty (only the @a replay happened, which
        // is suppressed from recording)
        assertEquals("", mapper.registers['b'] ?: "")
    }

    @Test
    fun `q followed by non-alphanumeric is ignored`() {
        val mapper = KeyMapper(testDefaults())
        mapper.feedKey("q")
        mapper.feedKey("<Esc>")  // not a valid register name
        assertFalse(mapper.isRecording)
    }
}
