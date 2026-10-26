# Existing BuildTest artifact reader

This investigation helper reads an artifact from an existing run through the public `url://buildtest/` RPC connection. It does not submit a build. Bytes are written to a temporary file in the output directory and moved into place only after the reported size matches.

The BuildTest CLI checkout declares the connection coordinates used here: `foundation.url:resolver:0.0.673` and `foundation.url:protocol:0.0.399`, along with its URL protocol runtime dependencies. Its own Maven coordinate, `buildtest.cli:buildtest-cli:0.0.3`, did not resolve from kotlin.directory during this investigation, so the helper compiles from the direct dependency coordinates declared by that checkout.

The cached manager artifact reader uses `getArtifactInfo` to read `hasArtifact`, `isDirectory`, and `sizeBytes`. For files up to 5 MiB it calls `getArtifact`, which returns the file in the Base64 `artifact` field. Larger files use `getArtifactChunk` with `runId`, `offset`, and `length`; each response carries Base64 `data` and numeric `size`. The helper follows that public response contract and uses 4 MiB chunk requests.

The run `f0f67dc5` has not been queried by this helper. After parent review, the capped read-only command is:

```bash
timeout 300s bash investigations/V71/fetch_existing_buildtest_artifact.bash f0f67dc5 investigations/V71/V71-native-reports.jar
```

The helper's `--help` path compiles the source and prints usage before opening the URL protocol connection. The output file must not already exist.
