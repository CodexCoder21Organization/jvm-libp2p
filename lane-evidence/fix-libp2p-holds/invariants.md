# Provisional parent hold contract

| Resource | Creator, owner and release | Terminal and concurrent behavior | Public scenario |
| --- | --- | --- | --- |
| Ordinary reservation | Network caller; parent lifetime | Never cleared by provisional release; explicit close remains terminal | Existing reservation tests unchanged |
| Provisional hold | Network creates one owned token for one caller | Release is idempotent and affects only that token; closed tokens cannot open or promote | Independent callers and repeated release |
| Provisional child creation | Hold owns pending admission before delegating to mux session | Controller cancellation or negotiation failure closes only that child and releases the hold; late child must close | Cancellation before negotiation and late completion |
| Accepted child | Stream caller owns child; selection tracks its lifetime | Releasing the hold leaves accepted children open; retirement waits for the last protected child | Accepted sibling on surplus parent |
| Promotion | Caller selects this parent and establishes ordinary retention | Promotion must order with close and retirement; release cannot undo it | Promote then close child and request retirement |
| Duplicate marker | Network records selection of another settled parent | Last provisional release initiates coordinated retirement; neither side closes active children | Two real parents, cancelled child, later settled-parent child |
| Incoming child | Peer creates child, receiver owns its admission | Retirement and admission must share an ordering; control stream does not retain its own parent | Peer disagreement and retirement reply controls |
| Parent close | Connection owner or host | Terminal even when a late child or earlier negative retirement response completes | Existing close and reply-ownership tests plus pending hold close |

The selected API must preserve reserveConnection unchanged. A provisional caller must use the hold for child creation, because ordinary muxer creation deliberately retains the parent for its lifetime. Explicit parent close is never a substitute for release.
