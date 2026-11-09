# When a Fix Does Not Fix Production

When a fix has been merged and deployed and the production symptom is still there — the page is still stale, the queue is still stuck, the error still appears — start here. The same guidance applies one step earlier, when you are about to ship a fix for something observed in production and want to know whether it will hold. A fix that ships and changes nothing is more expensive than it looks: it spends a review, a deploy, and a day of waiting, and it leaves everyone believing the problem is handled until someone notices that it is not.

The framing that governs everything else: **a fix that did not fix production was aimed at a cause nobody had demonstrated.** The diagnosis was plausible, the change was reasonable, the tests were green — and none of that connected the change to the symptom, because nothing had made the symptom happen on demand. The remedy is never a second, better guess. It is to stop shipping, make the real services reproduce the symptom in a test, and only then fix what the test shows. That is ordinary [test-driven development](../architecture/TESTING.md#test-driven-development) — write the failing test first, then make it pass — applied to a defect that was first seen in production, where skipping the failing-test step is most tempting and most costly. The three rules below are the three ways that reproduction most often goes wrong.

## Rule 1: Reproduce the Symptom by the Path Production Took

A production fix is not a fix until a test that starts real instances of the real services reproduces the symptom, and the fix turns that test from failing to passing ([Engineering Philosophy — Reproduce a Bug Before You Fix It](../PHILOSOPHY.md#reproduce-a-bug-before-you-fix-it), [Test End-to-End Against Real Dependencies](../PHILOSOPHY.md#test-end-to-end-against-real-dependencies)). That much is the ordinary bug-fix discipline. What production adds is a second requirement that is easy to miss: **the test must arrive at the symptom the same way production did.**

Most symptoms can be reached by more than one route. A reader can be told to start over because it went quiet for too long, or because it was busy and a size limit was reached; both end in the same message, and a fix for one does nothing for the other. A test that fails ten times out of ten feels like proof, but it only proves that *a* route to the symptom exists. If it is not production's route, the fix validated against it will pass every check and change nothing where it matters.

So before calling a test a reproduction, lay its conditions next to the production evidence and check them one by one. Was the component idle or busy in production — and in the test? Which limit, timer, or state was involved, according to the logs — and which one does the test trip? Does the recorded reason, error text, or log line match exactly? Then make the test assert that recorded reason, not just the visible symptom, so that a different cause producing the same symptom cannot pass for it. If production does not record enough to tell the routes apart, adding that record is the first change to ship ([Testing — Reproduce Production Conditions, Don't Fake Them](../architecture/TESTING.md#reproduce-production-conditions-dont-fake-them)).

## Rule 2: "It No Longer Reproduces" Is a Claim About One Path

During an investigation someone will run the production scenario against the current code, see that the failure no longer happens, and conclude the defect is already gone — perhaps an unrelated change removed it. Treat that conclusion with suspicion. What was actually shown is that *one sequence of events* no longer reaches the faulty code. The faulty code itself — the condition that makes the wrong decision — may still be sitting there, reachable by a different sequence that nobody tried.

When a defect is declared gone without a change that was aimed at it, do two things before relying on the declaration. First, look for the faulty condition in the current code: if the check that made the wrong decision still exists, the defect still exists, and only its doorway has moved. Second, hand the question to someone who did not do the original investigation and ask them to reach that condition by any route they can find — a different entry point, a restart at an awkward moment, a retry, an error handler. An independent reader asked to break a claim finds routes the original investigator, who was following the production timeline, never looked at. "Unreachable" is something to prove by search, not something to infer from one passing run ([Engineering Philosophy — A Refuted Hypothesis Is a Successful Outcome](../PHILOSOPHY.md#a-refuted-hypothesis-is-a-successful-outcome)).

The fix that follows should then target the condition itself, with one test per route that was found, so the defect is closed everywhere at once rather than one doorway at a time.

## Rule 3: Shrink the Threshold, Don't Inflate the Data

Many production defects only appear past some threshold: after a limit of so many records or bytes, after a timer of so many minutes, after a table grows beyond what fits in memory. The tempting way to reproduce them is to recreate production's scale in the test — write the hundreds of megabytes, wait out the timer, generate the tens of thousands of rows. This almost always goes badly. The test becomes slow, it fights the test machine's memory and deadlines instead of exercising the defect, and whether it fails starts to depend on the hardware it runs on.

A threshold is configuration. Find where it is set and make it small: a limit of twenty records instead of twenty-five thousand, a clock the test advances by hand instead of ten real minutes. The code path taken when the limit is crossed is the same at twenty as at twenty-five thousand, and the test now reaches it in milliseconds, on any machine, every time. Often an existing test already drives the path at small scale and needs only one more assertion to expose the defect.

A reproducer that fails only at production scale is calibrated to a machine, and its difficulty is itself a signal that the exact triggering condition has not been pinned down yet ([Testing — Never Calibrate a Reproducer to a Machine](../architecture/TESTING.md#never-calibrate-a-reproducer-to-a-machine)). Set the threshold small, force the exact ordering of events that crosses it, and — as in Rule 1 — assert the recorded reason so the test cannot pass for the wrong cause. If the threshold cannot be configured from a test, making it configurable is a legitimate first change.

## What Is Never the Answer

- **A second fix shipped on a better theory.** If the first fix was a guess, the second is too until a reproduction exists. Stop the deploys and build the test.
- **Verifying in production.** Deploying to find out whether a fix works turns users into the test and makes every wrong guess cost a full deploy cycle.
- **A test that reproduces the symptom some other way.** It validates a fix for a different defect. Rule 1 exists because this failure looks exactly like success.
- **Closing the issue because it stopped reproducing.** Without a change aimed at the defect, that is Rule 2's unproven claim.
- **Raising limits, timeouts, heap, or hardware so the production-sized test can run.** That is tuning the machine to fit the test; shrink the threshold instead ([Engineering Philosophy — Fix the Root Cause, Never Mask the Symptom](../PHILOSOPHY.md#fix-the-root-cause-never-mask-the-symptom)).

## What a Proper Resolution Looks Like

1. Production evidence that names the route: the log line, recorded reason, or state that shows *how* the symptom was reached, not only that it was.
2. A test against real instances of the real services that reaches the symptom by that route, fails on unchanged code, and asserts the recorded reason.
3. Thresholds set small through configuration, events forced in the exact order required, time driven by a test clock — so the test fails every time on every machine.
4. A search, by someone independent, for other routes to the same faulty condition, with a test for each one found.
5. A fix that turns all of those tests from failing to passing, with no limit, timeout, or assertion loosened to get there.
6. After deploy, a check of the original production symptom itself — and a statement, written before the deploy, of what that check must show.

## Further Reading

- [Engineering Philosophy](../PHILOSOPHY.md) — [Reproduce a Bug Before You Fix It](../PHILOSOPHY.md#reproduce-a-bug-before-you-fix-it), [Test End-to-End Against Real Dependencies](../PHILOSOPHY.md#test-end-to-end-against-real-dependencies), [A Refuted Hypothesis Is a Successful Outcome](../PHILOSOPHY.md#a-refuted-hypothesis-is-a-successful-outcome), and [Fix the Root Cause, Never Mask the Symptom](../PHILOSOPHY.md#fix-the-root-cause-never-mask-the-symptom)
- [Testing — Test-Driven Development](../architecture/TESTING.md#test-driven-development) — the write-the-failing-test-first workflow this playbook applies to production defects
- [Testing — Tests Must Be Hermetically Sealed](../architecture/TESTING.md#tests-must-be-hermetically-sealed), [Reproduce Production Conditions, Don't Fake Them](../architecture/TESTING.md#reproduce-production-conditions-dont-fake-them), [Bug Fix Testing Workflow](../architecture/TESTING.md#bug-fix-testing-workflow), and [Never Calibrate a Reproducer to a Machine](../architecture/TESTING.md#never-calibrate-a-reproducer-to-a-machine)
- [Resolving Slow Services](SLOW_SERVICES.md) and [Resolving OutOfMemoryErrors](OOM.md) — the sibling playbooks for two symptoms whose fixes most often fail to hold when shipped without a reproduction
- [Delegating Engineering Work](DELEGATION_BRIEFS.md) — how to carry these rules into the written instructions when someone else does the reproduction and the fix
