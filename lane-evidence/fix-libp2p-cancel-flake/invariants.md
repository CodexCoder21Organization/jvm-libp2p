| Resource or event | Creator and owner | End state and caller observation | Ordering exercised |
| --- | --- | --- | --- |
| Provisional controller and queued child | Hold creates child; caller abandons controller; mux owns queued creation | Cancelled controller must never start responder; surplus parent closes at both ends | Held publication, cancel before releasing callback, assert zero after remote parent close |
| Later child on retained parent | Ordinary caller creates child; caller closes it after responder starts | Initiator and responder start independently; parent remains open | Hold peer binding after acceptance; initiator readiness succeeds while responder count remains zero |
| Responder-start signal | Scenario owns one future; callback completes it after incrementing counter | A successful wait orders the counter read and child close after the peer event | Complete in onStartResponder; wait before child close and exact count assertion |
| Accepted sibling | Original sibling scenario owns Echo child | Echo before and after cancellation remains correct; parent and sibling remain open | Existing two-muxer parameterized scenario retained, including both Echo assertions |
