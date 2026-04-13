# Example graphs

Small DOT files for manual testing of the editor and its CLI.

- `simple.dot` — two nodes, one edge. Minimum viable graph.
- `marked.dot` — four nodes, two with persistent mark `A`, one
  with mark `B`. Use to test `'A` / `'B` recalls and
  `:export-selection`.

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
