# Dial for Roon (Android)

An Android app that registers itself as a **Roon extension** and presents a
round dial: a volume ring you sweep with your thumb, album art in the middle,
transport underneath. It talks to the Roon Core directly — no bridge, no
Docker, no Node host, no companion service.

| Now playing | Adjusting volume | Waiting for approval |
|---|---|---|
| ![now playing](docs/preview/now-playing.png) | ![adjusting](docs/preview/adjusting.png) | ![waiting](docs/preview/waiting.png) |

*(Rendered from the real view code — see "Verification" below.)*

## Install

Sideload `dial-for-roon-<version>.apk` (Android 8.0 / API 26 or newer). It is
signed with the standard Android debug key, so it installs alongside anything
else but will not update in place from a differently-signed build.

Then, once:

1. Open the app on the same Wi-Fi as your Roon Core.
2. In Roon: **Settings → Extensions → Enable "Dial for Roon"**.

The pairing token is stored per Core, so approval only happens the first time.

## Use

| Gesture | Action |
|---|---|
| Sweep the outer ring | Volume, quantised to the output's own step, one haptic tick per step |
| Tap the zone name | Zone picker |
| Tap the volume readout | Mute / unmute |
| Tap ⏮ ⏯ ⏭ | Previous / play-pause / next |
| Long-press the middle | Menu: zone, reconnect, re-discover, manual Core address |

A full sweep of the ring covers the output's whole range in about 320° of
rotation, whether that range is in dB or arbitrary units.

## How it works

Two protocols, both spoken natively in Kotlin:

- **SOOD** (`roon/Sood.kt`) — Roon's UDP discovery. A query goes to
  `239.255.90.90:9003` and to the subnet broadcast address; Cores answer with
  their `unique_id` and the port their extension API listens on. Android
  filters multicast out of userspace, so the app holds a `MulticastLock`.
- **MOO over WebSocket** (`roon/Moo.kt`) — `ws://<core>:<port>/api` carrying
  HTTP-shaped text frames. Registration is
  `com.roonlabs.registry:1/info` → `register` → (user enables the extension)
  → `Registered` + token, after which
  `com.roonlabs.transport:2/subscribe_zones` streams zone state and
  `change_volume` / `control` drive it.

Album art comes from the image service's plain-HTTP form:
`http://<core>:<port>/api/image/<key>?scale=fit&width=720&height=720&format=image/jpeg`.

### Things the protocol makes you get right

- **Volume is per output, not per zone.** A grouped zone has one output per
  device, each with its own type, range and step; the ring drives all of them.
- **`type: "incremental"`** controls report no value, range or step at all —
  only relative ±1 is legal, so the ring switches to detents with no arc.
- **Fixed-volume outputs** have no `volume` object; the ring goes inert.
- **`soft_limit`** caps the top of the ring where a device sets one.
- Rotation is quantised to whole steps with the remainder carried over, and
  sends are coalesced to ~16/s with an optimistic local value that yields to
  the Core's echo after 900 ms.

## Build

```
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

Needs JDK 17+, Android SDK platform 36 and build-tools 36.0.0. Output lands in
`app/build/outputs/apk/release/`.

## Verification

`./gradlew :app:testReleaseUnitTest` — 27 tests:

- **Wire format.** Frames produced by the Kotlin encoder are parsed back by
  `node-roon-api`'s own `moo.js`, and the SOOD query by the parser lifted from
  `sood.js`, so the framing is checked against Roon's reference implementation
  rather than against my reading of it.
- **Zone state.** `subscribe_zones` payloads: the initial set, added / removed
  / changed deltas, and the separate seek-position delta that must not clobber
  anything else.
- **The ring.** Robolectric renders the real view with native Skia and dispatches
  real `MotionEvent`s: a 90° clockwise sweep on a −80…0 dB output with a 0.5 dB
  step produces exactly +45 steps, a 60° counter-clockwise sweep −30, a sweep
  across the 12 o'clock seam doesn't spike, and a fixed-volume zone produces
  none. The preview images above are that renderer's output.

**Not yet verified against a live Roon Core** — there is no Core in the build
environment. The protocol layer is checked against Roon's own code and the UI
against a real renderer, but the first end-to-end pairing is untested.

## Limits

- LAN only. Extensions have no remote/ARC path; away from home needs a VPN.
- The phone cannot become a Roon *output* — that needs RAAT, which is licensed
  only through the Roon Ready partner programme. This is a control surface.
- Requires a running Roon Server and a Roon subscription.

## Licence

MIT, see [LICENSE](LICENSE). Not affiliated with or endorsed by Roon Labs.
"Roon" is their trademark.
