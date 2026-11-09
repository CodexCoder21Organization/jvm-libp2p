# Connection-selection invariants before edits

| Resource | Creator and owner | Terminal state and cleanup | Re-entry, close and concurrent caller rule | Scenario |
| --- | --- | --- | --- | --- |
| Parent connection | libp2p network | Network owns physical close; a ping releases only its child and hold | An unselected parent stays eligible only while another owner needs it | testAbandonedProvisionalParentClosesAfterStableSelection; testPingRetainedParentBecomesReadyWhileDuplicatePublicationIsHeld |
| Provisional parent hold | One ping arrival worker | Selected live parent is promoted, every hold is released once | Each ping owns its token independently; callback signals cannot promote after cleanup | Held-duplicate ping scenario; upstream independent-hold controls |
| Provisional RPC child | One arrival worker per physical parent | Selected child transfers to caller; unselected negotiation ends before token release | Late stream availability must not start a new remote probe after this caller gives up the child | Exact three-probe assertion in held-duplicate scenario |
| Admitted parent and child | Pending dial controller, then ping caller | Caller cancellation ends its dial participation; selected child closes after probe | A provisional arrival never takes ownership from an unsettled admitted dial | testFreshPingDoesNotSelectParentOwnedByUnsettledAdmission; testPingDefersParentOwnedByIndexedAdmission |
| Cached parent reservation | Ordinary RPC/bytecode caller | Reservation retains parent lifetime; child is independently owned | Retirement exclusion is checked before opening, without network waits under the selection lock | Five bytecode/reservation cases in register |
| Arrival and physical-close subscriptions | Ping selection scope | All subscriptions close when either racer finishes | Callback only signals the caller; scope joins cancelled work before releasing resources | Both ping observer-count assertions in existing scenarios |

No persisted state is changed. Resolver close ends the same scopes; no restart migration is involved.
