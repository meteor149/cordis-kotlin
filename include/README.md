# Include and layered composition

`composeEntries(base, layers)` interprets ordered patch layers independently of files and
running Contexts. It returns detached entry/configuration containers, source origins and
structured diagnostics. Opaque application objects in `Any?` configuration are borrowed;
portable applications must use data values rather than mutable host objects.

Later patches can address earlier inserted entries, including group children. IDs must be
unique within the composed tree. Configure replaces a whole value and FieldPatch distinguishes
explicit null from an unchanged field. `name` guards the original module identity;
`replacement` changes it explicitly. `remove` removes a target subtree.

Include delegates initial loading, same-path configuration changes and refreshes to this
same interpreter. `IncludeConfig.layered` disables runtime tree write-back to the source
file, preserving the distinction between source layers and their expanded result.

Use `CompositionResult.requireValid()` for strict preflight. Include retains warning-based
handling for legacy invalid targets. Tree transaction and committed file publication are
separate concerns; the legacy EntryGroup update is not an atomic application boundary.

Validation: `gradlew.bat :include:jvmTest` (JDK 21).
