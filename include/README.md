# Cordis Include and composition

Composition copies preserve typed `JsonElement` containers rather than coercing JSON objects
and arrays to ordinary maps/lists. Nested JSON values are detached from caller-owned containers
on insertion, configuration replacement and repeated compilation.

`composeEntries(base, layers)` is the detached, ordered interpreter shared by Include and
offline tooling. Entries inserted by earlier operations are immediately addressable. Results
contain source diagnostics and field origins; `requireValid()` rejects any diagnostics.
Configure distinguishes explicit null from an unchanged field. `name` guards the original
module identity; `replacement` changes it explicitly and `remove` withdraws a subtree.
Configuration replaces rather than deep-merges. Opaque runtime objects remain borrowed;
portable callers supply data values. Named profiles and application persistence remain host-owned.

`PatchOptions` supports insertion, field/context updates, guarded implementation replacement,
removal and movement. Insertion targets `id` as the parent group (null selects root) and accepts
an optional `position`. Movement targets `id` as the entry, with `parent = changeTo(groupId)`;
`changeTo(null)` selects root. A position without a parent change reorders within the current
parent. Null position appends. Indexes apply after removal, positive positions are zero-based,
negative positions count from the end, and out-of-range positions clamp like JavaScript splice.
Groups retain their children. Missing/non-group parents and self/descendant cycles are diagnosed
before the offending move changes the tree. Insert/remove cannot simultaneously move an entry.

Include validates and applies candidates through Loader tree transactions. Cross-parent moves
retire/recreate the branch in its new context; same-parent order changes retain resources.
The host owns durable publication and must treat failed restoration as a degraded runtime.

Initial Include loading, same-path config updates and refresh share this interpreter.
`IncludeConfig.layered` prevents writing the expanded runtime tree back over source layers.
Parsed content publishes only after candidate application succeeds. Legacy invalid patch
targets retain warnings; hosts use `requireValid()` for strict offline preflight. Direct
EntryGroup update remains a best-effort low-level API.

Run `./gradlew :include:jvmTest :loader:jvmTest` (Windows: `gradlew.bat`, JDK 21).
