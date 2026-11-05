# F93b final checkpoint — unfinished, no publication

- [x] Fresh clone, R93a first, README/FORK and three named policy documents read; rebase onto unchanged origin/develop436997d5, no equivalent fix.
- [ ] Finding1 every-path physical parent close: graceful terminated-loop red/green/red0/1,1/1,0/1; native immediate-rejection counterexample remains0/1; direct native close still registered0/1. Partial, not counted fixed.
- [x] Finding2 repeated failure and child-before-parent ordering: red/green/red0/4,4/4,0/4; private owner before callbacks, independent observers.
- [x] Finding3 secondary reporting: red/green/red1/2,2/2,1/2; suppression-disabled neighbor red1/2,green2/2. All individual channel/parent/group stages examined.
- [x] Gap1 repeated settled and pending shutdown public cases added and proven red first.
- [ ] Gap2 partial: secondary parent and suppression-disabled reporting covered; multi-child/group failure tests remain.
- [ ] Gap3 partial: graceful terminated-loop case fixed; actual native rejection remains failing; full-queue route not completed.
- [ ] Gap4 public reentrant dial-visitor shutdown reproduction is red1/1; no implementation fix adopted.
- [ ] Gap5 large inventory/churn/heap-descriptor-thread soak not implemented.
- [ ] Full independent test-comprehensiveness and adversarial reviews not completed; no clean-review claim.
- [ ] 30x single-attempt gate not run:0/810; final targeted native JUnit register27/27, zero retries/skips.
- [ ] Final-head Actions XML not inspected; runs pending at final read (links in final-actions-runs.json).
- [x] Completed product changes pushed from product checkout to flibp5-acceptor-shutdown-snapshot41; exact b7eecc3a0498f829088fa35a083790bb24199bc2 verified against remote.
- [x] Description opening why retained, snapshot45 publication/SHA256/FLIBP8 link corrected; current incomplete state explicit.
- [ ] New publication blocked; none attempted,45 untouched. Needs native correction and authorized dependency artifact before remaining gates and one library publication.
- [x] Process/slot cleanup check: no owned JVM/wrapper, no F93b slots, no subagents/timers; no handoff-cli or merge/enqueue/close/deploy used.

## Invariants
|Resource|Creator/owner/closer|Terminal state|Caller and reentry rule|Evidence|
|---|---|---|---|---|
|Dial UDP parent|Transport|Closed, deregistered, physical future done|Loop rejection must not strand parent|Graceful case passes; native rejection fails, still unfinished|
|Close ordering|Transport|Every child operation settled before parent cleanup|One private shutdown owner before callbacks|F2 pending-overlap red/green/red|
|First shutdown result|Transport/host|Original success/failure retained|Caller mutation affects observer only|F2 settled-repeat red/green/red|
|Combined failures|Transport/host|Every distinct outcome retained|Primary plus suppressed or aggregate|F3 red/green/red plus disabled-suppression red/green|

There is no persisted resource state; restart-from-record does not apply.
