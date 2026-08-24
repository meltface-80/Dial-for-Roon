# Feasibility: Android APK as a native Roon extension with a rotary-ring volume UI

Research note. Verdict: **feasible, with no bridge/companion service required.**

## 1. What the reference projects actually do

| Project | Stack | Talks to Roon how | State |
|---|---|---|---|
| [HiPhi Dial / `muness/roon-knob`](https://github.com/muness/roon-knob) | ESP32-S3 C/C++ firmware | HTTP + SSE to a **bridge** ([`open-horizon-labs/unified-hifi-control`](https://github.com/open-horizon-labs/unified-hifi-control), Rust, v3), which is the actual Roon extension | Beta, real code, Docker bridge required |
| [RoonPilot](https://github.com/mermayer/RoonPilot) | ESP32-S3 C/C++ firmware | Claims **direct** to Core over the Roon extension protocol, no bridge | Repo is currently README + `assets/` only — no firmware source and no release yet |

The bridge in the first design exists for convenience (source-agnostic: Roon + LMS + OpenHome + HQPlayer, plus an MCP server), not because the extension protocol is too heavy for the device. A phone is orders of magnitude more capable than an ESP32-S3, so the direct route is the right one for an APK.

## 2. The protocol an APK has to speak

Two pieces, both simple, both already reimplemented outside Node.js.

**Discovery — SOOD** (`node-roon-api/sood.js`): UDP, multicast `239.255.90.90:9003` plus subnet broadcast. Send a `SOOD`-magic query with `query_service_id = 00720724-5143-4a9b-abac-0e50cba674bb`; Cores reply with `unique_id` and `http_port`. Manual host/port entry is a valid fallback (and the only option if multicast is blocked).

**Transport — MOO over WebSocket** (`node-roon-api/moo.js`, `transport-websocket.js`): plain `ws://<host>:<http_port>/api`, binary frames carrying HTTP-like text:

```
MOO/1 REQUEST com.roonlabs.transport:2/change_volume
Request-Id: 7
Content-Length: 63
Content-Type: application/json

{"output_id":"170...","how":"relative_step","value":1}
```

Replies are `MOO/1 COMPLETE <name>` / `MOO/1 CONTINUE Changed` correlated by `Request-Id`. Subscriptions are `<service>/subscribe_<x>` with a `subscription_key`, and the Core pushes `CONTINUE Changed` frames on that request id. The Core also sends `REQUEST` frames *to* the extension for services it provides.

**Registration handshake** (`lib.js`):
1. `com.roonlabs.registry:1/info` → returns `core_id`.
2. Look up a saved token for that `core_id`; send `com.roonlabs.registry:1/register` with `{extension_id, display_name, display_version, publisher, email, website, required_services, optional_services, provided_services, token?}`.
3. User enables the extension in **Roon → Settings → Extensions**; the request then completes with `Registered` + a `token`. Persist `tokens[core_id]` and reuse it so approval is one-time.
4. Provide `com.roonlabs.ping:1` (reply `Success`) and, for pairing, `com.roonlabs.pairing:1` (`get_pairing`, `subscribe_pairing`, `pair`) so a Core can bind to this extension.

Non-Node implementations that prove portability: [`pavoni/pyroon`](https://github.com/pavoni/pyroon) (Python, own SOOD + MOO, Apache-2.0), the [`roon-api` Rust crate](https://docs.rs/roon-api/latest/roon_api/) (`roon-sood` + `roon-moo`, MIT/Apache-2.0). `node-roon-api` itself is Apache-2.0 and readable as the spec.

## 3. What the API gives the dial UI

`com.roonlabs.transport:2`:
- `subscribe_zones` — now playing (`three_line`, `length`, `seek_position`, `image_key`), `state`, `is_play_allowed` / `is_pause_allowed` / `is_next_allowed` / `is_previous_allowed` / `is_seek_allowed`, and per-output `volume { type, min, max, value, step, is_muted, hard_limit_min/max }`.
- `control(zone, play|pause|playpause|stop|previous|next)`, `seek`, `mute`, `mute_all`, `pause_all`.
- `change_volume(output, 'absolute'|'relative'|'relative_step', value)` — **per output**, not per zone.
- `transfer_zone`, `group_outputs`, `ungroup_outputs`, `subscribe_outputs`, `subscribe_queue`, `play_from_here`, `change_settings` (shuffle/repeat/auto-radio), `standby` / `convenience_switch`.

`com.roonlabs.image:1/get_image` returns bytes, and the same image is reachable over plain HTTP:
`http://<host>:<port>/api/image/<image_key>?scale=fit&width=720&height=720&format=image/jpeg` — feed that straight to Coil/Glide.

`com.roonlabs.browse:1` gives the full library hierarchy (search, albums, playlists, radio) if the app grows past a dial.

## 4. Rotary-ring volume: the parts that need care

- **Volume lives on outputs.** A grouped zone has N outputs, each with its own type/min/max/step. Ring gesture → apply `relative_step` to every output in the zone, or absolute to a designated primary. Don't assume one slider.
- **`volume.type`**: `number`, `db`, or `incremental`. For `incremental` there is no value/range at all — only ±1 `relative` nudges; the ring must render as a detent-only control with no arc fill.
- **Fixed-volume outputs** have no `volume` object. Ring must degrade gracefully (dim it, show "fixed volume").
- **Rate limiting.** A finger sweeping a 360° ring generates hundreds of touch events. Accumulate angular delta into whole `step` units, emit at most ~10–20 requests/sec, and keep an optimistic local value that reconciles when the `zones` subscription echoes the real one back (network/DAC volume changes can lag 100–300 ms).
- **Detents.** `VibrationEffect.createPredefined(EFFECT_TICK)` per step reproduces the knob feel; that plus an arc that snaps to `step` is most of what makes the hardware dial pleasant.
- **Geometry.** `atan2` on touch position relative to ring centre, track cumulative unwrapped angle, ignore touches outside the ring annulus so the centre stays free for artwork/transport.

## 5. Android-specific gotchas

1. **`WifiManager.MulticastLock`** must be held to receive SOOD replies — without it Android drops multicast/broadcast to userspace. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`.
2. **Cleartext.** `ws://` is blocked by default from Android 9. Add a `network-security-config` permitting cleartext for local addresses (ideally scoped, not `cleartextTrafficPermitted="true"` globally).
3. **Never hardcode the port.** Use `http_port` from the SOOD reply (9330 on current Roon Server builds, 9100 historically). Cache last-known host/port for instant reconnect and offer manual entry.
4. **Doze / Wi-Fi power save** kill idle sockets. Foreground service + `WifiLock` if the dial should stay live with the screen off; otherwise reconnect-on-resume with the saved token is enough (re-registration with a stored token is silent — no re-approval).
5. **Keepalive.** `node-roon-api` pings the socket every 10 s and tears down on a missed heartbeat; mirror that, plus exponential-backoff rediscovery on network change.
6. **Same-subnet only.** Extensions are LAN-only — no ARC, no internet path. Away from home requires a VPN back to the LAN.

## 6. Hard limits

- **The phone cannot become a Roon audio endpoint this way.** Playback endpoints need RAAT, whose SDK is licensed only through the Roon Ready partner programme. Extension API = control surface only.
- Volume control only exists where the output actually exposes one (device volume or Roon DSP volume).
- The extension must be manually enabled once per Core in Settings → Extensions, and a Roon *Server* must be running.
- No public API for lyrics; queue is read + `play_from_here`, not arbitrary reordering.

## 7. Recommended shape

Pure Kotlin, no bridge, no Node:

```
:roon-proto   SOOD discovery (DatagramSocket + MulticastLock), MOO codec,
              WebSocket transport (OkHttp), registry/pairing/ping, token store
:roon-api     typed services: transport(2), image(1), browse(1), status(1)
:app          Compose UI — circular artwork, ring gesture, transport row,
              zone picker; ViewModel over a zones StateFlow
```

`:roon-proto` is the only novel work — roughly the size of `pyroon`'s socket layer (a few hundred lines). Embedding `nodejs-mobile` + `node-roon-api` is a possible shortcut but adds ~30 MB and a second runtime for something a WebSocket and a `DatagramSocket` already do.

## 8. Distribution notes

`node-roon-api` is Apache-2.0 and third-party reimplementations (pyroon, the Rust crates, ESP32 firmware) are established practice. There is Play Store precedent for Roon-adjacent apps ([macro.on](https://community.roonlabs.com/t/roon-extension-macro-on-android-app/207485), though it does use a separate server-side extension). Avoid "Roon" as the product name or the Roon logo in the icon — "… for Roon" phrasing only. Note `unified-hifi-control` moved to PolyForm Noncommercial 1.0.0, so it is not a source to copy from for anything commercial.
