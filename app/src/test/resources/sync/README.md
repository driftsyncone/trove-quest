# Sync wire fixtures

One JSON file per message type and direction of the Trove sync protocol (`/api/sync/ws`).
Written for the protocol as of server `master` 341a09b / app `main` 8956842, plus the solo-use `resume` message and `status.resume`, the VacuGlide controls (`status.vg`, `vg`, `vg-ack`), script variants (`status.script`, `vg` op `script`), and the session timer (`status.timer`, `timer` ops, `timer-ack`, the `timer` warn event, `pause-all` reason `timer`, `hello.serverNow` for the timer's clock offset).

- `p2s-*`: player (headset) to server. The server must accept them; the app must encode exactly this for the equivalent state.
- `s2p-*`: server to player. The server must produce exactly this (only `hello.id` is random); the app must decode it to the expected value.

The same files live in both repos and must stay byte-identical:

- server: `test/fixtures/sync/` (source of truth; tests in `test/contract.mjs`, `npm run test:contract`)
- app: `app/src/test/resources/sync/` (`SyncContractTest`)

`SHA256SUMS` lists every JSON file. Both repos' tests check their files against it, so a changed fixture fails
until the list is regenerated, and a list copied to the other repo fails there until the files are copied too.

Changing the protocol:

1. Edit the fixture(s) in the server repo, then `test/fixtures/sync/sync-fixtures.sh push ../trove-quest`
   (rewrites `SHA256SUMS` and copies everything to the app's test resources).
2. `sync-fixtures.sh check ../trove-quest` shows any drift between the two checkouts.
3. Update the app's expectations in `SyncContractTest` and the server's in `test/contract.mjs`.

Numbers are compared numerically (the app writes `1.0`, JS writes `1`).

`s2p-hello` carries a made-up `serverNow` and `s2p-status-timer` a made-up `endsAt` (the server writes its clock + the timer); both sides compare everything else exactly and only the type of those two.

`s2p-status-vg-connected` is the one fixture the server cannot produce in the test (it needs the real driver); the contract test checks its fields and types against a produced status instead.
