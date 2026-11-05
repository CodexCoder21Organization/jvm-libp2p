# Ownership invariants before further changes

| Resource | Creator / owner / closer | Terminal state and public observations | Re-entry / overlapping close |
|---|---|---|---|
| Dial UDP parent | transport bind / transport / child-settled owner or terminal loop hook | physical closeFuture complete, unregistered, close failure retained | parent waits all shutdown children; failed bind still releases parent |
| Late dial child | Netty connect / transport until publication / shared close owner | closed physically and close operation result retained | must include a child created after close inventory snapshot; current candidate drops rejected registration's result |
| Shutdown result | first close caller / transport private future / completion chain | exact first error plus all distinct secondary errors | repeated and callback re-entry share operation; returned observers cannot mutate it |
| Groups / threads | transport / transport / close after channel results | group terminal and actual owned threads exited | rejected dispatch physically closes owned channels on their loop before exit |
| Repeated successful lifecycle | dial/close callers / transport / ordinary parent cleanup and terminal close | no channel, group, thread or descriptor growth | soak uses concurrent public calls and a separate process for exact descriptor baseline |

No persisted state is owned by these transports. Public API tests observe actual Netty channels handed to caller callbacks and returned Connections; no private state reads or reflection.
