# Example graphs

Small DOT files for manual testing of the editor and its CLI.

- `index.dot` — the way around: a node per example, the built-in
  help, the manual and the file format. Select one and press `gf`;
  `Ctrl+O` goes back. Each example has an `examples` node that
  leads back here.
- `simple.dot` — two nodes, one edge. Minimum viable graph.
- `marked.dot` — four nodes, two with persistent mark `A`, one
  with mark `B`. Use to test `'A` / `'B` recalls and
  `:export-selection`.
- `cube.dot` — eight nodes with a depth. `:set 3d`, then the middle
  button turns it.

## Try them

```sh
# Open interactively
./gradlew run --args=examples/simple.dot

# Or via the fat jar
java -jar build/libs/sane-graph-edit-*-all.jar examples/simple.dot

# Headless export
java -jar build/libs/sane-graph-edit-*-all.jar \
    examples/marked.dot -e "'A" -e ':export-selection /tmp/a.svg<Enter>'
```
