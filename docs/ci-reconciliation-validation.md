# Native convergence recovery — 2026-11-09

CI run [42532879905](https://github.com/slashmili/meshboard/actions/runs/42532879905)
failed only in the iPad four-peer convergence scenario at `34aceff`. The failure
artifacts show five objects on desktop and both browsers, but four on iPad: the
`desktop` edit was missing. All four participants still reported three connected
peers; native errors were null. Other iPad scenarios, including images, passed.
The logs do not identify where the update stopped being propagated or applied.

Native controllers previously requested state vectors only when admitting a
connection. They now repeat that request on their existing ten-second session
maintenance timer. Responses use the existing Yjs sync protocol and authenticated
encrypted channels. This repairs missed application updates without reconnecting,
increasing test timeouts, relaxing authentication or replay checks, or relaying
board content through signaling. Requests repair delete-only differences too,
even when the state vector itself has not changed. Timer lifetime remains tied
to the session; leaving/retrying cancels the existing maintenance task.

The new mixed-peer regression suppresses a browser's upstream document-update
notification before encryption. The document and connection remain alive and
cipher counters stay intact. It requires the native peer to recover the missing
insert, then a missing deletion, through periodic state-vector exchange. The test
fails without the desktop fix (missing insert after 25 seconds) and passes with
it. The same scenario is wired into desktop, Android and iPad suites. An Apple
controller unit test separately exercises repair of inserts and delete-only
updates through the real yrs binding.

This is a tested recovery fix for persistent divergence, not proof of the original
missed-update cause. Re-running the iPad CI scenario is still required; Apple
runtime execution is unavailable on this Linux host. Connector work is retained
separately in the stash named `wip: attached connectors before image CI fix`.

Local verification: workspace typechecks, 66 desktop/common/JNI tests, all 18
web/desktop interoperability scenarios, Android debug/test APK builds, and the
live web/Android missed-update regression passed. The desktop fault-injection
baseline failed before the repair and passed afterward. iPad CI and the added
Apple-specific controller test remain unexecuted locally.
