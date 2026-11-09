| Resource | Owner and creation | Terminal state and close | Caller, re-entry and concurrent rules | Public scenario |
| --- | --- | --- | --- | --- |
| Queued mux child creation | Mux session creates future before queuing initializer | Controller cancellation cancels pending creation; late created child closes | Initializer checks cancellation before installing protocol; close remains terminal | Cancellation before held publication, Yamux and Mplex |
| Provisional child hold | Connection hold creates one child use | Failed creation releases child use and owned hold | Cancelling one controller leaves sibling use and retained parent usable | Accepted sibling plus cancelled queued child, both muxers |
| Accepted protocol child | Caller owns published child | Explicit child close or parent close | Cancellation after successful controller completion has no effect | Existing accepted-child and promotion controls |
| Parent connection | Network owns physical lifetime | No uses permits coordinated surplus retirement | Cancelled child never closes an accepted sibling or stable parent | Existing 71-case network register |

No persisted records change. Restart behavior is unchanged.
