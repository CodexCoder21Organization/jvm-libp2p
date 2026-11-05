# F93b plan
- [x] Read R93a review and verify current develop/PR identities (no overlap).
- [ ] Finish required whole-document reading and reproduce finding 1.
- [ ] Finding 1: public terminated/rejected-loop parent test red, choke-point fix, green, removed-fix red.
- [ ] Finding 2: settled and pending repeated shutdown red, single private owner fix, green, removed-fix red.
- [ ] Finding 3: complete failure reporting red, fix, green, removed-fix red.
- [ ] Five review coverage gaps, each red first: overlap, combined failures, actual native rejection, pending dial, inventory/churn.
- [ ] Independent test and adversarial review, whole touched files and named documents.
- [ ] All changed/new and 18 PR cases 30x single attempt, at most two JVMs.
- [ ] Product push; Actions final-head per-attempt XML.
- [ ] One unused snapshot publication, all-entry/POM byte verification and provenance; description update.

# Invariants
|Resource|Creator/owner/closer|Terminal state|Caller/reentry/close rule|
|---|---|---|---|
|Dial UDP parent|Transport creates, owns, closes|Physical close future done, closed, deregistered|Cleanup survives rejected and terminated loop; no caller mutation can stop it|
|Close ordering|Transport owns child operation and parent|All child operations settle before parent close|Repeated/reentrant call shares same private shutdown owner|
|First shutdown result|Transport/host|Retained success or original failure|Independent observers; repeated call cannot replace result|
|Combined failures|Transport/host|Every distinct error retained|Primary preserved, later errors suppressed without hiding any|
No persisted records exist, so restart from storage does not apply.
