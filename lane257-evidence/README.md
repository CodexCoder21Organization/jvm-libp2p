# Lane 257 verification evidence

Source: https://github.com/CodexCoder21Organization/jvm-libp2p/commit/f74ff5a0dccefbdfe035ee6a67395acddcd45d2c . Target base: develop at 5c08e2e226692e8f2b214d56fcf9ee405c61fd5e.

The final fixed run passed 160 focused cases across ten fresh JVM tasks, 2 original fixture cases, 199 neighboring cases, and 72 transport cases. Transport has one inherited disabled case. See r257-verification-counts.json and r257-verification-xml for native results, and r257-final-verification.log for the complete build output.

The final close tests fail 6/8 with only the close fix removed (ProtocolSelect restored from 6f79afa8); two pending-controller cases are passing controls. The original callback baseline fails 4/4. Full failure messages and stacks are in the matching logs and XML folders.

Prior lane252 evidence is included for attribution: r252-baseline3 reproduces duplicate headers and duplicate activation in 4/4 cases on unchanged develop. The rejected decoder-order candidate loses four framing bytes in its two reentry cases. Its two segmented controls also had an incorrect expectation in that early run; those expectations were corrected before the final guard fix and are not counted as evidence of a production defect.

The original lane246 fixture was copied unchanged for verification and removed from the PR branch afterward. SHA256: 07b4e339ff65bcdfd10947cbd66a3b6f142ee284f662837322997eed703c1c36.

No existing assertion or timeout was weakened. No merge, publication or deployment performed. CI monitoring belongs to the supervisor.
