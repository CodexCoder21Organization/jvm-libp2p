| Configuration | Muxer | FIRST passed/total | Failures | Failure rate |
| --- | --- | --- | --- | --- |
| Unchanged isolated scenario, default JVM settings | Yamux | 1/1 | 0 | 0.0% |
| 20 repetitions per muxer, four concurrent pairs, 256 MiB, SerialGC | Mplex | 20/20 | 0 | 0.0% |
| 20 repetitions per muxer, four concurrent pairs, 256 MiB, SerialGC | Yamux | 20/20 | 0 | 0.0% |
| 10 repetitions per muxer, one pair, held responder binding, 256 MiB, SerialGC | Mplex | 0/10 | 10 | 100.0% |
| 10 repetitions per muxer, one pair, held responder binding, 256 MiB, SerialGC | Yamux | 0/10 | 10 | 100.0% |
