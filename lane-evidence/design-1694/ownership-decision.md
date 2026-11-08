# Parent connection ownership

OBSERVED: The public cancelled-child probe fails on snapshot 46 with native FIRST 0/1. The failure is the physical parent close assertion after the parallel dial returns the stable parent. The prior no-child control at https://github.com/CodexCoder21Organization/UrlResolver/commit/8306c0562b37ae468d0d7b5b63fd9098cd310ab9 was native FIRST 1/1. Cancelling the provisional child's controller does not release a parent-lifetime reservation.

| Resource | Creator and owner | Caller action and terminal state | Existing public contract |
| --- | --- | --- | --- |
| Physical provisional parent | libp2p NetworkImpl starts the dial. Admission owns an unpublished resolver dial. Once in the network table, the network owns its eventual retirement. | An application connection callback can see it before its dial settles. | Network.connections and connection callbacks expose candidates. Observation alone does not retain them. |
| Surplus parent in the existing closure scenario | A separate public clientHost.network.connect call opens it. It is outside resolver admission. | Its dial ultimately returns the stable parent. | NetworkImpl.retainOneConnectionPerPeer requests coordinated retirement of the surplus connection. |
| Provisional RPC child on that parent | UrlResolver ping opens the child after scanning candidates. | The ping's cleanup cancels the controller and closes any late stream when another parent is selected. | reserveReusableConnection and openAdmittedChild reserve the parent; ConnectionSelection.createStream retains it too. All three actions establish parent-lifetime retention. |
| Stable selected parent | NetworkImpl selects it; its connect callers and resolver children may share it. | Continues to serve pings and ordinary child opens. | Network.connect reserves the exact parent before returning it. |
| Parent reservation | Network.reserveConnection changes local selection state. | Repeated success is idempotent. There is no token or release operation. | Reservation lasts until physical close, independent of child lifetime. A child-free interval is deliberately protected. |
| Accepted child on either parent | Its application caller owns it. | Closing another caller's provisional child must not close it. | The current permanent parent reservation prevents coordinated retirement; explicit close still closes every child. |
| Coordinated retirement | The physical dial initiator requests it; the acceptor authorizes it only if unretained. | Positive reply leaves both endpoints excluded until physical close; a failed request restores its own exclusion. | ConnectionSelection.rejectDuplicate refuses a locally retained parent. No public operation releases that retention or requests safe retirement of it. |
| Explicit parent close | A caller with authority over the whole parent, or host shutdown. | Terminal for the entire parent and its children. | Connection.close and Network.disconnect bypass retention. They are not child-aware release calls. |

INFER: Choose owner (b), libp2p, for the coordinated retirement mechanism. UrlResolver owns cancellation of its provisional child, but cannot turn that into authority over the entire physical parent. Option (a) is unsupported by snapshot 46. The existing scenario demonstrates why: the resolver did not open the surplus parent, and other callers may already share it. A snapshot of Host.streams is neither exclusive ownership nor an atomic check against another child opening.

INFER: A safe upstream design needs a separate provisional hold that can be released by its owner. Keep the current parent-lifetime reserveConnection contract for ordinary selected cached parents; that is what protects the gap between reservation and child creation in this PR. A provisional stream must instead acquire an individually owned hold before child admission, keep its live child protected, and release only its own hold when cancelled or completed. Duplicate selection must remember the surplus parent and attempt coordinated retirement when its last provisional hold ends. Accepted children at either endpoint must continue to prevent physical close. A selected provisional parent must be promotable to ordinary retention before its caller releases the provisional hold. This requires resolver use of the new provisional path: changing only child bookkeeping upstream cannot undo the resolver's explicit permanent reservation.

INFER: Do not implement either shortcut: explicitly closing the parent from ping cleanup, or clearing the idempotent retained flag merely because no child appears in a snapshot. The first can close another caller's accepted child. The second can close a parent after successful reservation but before its child opens, restoring the bytecode-fetch failure this PR fixes.

| New provisional-hold obligation | Public scenario required before its implementation |
| --- | --- |
| Duplicate with no provisional child retires | Keep the existing no-child control unchanged. |
| Cancelled provisional child releases its own hold | The new cancelled-child closure probe; existing duplicate-publication ping remains the consumer gate. |
| Accepted sibling stays open | Hold one genuine sibling in service, cancel the provisional child and settle duplicate selection; sibling must complete before the surplus parent retires. |
| Several provisional callers release independently | Two real callers share the same parent; cancelling one cannot release the other's hold. |
| Reservation-to-child interval stays protected | Existing testReservedRootRefusesRetirementBeforeChildOpen plus the other four reservation probes, unchanged. |
| Promotion establishes ordinary retention | Select the provisional parent, promote it, close the provisional child, then request retirement; the parent must still serve a later child. |
| Close remains terminal and re-entry cannot restore a parent | Close while a provisional open is pending; complete its source afterwards; no stream may be published usable and no negative retirement reply may restore eligibility. |
| Peer disagreement keeps accepted work | Both endpoints select different parents with real live children; neither parent may be closed until the corresponding provisional work ends. |
| Failed retirement restores only its own exclusion | Preserve the existing retirement reply-failure and ownership tests. |

OBSERVED: Hard rule 2 forbids Maven publication and hard rule 6 forbids whole-repository local suites. Work item 3(b) asks for both. They cannot both be performed in this lane. There is also no build.kts or kompile buildMaven rule in the supplied upstream checkout; it uses Gradle, defaults to develop and declares snapshot 41 in build.gradle. The requested publishing command therefore does not name an available upstream build rule.

INFER: This lane can settle ownership and preserve a public assertion-level reproducer, but cannot report a published snapshot 47, a qualified resolver dependency bump, or completed red/green/reversal. No product change is represented as complete. The next implementation needs the provisional-hold API plus its resolver use and authorization for the publication gate.
