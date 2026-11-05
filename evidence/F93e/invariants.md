# Ownership table before further lifecycle changes

| Resource | Creator / owner / closer | Terminal state and public observations | Re-entry / concurrent close / restart |
|---|---|---|---|
| QUIC dial UDP parent | transport bind / transport / child-settled cleanup or loop terminal hook | physically closed, closeFuture completed, unregistered; exact close failure retained | close owner published before callbacks; parent waits global child barrier after shutdown; no persistence |
| QUIC child created by dial | Netty bootstrap / transport until publication / shared original channel close | physical close future complete; operation error observable; no publication after stop | connect can finish after snapshot; this remains unproved and not fixed by assigning child after register |
| Original transport shutdown | first caller / transport atomic reference / terminal completion | all observers retain original cause, distinct secondary causes; observer mutation isolated | repeated/reentrant calls share private owner; no persisted state |
| Ordinary child close result | channel lifecycle / close-operation attribute / physical channel close | original result retained even after removal and explicit retry | parent cleanup joins original outcome; shutdown joins all sibling original outcomes |
| Live groups / threads | private transport / transport / close after channels; shared TCP worker survives | private termination futures complete, actual threads exit | rejected-dispatch hook closes physically on owning loop; no restart |
| Successful dial history | transport / current DialParent / release callback | completed successes do not keep parent inventory history | successful child+parent cleanup releases owner; failure information must remain reportable |
