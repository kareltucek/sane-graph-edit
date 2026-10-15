# sane-graph-edit

A act-by-keyboard move-by-mouse controlled, VIM-inspired desktop editor for small to medium graphs (thousands objects should be fine; no hardware acceleration). 

Meant for convenient information storage - like mind maps. Ergomy is the main goal. No fighting with cumbersome UI. You can draft a tree or a dependency graph roughly as fast as you can describe it out loud.

Example of editing:

- `v` or doubleclick to spawn a node.
- `Space + <write> + escape` to edit a node.
- `v` or `a` to create connected nodes.
- `e` to connect node under mouse from selected node. `E` to connect in reverse direction.
- ...

Selecting:

- Click to select, `Ctrl+click` to multi-select, `Ctrl+drag` to box-select.
- Search-based: `/` , type, `enter`.
- Hit `t` to select full subtree (of successors). `T` for predecessors.
- `l` to add one generation of neighbours. `w` to remove the oldest generation
- ...

Registers, macros, mappings:

- record vim macros with `q` + `<letter>` and replay with `@` + `<letter>`. 
- map stuff `map gh tih0<Esc>` - gh hides everything except subtree of the current node and moves view to it. (Mapping a `g<letter>` shadows the built-in meaning — `gf` follows links — and `link-<letter>` for that letter.)
- `m<letter>` + `'<letter>` mark graph subsets; `m+<letter>` adds the selection to a mark, `m-<letter>` takes it out of it.
- some `:` commands.
- headless mode.
- ...

Saves in a DOT-like format, but full compatibility is not our goal.

Written in Kotlin + Swing, runs anywhere JDK 21 runs. (Yes, it *is* ugly, and hidpi support is bad.)

Originally written by me in 2022, finished with a heavy help of Claude Code. 

End of Human-written README. The rest is AI-generated, but I hope it's accurate and useful.

------------------------------

## Status

Being revived from a long-dormant "forgotten changes" state.
Buildable, runnable, but rough around the edges. See
[`tasks/roadmap.md`](tasks/roadmap.md) for the plan and
[`docs/developer/architecture.md`](docs/developer/architecture.md) for
the code-level picture.

## Install & run

### Quick start (requires JDK 21+)

```sh
git clone git@github.com:kareltucek/sane-graph-edit.git
cd sane-graph-edit
./gradlew run
```

