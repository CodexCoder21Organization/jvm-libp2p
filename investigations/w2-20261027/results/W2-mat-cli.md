# Eclipse MAT headless commands for the hosted HPROF probe

## Status and scope

Research only. I did not run MAT, parse an HPROF, or change a repository/PR. The archive was downloaded from Eclipse and inspected as a ZIP to verify its runtime metadata and digest; I did not execute its binaries.

## Distribution and Java version

Use the official standalone Eclipse Memory Analyzer **1.16.1**, Linux GTK x86_64 archive:

- Archive: <https://download.eclipse.org/mat/1.16.1/rcp/MemoryAnalyzer-1.16.1.20250109-linux.gtk.x86_64.zip>
- Official Eclipse release listing (archive filename, platform, size/date): <https://download.eclipse.org/justj/?file=mat%2F1.16.1%2Frcp>
- Official previous releases page: <https://eclipse.dev/mat/download/previous/>
- SHA-256 of the bytes fetched directly from that official archive URL: `7c7bc3457e08bdcd187fdafb28573b00e8e9f56b46a872fb63dbbaf7f508f01e`

Eclipse's listing exposes the archive but no checksum sidecar. I checked the usual `.sha256`, `.sha512`, `.sha1`, `.md5`, `.asc`, `.sig`, and `SHA256SUMS` names in that release directory; they returned 404. Thus the digest above is one I computed from the official download, not an Eclipse-published checksum. The runner can pin those exact bytes with `sha256sum -c`:

```bash
MAT_URL='https://download.eclipse.org/mat/1.16.1/rcp/MemoryAnalyzer-1.16.1.20250109-linux.gtk.x86_64.zip'
MAT_SHA256='7c7bc3457e08bdcd187fdafb28573b00e8e9f56b46a872fb63dbbaf7f508f01e'
MAT_ZIP="$RUNNER_TEMP/MemoryAnalyzer.zip"
MAT_DIR="$RUNNER_TEMP/mat"
curl --fail --location --silent --show-error "$MAT_URL" --output "$MAT_ZIP"
printf '%s  %s\n' "$MAT_SHA256" "$MAT_ZIP" | sha256sum --check -
mkdir -p "$MAT_DIR"
unzip -q "$MAT_ZIP" -d "$MAT_DIR"
MAT="$MAT_DIR/mat/ParseHeapDump.sh"
```

I verified the extracted `mat/configuration/config.ini` says `osgi.requiredJavaVersion=17`, so this release supports Temurin 17. MAT 1.17.0 is not suitable for this constraint: its official release notes say the standalone minimum is now Java 21: <https://eclipse.dev/mat/1.17.0/noteworthy.html>.

## Commands

Set `HEAP` to the HPROF path. Each command is a separate invocation; MAT's parser builds/uses the adjacent indexes and each query invocation emits one standalone report.

```bash
HEAP='/path/to/dokka.hprof'
```

### Class-loader retained heap view and histogram

The grouped **dominator tree** is the built-in result that carries retained-size information grouped by class loader. The histogram grouped by class loader is useful alongside it for class/object counts and shallow sizes; its grouped root table does not itself expose a retained-size column. MAT's own tutorial documents grouping both histogram and dominator tree by class loader, and the dominator tree identifies retained memory.

```bash
"$MAT" "$HEAP" -keep_unreachable_objects \
  '-command=dominator_tree -groupby=BY_CLASSLOADER' \
  -format=txt -unzip org.eclipse.mat.api:query

"$MAT" "$HEAP" -keep_unreachable_objects \
  '-command=histogram -groupby=BY_CLASSLOADER' \
  -format=txt -unzip org.eclipse.mat.api:query
```

The first report is the class-loader grouped retained-size result. The second is the companion class-loader histogram. `-keep_unreachable_objects` preserves objects that are not reachable from ordinary GC roots, so the inventory is not silently limited to the reachable subset. Such objects naturally have no GC-root path.

### URLClassLoader paths to GC roots

Use MAT's multi-object query with the `java.net.URLClassLoader` class pattern and `-include_subclasses`. It handles multiple target objects in one query and includes URLClassLoader subclasses. This invocation requests the orientation from each loader toward GC roots; MAT merges common path segments. By design, this query reports shortest paths only, not every alternative reference path.

