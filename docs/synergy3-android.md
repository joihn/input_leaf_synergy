# Synergy 3 on macOS → Input Leaf on Android (experimental)

This branch adds experimental compatibility for **Synergy 3.7.2 / core 1.21.4**.
Use the Mac as primary and Input Leaf with **Shizuku** on Android. Synergy 3's
management service is separate from the keyboard/mouse protocol: Input Leaf
connects directly to the core on TCP **24800**.

The client uses the existing TLS certificate and fingerprint-pinning support.
Keep TLS and **Only allow verified computers** enabled. No Synergy license or
Mac private key needs to be copied to Android.

## Set up the phone

1. Install an APK built from this branch. A locally signed development APK may
   require uninstalling a differently signed release first; that removes its
   settings and generates a new client certificate.
2. Start Shizuku, authorize Input Leaf, and select Shizuku in Input Leaf.
3. In Input Leaf **Settings → This device's fingerprint**, copy the **complete
   SHA-256 fingerprint**. The shortened Settings summary is insufficient.
4. Select the intended Mac as primary in Synergy. Keep the phone on the same LAN.

## Register the phone on the Mac

Synergy 3.7's trust prompts are driven by its management-service peers. Input
Leaf does not run that service, so merely connecting to port 24800 does not
create a screen or bring up the usual Synergy trust prompt.

The helper below adds one screen and its public certificate fingerprint to
Synergy's existing settings. It preserves the Mac-to-Mac layout, primary,
other trusted computers, encryption settings, and license. It targets the
observed **global schema 17 / local schema 8** and refuses other schemas, custom
configuration overrides, and occupied screen positions.

From this repository, preview the registration (Python 3, standard library only):

```sh
python3 scripts/synergy3_setup.py \
  --name android-phone \
  --fingerprint 'PASTE_THE_FULL_PHONE_SHA256_HERE' \
  --side below
```

Choose `above`, `below`, `left`, or `right`. The preview prints the exact core
screen name to use in Input Leaf, for example `androidphone-c32a6d0e`, and the
Mac's fingerprint. The final instructions distinguish the Synergy display name
from the exact value to enter in **Input Leaf → Settings → Screen name**, and
are also printed after applying or rerunning an existing registration.
No files are changed without `--apply`.

After checking the fingerprint against the phone:

1. Quit the Synergy GUI and stop its background service. This briefly stops
   Mac-to-Mac sharing. Pausing sharing alone is insufficient because the service
   can overwrite settings. In Synergy 3.7.2, if the service is still running,
   its local stop endpoint is:

   ```sh
   curl --fail --request POST http://127.0.0.1:24803/v1/controls/stopService
   ```

2. Repeat the same helper command with `--apply`. It refuses to write while
   `synergy-service` or `synergy-core` is running. It creates a private backup
   directory containing the exact original `db.json` and `local.json`, then
   writes the registration. **Keep the backup private: it contains your existing
   Synergy license/settings.** A failed write restores both original files.
3. Reopen Synergy. It generates the screen layout and trusted-client file from
   the updated settings. You can subsequently move the phone in its layout editor.
4. In Input Leaf **Settings → Screen name**, enter the exact **Input Leaf screen
   name** printed by the helper, for example `androidphone-c32a6d0e`. This differs
   from the display name `android-phone` shown in Synergy's layout editor.
5. In Input Leaf **Settings → Connection security**, select **TLS only** (Auto
   also supports TLS). This is an app-wide setting, outside the Add Server dialog.
6. Add the Mac's LAN IP in Input Leaf's **Add Server** dialog. Enter only the IP
   address, without a port suffix: this APK automatically uses port **24800** and
   does not expose a port selector. Verify the Mac fingerprint in Input Leaf
   against Synergy's Security settings before accepting it.
7. Move the Mac pointer across the configured edge to enter Android.

Rerunning the helper with the same name and fingerprint makes no changes. If you
regenerate the phone's certificate, rerun with the new fingerprint and the same
name; its screen identity and position are retained.

To undo registration, stop Synergy's service, restore **both** JSON files from
the printed backup directory, then reopen Synergy. A full restore also reverts
any subsequent settings changes, so use it before making unrelated edits.

## Check the screen name and connection status

With Synergy running on the primary Mac, you can retrieve the current screen
name and live connection status without providing the fingerprint again:

```sh
python3 scripts/synergy3_setup.py --status --name android-phone
```

Use the display name currently shown in Synergy for `--name`. This command only
reads the local service API and prints the selected phone's status and setup
instructions. It also works if you have renamed the phone in Synergy; renaming
changes its core screen name, which must then be updated in Input Leaf.

### Why the Synergy tile can stay gray while input works

Synergy 3.7.2 tracks two independent connections:

- **Core:** the actual keyboard/mouse connection on port 24800. Input Leaf makes
  this connection, and Synergy correctly reports it as `connected`.
- **Management service:** Synergy's separate desktop discovery/settings-sync
  connection. Input Leaf does not implement this service, so Synergy reports
  `serviceReachable: false`.