Gradle downloads the right Kotlin compiler via the JVM toolchain
config; you only need a JDK 21 (or newer) on your `$PATH` or
managed by [sdkman](https://sdkman.io/).

`./gradlew run --args=examples/index.dot` is a place to start: a graph
whose nodes lead to the examples, the built-in help, this manual and the
file format. Select a node and press `gf`; `Ctrl+O` goes back.

### Fat jar (portable, ~2 MB)

```sh
./gradlew fatJar
java -jar build/libs/sane-graph-edit-*-all.jar
```

A single self-contained `.jar` you can copy to any machine with
JDK 21+. Rename it, put it on a USB stick, alias it in your
shell — whatever works.

### AppImage (recommended for Linux, ~54 MB, no Java dependency)

```sh
./gradlew appimage
```

Produces a single self-contained
`build/dist/sane-graph-edit-<version>-x86_64.AppImage` file.
`chmod +x` it and run — no installation needed, no Java needed on
the target machine.

```sh
chmod +x build/dist/sane-graph-edit-*-x86_64.AppImage
./build/dist/sane-graph-edit-*-x86_64.AppImage
```

Requires
[appimagetool](https://github.com/AppImage/appimagetool) on
`$PATH` or at `~/.local/bin/appimagetool`:

```sh
mkdir -p ~/.local/bin
wget -O ~/.local/bin/appimagetool \
  https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
chmod +x ~/.local/bin/appimagetool
```

### Native app directory (no Java dependency, ~160 MB)

```sh
./gradlew jpackage
```

Produces `build/dist/sane-graph-edit/` — a directory containing a
native `bin/sane-graph-edit` launcher and a bundled JRE. Copy the
whole directory to `/opt`, `~/bin`, or wherever you keep local
apps. The user does **not** need Java installed.

```sh
# run it directly
build/dist/sane-graph-edit/bin/sane-graph-edit

# or install system-wide (also available via `make install-swing`)
sudo cp -r build/dist/sane-graph-edit /opt/
sudo ln -sf /opt/sane-graph-edit/bin/sane-graph-edit /usr/local/bin/sane-graph-edit
```

That is the Swing client. The Electron client has no package yet:
`make electron` builds it and `sudo make install` installs a
`sane-graph-edit` command that starts it from this source tree.

For a `.deb` or `.rpm` installer instead of a plain directory:

```sh
./gradlew jpackage -Ptype=deb   # Debian/Ubuntu (.deb)
./gradlew jpackage -Ptype=rpm   # Fedora/RHEL (.rpm)
```

These require the corresponding packaging tools (`dpkg-deb` /
`rpmbuild`) to be installed.

### Headless / scripting

Run the editor without a window to automate exports and other
batch operations:

```sh
# Export a DOT file to SVG, no window, no user interaction
sane-graph-edit input.dot -e ':export svg output.svg<Enter>'

# Recall a persistent mark (A-Z), then export just those nodes
sane-graph-edit input.dot -e "'A" -e ':export-selection svg a.svg<Enter>'
```

Flags:
- `-e '<keys>'` — execute a key sequence and exit. Repeatable.
  The argument is whatever you'd type interactively:
  `':export svg foo.svg<Enter>'`, `"'A"`, `'dih'`, anything.
- `-u` — stay open with a window after running `-e`. Useful for
  pre-configuring a session.
- `[file]` — positional arg: DOT file to load on startup.
- `-h`, `--help` — usage.

### Development

```sh
./gradlew build          # compile + test + assemble
./gradlew test           # run unit tests only
./gradlew fatJar         # rebuild the fat jar
./gradlew jpackage       # rebuild the native app-image
```

## Using it

### Mouse

| Action                       | What it does                                       |
|------------------------------|----------------------------------------------------|
| Click a node                 | Select it (replaces current selection)             |
| `Ctrl` + click node          | Toggle that node in/out of the multi-selection     |
| `Ctrl` + click empty + drag  | Selection box — rubber-band adds to selection      |
| Drag a selected node         | Move the selection                                 |
| …with `Shift` held           | The selection moves exactly; nodes it lands on make way and the descendants that hang from it alone follow, by springs, a step for each picture, at a speed that does not depend on how often pictures are drawn, until they rest; one undo step |
| Drag on empty space          | Pan the view                                       |
| Double-click empty           | Create a new node                                  |
| Double-click a node          | Edit its text in place                             |
| Right-click                  | Open the colour / shape / size / content picker on hover node |
| Mouse wheel                  | Zoom in / out, centred on the cursor               |

### Keyboard — structural edits

| Key       | Action                                                          |
|-----------|-----------------------------------------------------------------|
| `e` / `E` | Connect selection → node under cursor (forward / backward)      |
| `v` / `V` | Spawn new node at cursor, connect from selection (fwd / bwd)    |
| `a` / `A` | Append new node + edge from the last active node                |
| `c`       | Clear edges (within selection, or incident to selection)        |
| `d`       | Delete selected nodes                                           |
| `D`       | Delete selected nodes but reconnect predecessors to successors  |
| `o`       | Layout optimiser while held: the selected nodes (all, with none selected) move a spring pass a picture until the key is let go; one undo step |
| `O`       | The same, around one selected node: it stays where it is and its children are laid out around it (several selected: as `o`) |
| `:set suns` / `:set nosuns` | Show (made anew now and whenever `o` is let go) / stop showing the systems the whole graph is settled by: a ring (3D: a wire icosahedron) around each, as big as the room it wants, coloured by level, and each node's own room (level 0), the suns in the first colour, the planets black; `:set suns=<levels>` (e.g. `0,1,2`) only those of these levels, also while `o` runs, and `o` on the whole graph moves those levels only |
| `g`       | Toggle "grab" mode — move the selection without holding a button |

### Keyboard — selection & navigation

| Key        | Action                                                    |
|------------|-----------------------------------------------------------|
| `Ctrl+A`   | Select all / deselect all (toggle)                        |
| `Escape`   | Clear selection                                           |
| `t` / `T`  | Add the forward / backward reachable closure to selection |
| `l` / `L`  | Add one generation of neighbours (forward / backward)     |
| `w` / `W`  | Remove the "oldest" generation of the current selection   |
| `i`        | Invert selection (among visible nodes)                     |
| `h`        | Hide the selected nodes (levelled; use `ih` to keep only selection) |
| `H`        | Unhide one level (undo the most recent `h`)               |
| `0`        | Fit view to all visible (or to selection if any)          |
| `1`        | Centre view on selection                                  |
| `Space`    | Edit the text of the last active node                     |
| `Shift+Space` | Same, but replace selection with that node first       |

### How big a node is: `node-size`

A node is as big as its text ("auto") unless it was given a size of
its own ("manual", in the picker): then it keeps that size whatever
is in it, and what does not fit is cut off. A selected manual node
shows its corners; dragging one resizes the node, the opposite corner
staying where it is (not in 3D mode). Back to "auto" in the picker.
In the file it is the attribute `node-size="320x200"`, width and
height in the graph's units.

### What a node's text is: `content-type`

A node's text is plain unless its `content-type` attribute says
otherwise. The same types, and `graph`, `directory`, `workspace` and
`file`, say what a link points at (`link-content-type-<letter>`); the
file format's table of content types says which is allowed where:

| `content-type` | The text is            | Shown, while not edited (Electron client)          |
|----------------|------------------------|-----------------------------------------------------|
| (none)         | plain text             | as it is                                            |
| `code-<lang>`  | code, e.g. `code-scala` | highlighted as neovim highlights that file type    |
| `code`         | code in no named language | as it is                                         |
| `markdown`     | markdown               | formatted; ` ```mermaid ` and ` ```dot ` blocks are drawn as diagrams |

A `terminal` node (Electron client) is not text but a terminal with
your shell (`$SHELL`) in it, as big as the node; making a node one
gives it a size of 80 columns and 24 rows, to be changed by its
corners. The shell starts when the node is first entered (as a node
is edited: `Space`, …) and then runs on and shows what it prints,
whether the keys are its or the graph's. `Shift+Escape`, or a click
on the graph, gives the keys back to the graph. It ends when the
shell exits, the node is removed or the window closes. Nothing of it
is saved, and opening a file starts nothing: the document only says
that the node is a terminal. What the terminal shows is drawn in the
node, in the node's colours, wherever nodes are drawn: in 3D mode and
in a headset too, where the trigger on the node enters it (the keys
are still the window's: the keyboard of the computer the editor runs
on). A headset is told what a terminal shows as it changes, as text
(up to some 60 times a second), and draws it over the node in its
own monospaced font. The node's text shows while there is no shell.

`:content-type code-scala` (or `markdown`, `plain`, `terminal`, …) sets it for
the selected nodes; `:content-type` alone tells it. The picker
(right click) shows it too and sets it with a click; `code` takes
the language typed beside it. The language is
a neovim file type, and the node is edited as one. A node keeps the
size of its source text whatever is shown in it; a picture that
does not fit is scaled down. The Swing client keeps the attribute
and shows every node as text.

**Text kept in a file.** A type may be followed by `:` and where the
text is, written as a link is: `code-kotlin:src/A.kt:10-20` (lines),
`markdown:README.md#Depth` (a section of a markdown file),
`plain:notes.txt` (all of it). The node then shows what the file
holds, read when the graph is opened. All the nodes of all the open
graphs that speak of one file share one unsaved copy of it: nodes
that hold the same lines, or lines of one another, show one text,
and an edit of one is seen in the others when the editor is left. An
edit is a change of the graph like any other: it is undone like any
other, and saving the graph (`:w`) writes the file — all of it, with
what other open graphs changed in it. When a save changes how many
lines there are, the line numbers of the nodes below them follow, in
every graph that is open. A graph that is not open is not corrected:
name a section where you can. A file that another program changed
under an edit is not written over; `:e!` takes its text again.
The unsaved copy lives as long as the application: `:e` and closing
a tab leave it, `:wa` saves every graph and every such file, and
quitting asks about those that are still unsaved. `gf` on a text file opens such a node, and `:w` there
writes the file. This is a first version.

A node being edited in the Electron client is never bigger than the
part of it that is on the screen; beyond that neovim scrolls.

### Levels of detail: `-` / `=`

A node can be a different thing at each level of detail: its label,
content type, size and colours are kept once per level (`label-l1`,
`fillcolor-l2`, … in the file; see [docs/file-format.md](docs/file-format.md)).
The editor shows one level, 0 at first; `=` goes one up and `-` one
down (`:detail-more`, `:detail-less`), and the status line says
`[detail 1]`. Nothing is taken from another level: a node that has
neither a label nor a content type at the level shown is not shown,
with its edges. What is edited, and a node that is made, is of the
level shown; positions and edges are the same at all levels. `=`
goes as far as one above the highest level anything is at, which is
how a new level is started. The level is of the view and is not
saved.

### Depth: `z`

A node can have a third coordinate, `z`, the third number of its
position (`pos="100,50,200!"`): how far it is behind the plane the
graph is drawn in, away from you, in the units of its position;
negative for a node in front of the plane. A node without one is in
the plane, and a graph without any looks as it always did. (An
attribute `z="200"` is read as well.)

Depth shows in 3D mode only (`:set 3d`), which turns itself on when
a graph with depth in it is opened or a node is given one; in 2D
mode every node is drawn as if in the plane. In 3D mode
the view looks straight at the graph. What is further away is drawn
smaller and nearer the middle of the view, what is nearer bigger;
what is behind you is not drawn, and an edge to it is drawn as far
as it can be seen. At zoom 1 you
are 1000 units in front of the plane you look at, so a node 1000
units behind it is half its size. Zooming is coming closer: the
wheel goes towards the node the pointer is on, or, over nothing, the
one nearest to it on the screen, whatever its depth, and past the
nodes in front of it; that node stays where it is. `examples/cube.dot` has one to try.

`:z 300` puts the selected nodes at that depth, `:z +50` and `:z -50`
move them by that much (away from you, towards you), `:z =-300` puts
them at a negative depth, and
`:z` alone tells where they are. A new node is made at the depth of
the plane you look at.

`:set 3d` turns 3D mode on for the graph shown, not for the other
tabs (`:set no3d` off, `:set 3d!` toggles; the status line shows
`[3d]`). In the Electron client a graph in 3D mode is drawn by the
graphics card (WebGL), each node a picture in space. In 3D mode the middle button turns the
view around the same thing: the node it is pressed on, or the one
nearest to the pointer, which stays where it is on the screen; `:view-front` turns it back. Nodes always face you. What
you do with the mouse then happens across the view as you see it:
a dragged node, grab, rotate, scale and mirror move nodes left,
right, up and down on your screen, which seen from the side is into
the graph; new and pasted nodes land in the plane you look at. In 2D
mode, the default, nothing turns the view (the middle button pans
it), and a view that was turned is shown from the front. The layout optimizer and the SVG
export know nothing of depth.

### Keyboard — `z`: view and folding

These act on the selection, or on the last active node when nothing
is selected.

| Key           | Action                                                      |
|---------------|-------------------------------------------------------------|
| `zz` / `z1`   | Centre the view on it (as `1`)                              |
| `z0`          | Fit the view to it (as `0`)                                 |
| `zt` / `zb`   | Move the view up or down so that it is at the top / bottom edge |
| `zs` / `ze`   | Move the view sideways so that it is at the left / right edge |
| `zf{motion}`  | Fold: hide the nodes the motion reaches                     |
| `zd{motion}`  | Unfold: show the nodes the motion reaches                   |
| `za{motion}`  | Toggle: unfold if the motion reaches a hidden node, else fold |
| `zi` / `zI`   | Toggle the children / everything downstream (`zal`, `zat`)  |
| `zo` / `zO`   | Toggle the parents / everything upstream (`zaL`, `zaT`)     |
| `zm` / `zr`   | Hide the selected nodes / unhide one level (`h`, `H`)       |
| `zR`          | Unhide everything                                           |

The edge commands keep the zoom and leave 40 pixels to the edge.
A motion is one of `t`, `T`, `l`, `L`: what it would add to the
selection is what is folded, hidden nodes included; whatever is
selected stays visible. Each fold is one undo step. As commands:
`:fold t`, `:unfold L`, `:toggle-fold select-closure-forward`.

Two modes change how hiding interacts with selection. Turn them on
with `:set <mode>`, off with `:set no<mode>`, toggle with
`:set <mode>!`. They apply to all tabs and show in the status bar
while on:

- `unhide` — `t`/`T`/`l`/`L` (and `G`) also step into hidden nodes,
  and unhide the ones they select (one undo step).
- `show-parents` / `show-children` — besides non-hidden nodes, also
  show selected nodes and every parent / child of a non-hidden or
  selected node. Display only; hide levels are untouched. A hidden
  node that is seen so is drawn faint, half as strong (not by the
  Swing client).
- `explore` (key `x`; `x` or Escape leaves it) — explore mode, for
  walking through a graph; of the graph shown, not of all tabs. Only the pinned nodes are
  shown, at first the selected ones (with nothing selected: of each
  connected part of the graph the nodes that fewest edges come into,
  so the root of a tree and all of a cycle), and their parents and
  children faint. A click pins: a
  faint node, or the children of a node that is pinned already
  (`:explore-show`). A right click folds: everything pinned below the
  node, but for what is also below another pinned node; with nothing
  to fold, the node itself (`:explore-hide`). A right click on a
  faint node does nothing. The pinned nodes are the mark `!`: `m!`
  pins the selection and nothing else, `m+!` / `m-!` add to it and
  take from it, `'!` selects them. Nodes that come to be seen are
  brought next to their neighbours, the faint ones on entering the
  mode too: each starts where the document has it from its parent
  and is brought to rest by the springs of the layout optimizer (the
  edges pull it in, the nodes around push it off) and by one that
  holds it to where it started from, direction and distance, but no
  harder than a fixed pull; so a node far away in the document comes
  near and stays in its direction. Several nodes to start from are
  themselves put together then, around the middle of the view: in
  columns side by side, each some 1500 units high, in the order they
  were in from top to bottom, with room to unfold between the
  columns; a faint node that is in the way of
  a new one is pushed aside, the pinned ones stay. All of that is
  only for as long as the mode is on: nothing is changed, saved or
  undone, the document is not modified by it, and leaving the mode
  shows what was shown before, every node where it was (also one
  that was moved by hand in the mode). A file saved in the mode has
  the nodes where the document does.
- `dynamic` — a node that becomes visible (by any of the above, or
  `H`) is moved next to its visible neighbours, left to right: a
  child in a column right of its parents, a parent left of its
  children, stacked vertically without overlapping anything visible.
  A real move: one undo step per batch, saved with the file.
- `suns`, `suns=<levels>` — the systems `o` settles the whole graph by
  are drawn (see `o`); with levels (`0,1,2`; 0 the nodes), only those,
  and `o` on the whole graph moves only those.
- `wall-scale=<n>` — with `walls`, how many millimetres of a wall a
  unit of the graph is: 1 (a node 150 units wide is 15 cm) unless set;
  0.5 shows everything half as big.
- `walls` — in a headset (`--vr`) the graph is on the walls of the
  room instead of before the eyes: flat as it is, bent around the
  room along the walls, its middle on the wall one looks at when
  this is switched on, at the height of the eyes; past the last wall it goes on over the first.
  It is made smaller to fit once around the room, but in explore
  mode, where it is as big as it is. Where it is on the walls is
  decided when it is first shown so, and when explore mode is entered
  or left. Doors and windows are not known of. Nothing in the graph
  is moved by this; a node taken and let go of somewhere in the room
  is put on the wall nearest to that place, as high as it was let go
  of. Needs
  walls: the headset's room must be set up (or `:room-mock`). The
  headset's page opened in a browser on the desktop shows the same
  with the walls as lines; the mouse goes around the room. What of
  the graph is behind something the headset has scanned of the room
  (furniture, a wall) is shown faint, with `walls` or without.
- `discard` — never ask about unsaved changes: opening, following
  links, reloading, jumping, closing tabs and quitting discard them.

The left and the right button of the mouse can be mapped like keys,
for when they are used on a node: `:map <RightMouse> zO` makes the
node the selection and runs `zO`. A mapped right button does only
that (no style picker). A mapped `<LeftMouse>` is run after a click,
and the button still selects and drags as ever. `:unmap` undoes it.

### Keyboard — styling

| Key        | Action                                                    |
|------------|-----------------------------------------------------------|
| `F`        | Copy style from the selected node to the "clipboard"      |
| `f`        | Paste the last-copied style onto the selected nodes       |

### Keyboard — history

| Key                   | Action                                           |
|-----------------------|--------------------------------------------------|
| `u` or `Ctrl+Z`       | Undo the last mutation                           |
| `U`, `Ctrl+Shift+Z`, or `Ctrl+R` | Redo                                  |

Undo covers every user-visible change: spawning / deleting nodes,
adding / removing edges, moving nodes (one step per drag, not per
pixel), typing into a node (one step per edit session, not per
keystroke), style / shape / size changes, and layout-optimiser runs.
Selection changes and view transforms are not tracked — they're
navigation, not data.

### Command line (`:`)

| Key            | Action                                                   |
|----------------|----------------------------------------------------------|
| `Tab`          | Complete: commands, `:set` settings (`:se` works too), `:help` topics, `:export` formats, paths for `:e` / `:w` / `:source` / `:browse` / `:cd`. Several matches: first the common prefix, then each Tab cycles |
| `Shift+Tab`    | Cycle backwards                                          |
| `Ctrl+D`       | List what Tab could complete to                          |
| `Up` / `Down`  | Command history                                          |

### Keyboard — files & export

| Key             | Action                                                 |
|-----------------|--------------------------------------------------------|
| `:open`         | Open into this tab — file chooser dialog               |
| `Ctrl+P`        | Pick a file with an external command (`:set picker=…`; default: xterm + fzf over `*.dot`), open it here (more lines: new tabs) |
| `Ctrl+O` / `Ctrl+I` | Jump back / forward (vim jump list): to where a `gf` / `g<letter>` / `:open` in this tab started, restoring selection and view |
| `Ctrl+S`        | Save — writes to the current file, prompts on first save |
| `Ctrl+Shift+S`  | Save As — always prompts                               |
| `F5`            | Reload the current file from disk (asks if unsaved). Undo brings back the graph as it was before the reload |
| `gf`            | Follow the selected node's `link` in this tab: open the file (and focus `:nodeid` if given), or show the directory's file tree |
| `g<letter>`     | Same for `link-<letter>`, unless `g<letter>` is mapped to something else. Without such a link it reports that and does nothing (it doesn't grab) — except `gx` / `gy` / `gs` / `gg`, which then grab |
| `gF`            | Same as `gf`, but into a new tab                       |
| `:browse [dir]` | New tab with a file tree of `dir` (default: working directory), its graphs and markdown files; `gf` on a node opens it. Files are one column right of their directory, subdirectories two; the tree is cut to the depth that fits the window, the last level being `name/ …`. The `help` node above the directory opens the key and command reference. `:tabnew` is `:browse` of the workspace |
| `:e <path>`     | Open a file in a new tab; a directory opens as its file tree (also from the open dialog and the command line) |
| `:file`         | Show the full path of the tab's file (or the browsed directory) |
| `:cd <dir>`     | Switch the workspace to `dir` (see [Workspaces](#workspaces)); bare `:cd` shows it |
| `:open-workspace` | New tab with the workspaces that have a session and the graphs open in each; `gf` on a workspace switches to it, on a graph opens it here |
| `Ctrl+E`        | Export graph as SVG                                    |
| `Ctrl+Shift+E`  | Export only the selected nodes as SVG                  |

On close, the editor prompts Save / Discard / Cancel for every
unsaved tab. Last-used open/save directories are remembered in
`~/.sanegrapedit.properties`.

The `Ctrl+P` picker runs `sh -c "<picker>"` in the background with
`SGE_DIR` (the workspace directory) and `SGE_FILE` set,
and opens the `path[:nodeid]` lines it prints (relative to
`SGE_DIR`). Opening a terminal is the command's job; a terminal's
stdout isn't the picker's, hence the temp file. E.g. st + television
in the init file:

```
set picker=out=$(mktemp); st -c sge-picker -e sh -c 'cd "$SGE_DIR" && tv --source-command "find . -name \"*.dot\"" > "$1"' sh "$out"; cat "$out"; rm -f "$out"
```

With i3, `for_window [class="sge-picker"] floating enable` makes it
a popup.

### Workspaces

The workspace is the working directory: the one the editor was
started in, until `:cd <dir>` switches it. Relative paths in the
`:` bar are relative to it, and each workspace has its own session.
`:cd` stores the open tabs in the session of the workspace being
left (asking about unsaved changes) and replaces them with those
of the new one. A workspace without open files in its session
starts with a file tree of its directory (`:browse`).

### Shared sessions

Windows are independent of each other, also when started in the
same directory. `sane-graph-edit --attach` instead joins the
*session* of the window already running in that directory (the
first one started there, if there are several): the two have their
own tabs, selection and view, but a file open in both is one
document — an edit, an undo or a save in one shows up in the other,
and undo steps back through the document's changes whoever made
them. While somebody else has the current document open too, the
status bar says `[shared]`.

If two changes collide, the one that names something already
deleted is refused, and where both touched the same node the owner's
stands; the refused change disappears with a message. When the
owner quits, the other windows carry on by themselves with what
they had.

The window that has a directory's session is the one that answers
at its socket, whichever client it is: a window of the Electron
client has it, is joined with `--attach` and is scripted with `ctl`
the same way, and a Swing window and an Electron window can share
one session.

### Scripting a running window (`ctl`)

A script or an agent can read and change the documents of a running
window (the first one started in the directory):

```sh
sane-graph-edit ctl documents            # what is open
sane-graph-edit ctl open other.dot       # show a file in a new tab (--here: in the current tab)
sane-graph-edit ctl state graph.dot      # nodes and edges by id, as JSON
sane-graph-edit ctl dot graph.dot        # the same as DOT, unsaved changes included
echo '{"setNodes": {"<id>": {"text": "New label"}}}' |
    sane-graph-edit ctl patch graph.dot  # change it; the window updates at once
sane-graph-edit ctl undo graph.dot
sane-graph-edit ctl save graph.dot
```

Run it in the window's workspace directory, or name it with
`--workspace <dir>`. A patch adds, changes and removes nodes and
edges by id; `sane-graph-edit ctl help` shows its form. The same
messages can be spoken directly over the session's socket (`ctl
socket` prints its path): JSON, one message per line — see
`docs/developer/architecture.md`.

### WebSocket (`--ws`)

For clients that cannot open a Unix socket, such as a browser,
`sane-graph-edit --ws <port>` also listens on a WebSocket (port 0:
any free one). It is off unless asked for, accepts connections from
this machine only, and only from clients that know the token in the
address. The window prints that address
(`ws://127.0.0.1:<port>/?token=…`) and keeps it, while it runs, in
the file beside the session's socket with `.ws` in place of
`.sock`. The messages are the same as on the socket, one per
WebSocket text message.

### A headset (`--vr`)

A first version, to look at a graph in a headset; nothing can be
changed from there yet. `make run ARGS="--vr 8765 graph.dot"` (the
Electron client) also answers a headset's browser at that port, and
says what to do next: with the headset on its USB cable,

```sh
adb reverse tcp:8765 tcp:8765
```

and then the address it prints, in the headset's browser, and there
the four-digit PIN it prints with it. The page
shows the graph of the tab that is shown in the window, as that
changes, and offers to enter with the room visible (passthrough) or
hidden. The grip of the left controller takes the graph in that
hand; a thumbstick pushed forward or back makes it bigger or
smaller. The right controller points: on the node it points at, its
trigger is the left button of a mouse and its grip the right one,
so a node is selected with the trigger, and in explore mode opened
with the trigger and folded with the grip. A node the trigger is
held on goes where the controller points until it is let go of: it
is put there, with the other selected nodes if it is one of them.
The nodes face whoever looks, from wherever. The left controller
points too: its trigger on a node that has links follows its link,
into a new tab; of several links it holds a menu open around the
node, and the one pointed at when it is let go of is followed. B
(on the right controller) goes back to
where a link was followed from, A forward again (Ctrl+O, Ctrl+I).
X on the left controller
goes into explore mode (from the node selected, or from the roots
of the graph) and out of it. The window's status line is shown on a
strip before and below the eyes: it stays where it is in the room,
and comes along when one turns or walks away from it. The
headset only places pictures the window makes of the nodes: it holds
nothing, and reloading its page loses nothing.

It is off unless asked for, answers on this machine only and only
to who gives the PIN (which sends the browser on to an address with
a long token in it); the cable is what lets the headset reach it,
and nothing is opened to the network. The PIN is new each time the
application starts, and after five wrong ones none is taken until
it is started again.

In the page opened in a browser on the desktop the mouse is the
controller: a click on a node is the trigger on it, a right click
the grip; the left button held on a node and moved takes the node
along. Held on nothing it takes the view along, as in the window.
The middle button or the right one (or the left one with Shift)
turns the eyes around the node last clicked (at first: the middle of
the graph, or of the room); the wheel brings them nearer to it or
further away. Nodes face whoever looks, on the walls too.

Keys pressed in the headset's page are the window's, as if pressed
there (graph keys, the `:` line, a node's editor, a terminal node's
shell), but only when the page is served on this machine alone: with
`--vr-lan` no keys are taken, since they can do all the editor can.
The browser keeps its own (F-keys, Ctrl+R/L/T/W/N, Alt). Only the
page this serves is let in, not another site's.

`--no-pin` (with `--vr`, on this machine only) asks for no PIN: for
trying things, when typing it at every start is a nuisance. Whatever
runs on this machine can then open the page; on the local network
the PIN is asked for all the same.

Without a cable: `--vr 8765 --vr-lan` answers on the local network
instead, for a headset on the same Wi-Fi. This does open a port to
that network: on the one address this machine has there, over TLS
(`https://192.168.…:8765`, as it prints), and still only to
who gives the PIN. The certificate is one the application makes
for itself with `openssl` and keeps in its configuration directory
(`vr-cert.pem`, `vr-key.pem`), so the headset's browser warns about
it once and has to be told to go on.

Where the headset knows the flat surfaces of its room (on a Quest:
after its space setup, and when its browser is allowed to see them),
the page outlines them with what they are (`wall`, `floor`, `desk`…)
and tells the window of them: `:room` lists them, with their sizes
and where they are. Nothing is laid out by them yet. Where it has
the scanned shape of the room too (furniture and all), the page
shows that as a faint net; the window is not told of it yet. Y on
the left controller asks the headset to scan the room anew, where
its browser can (once after entering).

The window draws that room too, in 3D mode, as outlines around the
graph (a unit of the graph is a millimetre; the middle of the graph
is 1.3 m above the floor). `:room-mock` makes a room up, with a
door, a window and a desk, to try this without a headset.

### Session persistence

The set of open file-backed tabs is persisted per workspace to
`$XDG_CONFIG_HOME/sane-graph-edit/workspaces/<dir>/session.properties`
(`$XDG_CONFIG_HOME` falls back to `~/.config`; `<dir>` is the
workspace path with `/` written as `%2F`) whenever
you open, close, or save a file — and one last time on window
close. On the next launch in that directory, those files are reopened
automatically and the tab that was active last time becomes active again.
The `init` file stays global.

Untitled tabs don't participate (there's no stable identifier to
restore them from), and the restore only reopens files *from
disk* — it does not recover unsaved edits. That's what the
autosave backup directory is for, and it is never touched
automatically by session restore.

### Autosave backups

Every 5 minutes, any dirty tab is written to
`$XDG_CACHE_HOME/sane-graph-edit/backups/` (defaults to
`~/.cache/sane-graph-edit/backups/`) as a plain DOT file with
the filename

```
<basename>.<hash>.<id>.dot
```

where `basename` is the source file's name without its `.dot`
extension (or `untitled` for tabs that have never been saved),
`hash` is a short SHA-1 prefix of the absolute source path (or
the tab's autosave UUID for untitled tabs) to disambiguate files
that share a basename, and `id` is a monotonically increasing
integer per `(basename, hash)` pair. Examples:

```
foo.3a1b9cd0.1.dot
foo.3a1b9cd0.2.dot
foo.3a1b9cd0.3.dot
untitled.8e7d2f4a.1.dot
```

**Backups accumulate and are never deleted automatically.** The
design goal is a safety net against your own saving mistakes: if
you overwrite the wrong file, save after a bad delete, or
clobber a graph with an empty document, the previous snapshots
are still sitting in the backup directory. Recovery is manual —
`ls ~/.cache/sane-graph-edit/backups/`, find the snapshot you
want, copy it into place. When the directory gets too large,
prune it yourself. The editor never auto-restores from this
directory; it's there when you need it, invisible when you
don't.

### Keyboard — tabs

| Key                | Action                                               |
|--------------------|------------------------------------------------------|
| `Ctrl+T` / `Ctrl+N` | Pick a file and open it in a new tab                |
| `Ctrl+W`           | Close current tab (prompt if unsaved)                |
| `Ctrl+Tab`         | Next tab                                             |
| `Ctrl+Shift+Tab`   | Previous tab                                         |
| `Ctrl+PageDown`    | Next tab (alias)                                     |
| `Ctrl+PageUp`      | Previous tab (alias)                                 |

Each tab owns its own graph, file path, undo history, and view
transform (pan / zoom). Closing the last tab clears it rather than
removing it — the window is never empty.

### Keyboard — clipboard

| Key        | Action                                                   |
|------------|----------------------------------------------------------|
| `Ctrl+C`   | Copy selected nodes (plus edges with both endpoints in)  |
| `Ctrl+X`   | Cut — copy and delete                                    |
| `Ctrl+V`   | Paste under the cursor                                   |

The clipboard is process-local and works across tabs: copy in one
tab, switch tabs, paste. Pasted nodes become the new selection so
they can be immediately repositioned or restyled.

## File format

Native format is a subset of [Graphviz
DOT](https://graphviz.org/doc/info/lang.html):

```dot
digraph g {
  n0 [label="Hello"; fillcolor="#fff3b0"; pos="100,200!"];
  n1 [label="World"; shape=box; pos="300,200!"];
  n0 -> n1;
}
```

Attributes the editor understands and round-trips:

- `label` — node text
- `pos` — node position (with the trailing `!` for fixed placement)
- `fillcolor` / `color` — background / foreground
- `fontsize` — maps to an internal scale factor
- `shape` — `box` or `oval`
- `marks` — persistent marks
- `fontname` — `monospace` for source code
- `link-<letter>` / `link` — links to other graphs / nodes (`g<letter>`),
  or to lines of a source file (`file.scala:42`, `file.scala:40-60`)
- `attachTo` / `attachSide` — glue a node to a side of another one;
  it then moves, hides and lays out with its owner

Graph-level settings, unknown node / edge attributes and comments
are preserved on open/save. The full specification is in
[`docs/file-format.md`](docs/file-format.md).

## Why "sane" graph edit?

Most graph editors assume you want to *compose* a diagram out of
prefab pieces on a palette. If you just want to write down "A leads
to B, B and C both lead to D, by the way D is the interesting one",
you end up fighting the editor. This tool skips the palette and makes
the common operations — spawn a node, connect it, label it, move it —
one keystroke each.

## Contributing / hacking

- [`docs/developer/architecture.md`](docs/developer/architecture.md)
  is the orientation document. Read it first.
- [`tasks/`](tasks/) holds the design plans for in-flight features.
- Top-level types (`Graph`, `Plotter`, `GraphView`, `GraphKeyListener`,
  `DotGraphLoader`) have KDoc pointing at the relevant sections.

## Licence

Copyright (C) 2026 Karel Tuček

Licensed under GNU GPLv3. 
