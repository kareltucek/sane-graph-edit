package utils

/**
 * The texts of the help graph ([Graph.defaultGraph]), in markdown: a
 * cheatsheet, not the manual. [commands], [selection] and [files]
 * are the reference written by hand, the first of them being where a
 * reader starts; [topics] are what hangs around it: a category and
 * the few keys of it.
 */
object Help {
    val commands = """
        # Graph manipulation

        **Mouse**
        - Double-click — new node / edit text
        - Drag — move the selection / pan
        - Wheel — zoom
        - Right-click — colour / shape / size

        **Keys**
        - `v` / `V` — new node + edge (fwd/bwd)
        - `e` / `E` — edge to node under cursor
        - `a` / `A` — append to last active
        - `c` — clear edges
        - `d` / `D` — delete / delete + reconnect
        - `o` / `O` — layout optimiser
        - `-` / `=` — tighter / looser layout
        - `g` — grab-and-move
        - `F` / `f` — copy / paste style
        - `u` / `U` — undo / redo
    """.trimIndent()

    val selection = """
        # Selection & navigation

        - Click, `Ctrl` + click, `Ctrl` + drag
        - `Ctrl+A` / `Escape` — all / none
        - `t` / `T` — add closure (fwd/bwd)
        - `l` / `L` — add neighbours
        - `w` / `W` — remove oldest generation
        - `i` — invert
        - `h` / `H` — hide / unhide one level
        - `Space` — edit text
        - `ma` / `'a` — set / recall mark
        - `m+a` / `m-a` — add to / remove from mark
        - `mA` — marks A..Z are saved
        - `0` / `1` — fit / centre view
    """.trimIndent()

    val files = """
        # Files, tabs & clipboard

        - `Ctrl+S` / `Ctrl+Shift+S` — save / save as
        - `:open`, `:e <path>` — open
        - `F5` — reload
        - `gf` / `gF` — follow link (here / new tab)
        - `Ctrl+O` / `Ctrl+I` — jump back / forward
        - `:browse`, `:cd <dir>` — file tree, workspace
        - `Ctrl+E` — export SVG
        - `Ctrl+T` / `Ctrl+W` — new / close tab
        - `gt` / `gT` — next / previous tab
        - `Ctrl+C` / `Ctrl+X` / `Ctrl+V` — clipboard
    """.trimIndent()

    val attribution = """
        Created by Karel Tuček.

        Code is freely available at
        github.com/kareltucek/sane-graph-edit
    """.trimIndent()

    val todo = """
        # Todo

        - menu bar
        - port to compose multiplatform
        - markdown editing in nodes
        - cfg parser
        - styles (y,p)
        - folding?
        - highlight related to selection (s?)
    """.trimIndent()

    /** A category: the small coloured node that names it, and what hangs on that. */
    class Topic(val name: String, val colour: Int, val text: String)

    val topics = listOf(
        Topic(
            "Moving", 0xF4C7C3,
            """
                - `g` / `s` — grab / scale
                - `x` / `y` — one axis only
                - `:rotate`, `:mirror`
            """,
        ),
        Topic(
            "Search", 0xFCE8B2,
            """
                - `/text`, `?text` — search
                - `n` / `N` — next / previous
                - `G` — go downstream
            """,
        ),
        Topic(
            "Folding & view", 0xD9EAD3,
            """
                - `zf` / `zd` / `za` + `t T l L` — fold / unfold / toggle
                - `zi` / `zo` — toggle children / parents
                - `zR` — unhide all
                - `zz` `zt` `zb` `zs` `ze` — place the view
            """,
        ),
        Topic(
            "Text of a node", 0xC9DAF8,
            """
                - `:content-type markdown`
                - `:content-type code-<language>`
                - `:content-type code-kotlin:A.kt:10-20` — lines of a file
            """,
        ),
        Topic(
            "Depth & 3D", 0xD9D2E9,
            """
                - `:z 300`, `:z +50` — depth
                - `:set 3d` — middle button turns the view
                - `:view-front`
            """,
        ),
        Topic(
            "Links", 0xF6EFC4,
            """
                `link="..."`, followed by `gf`:
                - `graph.dot`, `graph.dot:<nodeid>`
                - `dir/`
                - `A.kt:10-20`, `README.md#Heading`
                - `sge:help`, `sge:examples`
            """,
        ),
        Topic(
            "Command line", 0xD0E0E3,
            """
                - `:` + `Tab` — complete
                - `:help keys`, `:help commands`
                - `:w` `:wa` `:e` `:e!` `:q` `:qa`
                - `:map`, `:set`, `:source`
                - `q<letter>` / `@<letter>` — macro
            """,
        ),
        Topic(
            "Scripting", 0xEAD1DC,
            """
                - `sane-graph-edit --attach` — join a window
                - `sane-graph-edit ctl help`
                - `sane-graph-edit <file> -e '<keys>'`
            """,
        ),
    )
}
