| Resource | Creation / owner | Close / terminal state | Public observation / re-entry |
| --- | --- | --- | --- |
| Settled parent | First successful connect, owned by host | Explicit close, transport close or host stop | Selected parent remains usable after duplicate retirement |
| Unselected parent without a child | Concurrent provisional dial, owned by host | Initiator closes after remote retirement approval | Connection callback may hold publication without selecting parent; later connect returns settled parent |
| Parent with a child or reservation | Caller opens child or reserves exact parent | Parent lifetime retention until explicit close, transport close or host stop | Closing or cancelling child does not release parent retention |
| Publication gate | Test holds application callback | Always released in finally | No child is opened on the unselected parent in the unused-parent scenario |
