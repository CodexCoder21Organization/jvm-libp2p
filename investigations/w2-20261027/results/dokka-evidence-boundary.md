# Dokka evidence boundary

The original wider Java17 build had Metaspace failures. W2 documentation-only and library-compilation/documentation probes pass under the original3GiB heap/512MiB Metaspace caps. Their JMX profiles approach the cap; that does not identify a retained root or prove a repair.

The native assemble/static/documentation probe https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40579988965 captured a heap before scheduler documentation. Its artifact is https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40579988965/artifacts/12768873050. The raw HPROF is kept only in the authenticated Actions artifact and is not committed.

The subsequent analysis https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40581419255 returned job success, but its dominator/histogram/root-path query reports contain argument errors. Those three reports are invalid evidence despite exit0. The thread report is present. No classloader root or Dokka fix is claimed. This was a diagnostic command-argument mistake; no production/test implementation was guessed from it. The original supervisor explicitly assigned this wider Java17 documentation investigation to separate follow-up.

The actual complete Linux/JDK11 native gate passed at https://github.com/CodexCoder21Organization/jvm-libp2p/actions/runs/40577902249:1445 total,1443 passed,0 failures/errors,2 existing skips, including documentation. The final owned-Kubo PR retains complete documentation; no task is dropped or bound raised.
