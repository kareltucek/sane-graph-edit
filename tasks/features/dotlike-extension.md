# Save as `.dotlike` instead of `.dot`

> **Status: backlog**

Using `.dot` as the file extension conflicts with Graphviz DOT on
systems where file associations matter. A distinct extension like
`.dotlike` or `.sge` would make the editor's files unambiguous.

**Considerations:**
- The parser and serializer don't care about the extension — it's
  just a file-dialog filter and a default-name convention.
- Existing `.dot` files should still open fine (the Open dialog
  can accept both `*.dot` and `*.dotlike`).
- The DOT format itself doesn't change — only the extension.