```bash
"$MAT" "$HEAP" -keep_unreachable_objects \
  '-command=merge_shortest_paths java\.net\.URLClassLoader -include_subclasses -groupby=FROM_OBJECTS_BY_CLASS' \
  -format=txt -unzip org.eclipse.mat.api:query
```

For a single loader when individual paths are needed, MAT's `path2gc` query accepts one heap object (address/OQL selection) and defaults to at most 30 paths. Example for an address copied from the OQL object report:

```bash
"$MAT" "$HEAP" -keep_unreachable_objects \
  '-command=path2gc 0x12345678 -numberofpaths=30' \
  -format=txt -unzip org.eclipse.mat.api:query
```

To run this for every loader separately, use one `path2gc` invocation per object address from the object list. The combined `merge_shortest_paths` invocation above is the simpler complete set-oriented first pass, with the shortest-path limitation stated above.

### Thread overview with context class loaders

```bash
"$MAT" "$HEAP" -keep_unreachable_objects \
  '-command=thread_overview' \
  -format=txt -unzip org.eclipse.mat.api:query
```

MAT's Thread Overview and Stacks query includes a Context Classloader column and, where the HPROF carries stack data, thread stacks/locals.

## Output and status

With `-format=txt -unzip`, MAT writes text query results into a `<heap-basename>_Query/pages/` report directory. The official query-report example names the file `Query_Command2.txt`; the `CommandN` suffix depends on the report sequence. Preserve/upload the generated `_Query` directory (or ZIP report if `-unzip` is omitted) as the analysis artifact. The three requested results are the dominator-tree query, merged-path query, and thread-overview query; the grouped histogram is supporting context.

MAT's official docs do not specify a numeric exit-code table. In the downloaded 1.16.1 distribution, `ParseHeapDump.sh` ends by invoking `MemoryAnalyzer ... "$@"` as its final shell command, so its status propagates. Treat exit code 0 plus the expected generated report as success; any nonzero code is a failed parse/query. This exit-code interpretation is shell behavior inferred from the shipped script, not a separate MAT numeric-code guarantee.

## Primary sources read

- Batch mode and accepted reports/options: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/tasks/batch.html>
- Single-query reports, `-command`, TXT output, OQL quoting, and output path example: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/inspections/query_report.html>
- Class histogram and grouping: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/querymatrix.html> and <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/gettingstarted/basictutorial.html>
- Class-loader analysis: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/tasks/analyzingclassloader.html>
- Dominator tree: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/concepts/dominatortree.html>
- Merge shortest paths and its shortest-path limitation: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/inspections/merge_shortest_paths.html>
- Single-object paths to GC roots: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/inspections/path_to_gc_roots.html>
- Thread overview/context classloader: <https://help.eclipse.org/latest/ntopic/org.eclipse.mat.ui.help/tasks/analyzingthreads.html>
- Heap object class patterns, subclass inclusion, and OQL/address selections: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/selectingqueries.html>
- Release archive listing and Java 17 metadata are the official Eclipse directory/archive noted above. Exact query command names and fields were cross-checked against tagged MAT 1.16.1 source: [HistogramQuery.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/inspections/HistogramQuery.java), [ThreadOverviewQuery.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/inspections/threads/ThreadOverviewQuery.java), [Path2GCRootsQuery.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/internal/snapshot/inspections/Path2GCRootsQuery.java), [MultiplePath2GCRootsQuery.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/internal/snapshot/inspections/MultiplePath2GCRootsQuery.java), [DominatorQuery.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/internal/snapshot/inspections/DominatorQuery.java), and [HeapObjectParamArgument.java](https://github.com/eclipse-mat/mat/blob/R_1.16.1/plugins/org.eclipse.mat.api/src/org/eclipse/mat/internal/snapshot/HeapObjectParamArgument.java).

## Evidence versus inference

**Observed:** The official Eclipse release listing includes the 1.16.1 Linux GTK x86_64 archive. Its downloaded archive SHA-256 is the value above. Its `config.ini` sets the required Java version to 17. The shipped launcher script delegates to `MemoryAnalyzer` as its final command. Official MAT docs describe batch `ParseHeapDump.sh`, one-query reports, TXT/unzipped output, class-loader grouping, merge-shortest-path behavior, and thread context-loader data.

**Inferred:** A hosted Linux runner with Temurin 17 and normal `curl`, `unzip`, and `sha256sum` tools can use this RCP archive headlessly. Success should be gated on process status 0 and the expected report directory/files; MAT does not document a comprehensive numeric status mapping.
