# FLIBP6 terminal checkpoint — incomplete

- [x] Fresh clones, full brief/docs and prior evidence read; both branches rebased and base overlap checked.
- [x] Snapshot-42 exact-source fresh build and downloaded hashes compared: all contents/POM identical, raw JAR differs only by ZIP timestamps.
- [x] Upstream PR pushed at 23d66d41; original public regression red/green and 54/54 adjacent first attempts; required bld-build and other Actions checks SUCCESS.
- [x] Expanded public coverage preserved: old product 13/15 intended failures plus two controls; fixed product 55/56, with an unsettled QUIC client UDP parent close future after group termination.
- [ ] Fix the new upstream owned-resource completion finding, then prove the same expanded test green. No publication or guessed fix was made.
- [ ] Package and publish the one authorized next unused coordinate; snapshot-45 was 404 at initial preflight and must be rechecked before use.
- [ ] Consumer candidate is rebased on 8bd31e5b and saved as consumer-candidate.patch; no consumer test attempt ran, no PR push was made.
- [ ] Diagnose both consumer cleanups from actual channel/group/thread observations; classifications remain unverified.
- [ ] Probe 30x and other 17 scenarios 30x single-attempt, then consumer push/final Actions XML.
- [x] Old Actions XML independently counted: 3658/3661 first passes, 3664 attempts, three second-attempt passes; all retry classifications unknown.
- [x] No main test edits; both PRs open; no merges/enqueues/deploys/check reruns/publications/handoff commands; local jobs and slots cleaned.
