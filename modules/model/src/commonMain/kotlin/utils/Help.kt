package utils

/**
 * The texts of the help graph ([Graph.defaultGraph]), in markdown: a
 * cheatsheet, not the manual. [commands] is where a reader starts;
 * [topics] are what hangs around it: a category and the few keys of
 * it. The first four of them, and [commands], are the reference
 * written by hand.
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

    /** A child of the selection node. */
    val marks = """
        # Marks

        - `m{a-z}` / `'{a-z}` — set / recall mark
        - `m+{a-z}` / `m-{a-z}` — add to / remove from mark
        - `m{A-Z}`, `m+{A-Z}`, `m-{A-Z}`, `'{A-Z}` — the same, saved with the file
    """.trimIndent()

    /** A category: the small coloured node that names it, and what hangs on that. */
    class Topic(val name: String, val colour: Int, val text: String)

    val topics = listOf(
        Topic(
            "Selection & navigation", 0xCFE2F3,
            """
                # Selection & navigation

                - Click, `Ctrl` + click, `Ctrl` + drag
                - `Ctrl+A` / `Escape` — all / none
                - `t` / `T` — add closure (fwd/bwd)
                - `l` / `L` — add neighbours (fwd/bwd)
                - `w` / `W` — remove oldest generation (fwd/bwd)
                - `i` — invert
                - `h` / `H` — hide / unhide one level
                - `Space` / `Shift+Space` — edit text
                - `0` / `1` — fit / centre view
            """,
        ),
        Topic(
            "Files, tabs & clipboard", 0xFFE599,
            """
                # Files, tabs & clipboard

                - `Ctrl+S` / `Ctrl+Shift+S` — save / save as
                - `:open`, `:e {path}` — open
                - `F5` — reload
                - `gf` / `gF` — follow link (here / new tab)
                - `Ctrl+O` / `Ctrl+I` — jump back / forward
                - `:browse`, `:cd {dir}` — file tree, workspace
                - `Ctrl+E` / `Ctrl+Shift+E` — export SVG (all / selection)
                - `Ctrl+T` / `Ctrl+W` — new / close tab
                - `gt` / `gT` — next / previous tab
                - `Ctrl+C` / `Ctrl+X` / `Ctrl+V` — clipboard
            """,
        ),
        Topic(
            "Todo", 0xD9D9D9,
            """
                # Todo

                - menu bar
                - port to compose multiplatform
                - markdown editing in nodes
                - cfg parser
                - styles (y,p)
                - folding?
                - highlight related to selection (s?)
            """,
        ),
        Topic(
            "About", 0xD9D9D9,
            """
                # About

                Created by Karel Tuček.

                Code is freely available at
                github.com/kareltucek/sane-graph-edit
            """,
        ),
        Topic(
            "Moving", 0xF4C7C3,
            """
                # Moving

                - `g` / `s` — grab / scale
                - `gx` / `gy` / `sx` / `sy` — along one axis only
                - `:rotate`, `:mirror`, `:mirror-vertical`
            """,
        ),
        Topic(
            "Search", 0xFCE8B2,
            """
                # Search

                - `/{text}` / `?{text}` — search (fwd/bwd)
                - `n` / `N` — next / previous
                - `G` — go downstream
            """,
        ),
        Topic(
            "Folding & view", 0xD9EAD3,
            """
                # Folding & view

                - `zf{motion}` / `zd{motion}` / `za{motion}` — fold / unfold / toggle
                - `{motion}` — `t` / `T` / `l` / `L`
                - `zi` / `zI` — toggle children / all downstream
                - `zo` / `zO` — toggle parents / all upstream
                - `zm` / `zr` / `zR` — hide / unhide one level / unhide all
                - `zz` / `z1` / `z0` — centre / centre / fit
                - `zt` / `zb` / `zs` / `ze` — to top / bottom / left / right edge
            """,
        ),
        Topic(
            "Text of a node", 0xC9DAF8,
            """
                # Text of a node

                - `:content-type {type}`
                - `{type}` — `plain` / `markdown` / `code` / `code-{language}`
                - `:content-type {type}:{file}` — the text is that file
                - `{file}` — `A.kt` / `A.kt:10-20` / `README.md#Heading`
            """,
        ),
        Topic(
            "Depth & 3D", 0xD9D2E9,
            """
                # Depth & 3D

                - `:z` / `:z {n}` / `:z +{n}` / `:z -{n}` / `:z ={n}` — depth
                - `:set 3d` / `:set no3d` / `:set 3d!` — 3D mode
                - middle button — turn the view
                - `:view-front`
            """,
        ),
        Topic(
            "Links", 0xF6EFC4,
            """
                # Links

                - `link="{link}"` — `gf` / `gF`
                - `link-{letter}="{link}"` — `g{letter}`
                - `{link}`:
                  - `graph.dot` / `graph.dot:{nodeid}` / `:{nodeid}`
                  - `dir/`
                  - `A.kt` / `A.kt:10-20` / `README.md#Heading`
                  - `sge:help` / `sge:examples`
            """,
        ),
        Topic(
            "Command line", 0xD0E0E3,
            """
                # Command line

                - `:` + `Tab` / `Shift+Tab` / `Ctrl+D` — complete
                - `:help` / `:help keys` / `:help commands` / `:help init`
                - `:w` / `:wa` / `:wq` / `:e` / `:e!` / `:q` / `:q!` / `:qa` / `:qa!`
                - `:map` / `:noremap` / `:remap` / `:unmap`
                - `:set {mode}` / `:set no{mode}` / `:set {mode}!`
                - `:source {file}`
                - `q{letter}` / `@{letter}` — record / replay macro
            """,
        ),
        Topic(
            "Scripting", 0xEAD1DC,
            """
                # Scripting

                - `sane-graph-edit --attach` — join a window
                - `sane-graph-edit ctl help`
                - `sane-graph-edit {file} -e '{keys}'`
            """,
        ),
    )
}