The installed GUI colors a tile blue only if the management service is reachable
and the core is not disconnected. Consequently, an Input Leaf client can have a
working input connection and still have a gray tile. Hover the tile's Ethernet
status badge to see **Keyboard/mouse connected**; the separate link badge reports
management-service reachability. The helper's `--status` reports both explicitly.
For Input Leaf, an absent management link is expected and is not an input-sharing
failure. The helper labels it **not connected (expected for Input Leaf)**; use the
**Keyboard/mouse connection** line to check whether the phone is connected.

This is not a missing input keepalive. Changing the saved `misc.connected` flag
does not fix it: the service derives runtime status from live connections.
Management presence requires the separate WebSocket settings-sync protocol,
including messages signed using the Synergy serial, rather than an extra packet
on the input connection.

The minimal fix in Synergy's GUI would be to treat `core: connected` as active
regardless of management reachability, while retaining the separate service badge.
For a computer whose core status is `unknown` (viewed from a secondary Mac), it
can retain the existing fallback to management reachability. This branch does not
modify the installed Synergy application; the native tile color remains a known
UI limitation.

## What this branch changes

- Decodes signed 16-bit `DMRM` relative mouse packets used by Synergy, Input Leap,
  and Deskflow; retains the previously accepted 32-bit variant.
- Converts macOS Carbon keycodes (`virtual keycode + 1` on the wire) to Linux
  evdev codes before Shizuku HID injection. Detects the source from known keys
  and preserves evdev/X11 support. Before detection, unsupported characters use
  the existing keysym/text fallback. After detection, non-Latin layouts retain
  physical-key behavior; configure the Android hardware keyboard layout to match
  the Mac. Pressing a modifier or Space helps identify the source immediately.
- Waits for `DSOP` before reporting a completed handshake. `CIAK` only acknowledges
  screen dimensions and can be followed by `EUNK` (unregistered screen).
- Reports unregistered screens and protocol rejections directly instead of
  repeatedly reconnecting. Rejects unsupported protocol major versions.
- Uses server-neutral certificate and connection guidance in the UI.

## Validation and limitations

The installed Synergy 3.7.2 core was probed over TLS 1.3 using a temporary client
certificate. Its greeting was `Synergy 1.8`; it accepted the client's `1.6`
version and exchanged `QINF → DINF → CIAK`, then correctly rejected the deliberately
unregistered test screen with `EUNK`. A second live test used the actual Android
`InputLeapConnection` implementation and a temporarily borrowed offline configured
screen: it completed the full handshake and returned `Ok` over TLS (protocol banner
1.8). Each temporary fingerprint was removed afterwards; the existing Mac layout
and connected sessions were left intact.

The Android/JVM regression suite covers Synergy magic, mutual TLS, handshake
acceptance/refusal, relative motion, and Mac key conversion alongside the existing
Input Leap/Deskflow tests. The setup helper has standalone tests for preservation,
idempotency, certificate replacement, private backups, failed-write recovery,
and refusal while the service is running.

The user confirmed successful connection, screen-edge switching, and working
input on a Pixel 10 using this branch. Other Synergy 3.x versions, Windows/Linux
Synergy setup, clipboard/file transfer, and participation in Synergy's
management-service discovery are outside the verified scope. This is direct
input-protocol compatibility with an explicit desktop setup step.

## Development and reproduction

```sh
./gradlew :koverXmlReportDebugJvm :app:assembleDebug
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
```

An opt-in `Synergy3LiveTest` uses the real `InputLeapConnection` against a configured
TLS server. Set `INPUT_LEAF_SYNERGY_HOST`, `INPUT_LEAF_SYNERGY_SCREEN`,
`INPUT_LEAF_SYNERGY_FINGERPRINT` (server SHA-256), `INPUT_LEAF_SYNERGY_PKCS12`, and
`INPUT_LEAF_SYNERGY_PASSWORD`; optionally set `INPUT_LEAF_SYNERGY_PORT`.
Use a dedicated test screen and certificate already trusted by the server.
Then run:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*Synergy3LiveTest' --rerun-tasks
```

It is skipped in normal CI, changes no server settings, and refuses an unexpected
server certificate. Never commit private keys or real settings snapshots.

Interoperability references:

- [Maintainer discussion, issue #47](https://github.com/anasvhora284/input-leaf/issues/47).
- [Deskflow ProtocolTypes](https://github.com/deskflow/deskflow/blob/081f6478e654a112168879e67a41ce7d7e4ae3af/src/lib/deskflow/ProtocolTypes.cpp):
  packet layouts and protocol negotiation.
- [Deskflow ServerProxy](https://github.com/deskflow/deskflow/blob/081f6478e654a112168879e67a41ce7d7e4ae3af/src/lib/client/ServerProxy.cpp):
  `DSOP` completes the handshake; `CIAK` acknowledges screen information.
- [Deskflow OSXKeyState](https://github.com/deskflow/deskflow/blob/081f6478e654a112168879e67a41ce7d7e4ae3af/src/lib/platform/OSXKeyState.cpp):
  macOS physical buttons are Carbon keycodes plus one.
- Apple's installed HIToolbox `Events.h`: Carbon virtual keycode values.

Synergy-specific registration and trust behavior was inspected in the locally
installed 3.7.2 application; the helper is an independent implementation of the
observed settings format. No Synergy application code is included in this repo.
