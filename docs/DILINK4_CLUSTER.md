# Experimental DiLink 4 cluster video

The contributor reported testing this approach on a 2022 BYD Seal with DiLink 4.0 / Android 10. The updated upstream branch still needs a vehicle retest.
It keeps the main CarPlay stream on the head unit and launches a separate Activity
for the instrument-cluster stream. Other firmware has not been vehicle-tested.

## Setup

1. Enable the car's native cluster-map/projection mode.
2. In DiPlay settings, enable **DiLink 4 cluster video via ADB (experimental)**.
   DiPlay requests the head unit's ADB access at that moment; approve the car's
   debugging prompt. Local ADB must be available; background retries never request
   approval.
3. If the automatic match picks the wrong surface, choose **Instrument projection
   display** — the switch's only child, whose single tap reads the head unit's
   display list over ADB and opens the picker.
4. Connect or reconnect the iPhone and open Apple Maps. The main CarPlay display
   stays on the head unit while the independent map appears on the cluster.

The route detects the current logical display ID from an XDJA-owned (`com.xdja.containerservice`)
cluster projection display, matched by name alone: any BYD `fission`/`xdja` projection surface
counts, whatever its resolution. `fission_bg_xdjaVirtualSurface` at 1920×720 is the measured
reference, not the only accepted model, and derived `shared_…` layers are never taken as the
base projection. It does not assume display 1.
An absent, ambiguous or mismatched target is rejected. The Activity validates its
per-launch token and actual display before handing its surface to stream 111.
Some firmware reports display 0 to the view; in that case the exact Activity task
is checked in ADB's per-display Activity history.

A successful shell launch is not proof of visible output. Unconfirmed launches
retry after five seconds. Turning the feature off or destroying the host cancels
queued retries and invalidates launch tokens. Failed routing is recorded in the
normal diagnostic export. No stock task is moved and no OEM projection mode is
changed.

## Layout and navigation

The stream and texture buffer remain 1920×720 at 100% scale. The transparent,
non-focusable Activity leaves factory instruments visible. An inactive stream is
covered with black so a disconnected phone does not leave a frozen map behind.

**Cluster safe area · edit box** opens a fitted editor. Drag the green edges;
the outline is mirrored on the cluster during calibration, including before a phone
connects. Opening the editor launches a preview-only window using already authorized
local ADB; it does not start CarPlay or hold the stock map. Keep the car's native
cluster casting active. Dismissing the editor closes a preview-only window and
invalidates pending launches, while an existing CarPlay window remains. Save applies the new
safe area after reconnecting. Cancel keeps the saved mapping. The outline clears
on dismissal or leaving settings. The cluster mapping has its own preference and
does not overwrite the main display's mapping. Reset restores marker-offset-based
placement. A manual replug may still be needed after changing connection settings.

For this mode, an unset dashboard-content choice defaults to **Map with turn card**
(the phone's built-in card). Saved choices are preserved. **Map with custom turn
card** uses DiPlay's existing maneuver overlay, with live placement/size controls.
It reuses upstream’s info strip for phone-supplied arrival time, duration and
remaining distance. Missing totals stay blank. Guidance clears at route end/expiry/disconnect and hides with an inactive
stream. Map orientation is controlled by the phone's cluster stream.

## Scope and credit

Routing priority is now keyed on the projection surface a head unit exposes, not
on a single measured firmware fingerprint. Any firmware exposing a DiLink 5 XDJA
Screen Projection display (`fission_bg_XDJAScreenProjection`, including derived
`shared_*` layers) takes the DiLink 5 path; the DiLink 4 ADB route stays off
regardless of the saved experimental switch. The `supported` fingerprint check now
only gates the measured 1920×720 layout plan and theme/contrast controls on the
verified firmware. DiLink 4 and DiLink 3 firmware no longer have a version gate
either: both fall back to the unified DiLink 4 ADB route when no DiLink 5
projection surface is present.

DiLink 5 public cluster displays take priority even if the ADB switch is
saved. Until an activity confirms the private display, the original virtual stream
remains. If confirmation arrives after CarPlay starts, DiPlay reconnects once to
request 1920×720 and covers the cluster during that transition. Turning off only
the ADB option leaves the saved cluster-map enable preference intact.
The DiLink 3 cluster-mode commands run on both DiLink 3 and DiLink 4 head units,
including while the ADB option is selected: they open the instrument's projection
window. The instrument keeps that window in its previous layout until it is opened
again, so every wheel switch to Full screen navi closes and reopens the projection
once; the full layout then takes effect without visiting DiPlay's settings.
A pending DiLink 3 recovery journal is always restored.

### Available screen selection

First-time configuration prompts the user to authorise local ADB, then to open
the car's own Gaode (AutoMap) projection in the instrument cluster menu (Small or
Full navi). DiPlay then scans every base logical display reported by
`dumpsys display` — including the main display (id 0) — and shows them as a list
for the user to pick. Items are not filtered out; each is tagged instead:

- **Current main screen (not selectable)** — id 0; the picker disables it.
- **Suspected instrument panel (recommended)** — a BYD/XDJA projection surface,
  whatever its resolution (matches `DiLink4ClusterDisplay`).
- **Suspected third-party desktop widget (not recommended)** — a small surface
  owned by a non-BYD package.
- **Other display** — anything else.

Nothing prevents a manual pick beyond the main screen; the markers only advise.
The manual pick is matched by name and panel size, so it survives reboots.

USB reconnection and colour controls retain the implementations already on main. Automated tests cannot establish visible placement on other cars.

The direct `am start-activity --display … -f 0x18000000` approach follows the legacy
platform-21 implementation published by 寒叙 (@Hanxu4131):
https://github.com/Hanxu4131/BYD-CarPlay (LegacyClusterTarget.kt,
LegacyClusterMap.kt and ClusterMapActivity.kt).

Vehicle testing and feedback: @ojjj13. Implementation and debugging assistance:
ChatGPT/Codex. DiPlay/xcertplay authors and existing licence notices are retained.

## Optional stock map and HUD text

This route is shared with PR #187; there is only one decoder-surface owner.
The stock map (`com.byd.automap`) is handed over automatically, with no setting:

- When DiPlay opens the DiLink 4 projection it first re-enables the whole stock map and
  forces the instrument to Full screen navi. The stock map's own cluster activity
  rebuilds the instrument's projection window, which the instrument otherwise latches,
  and DiPlay then sends the full-screen projection (16) the mode needs. A launch while
  DiPlay already owns the projection re-sends only the command the current mode needs,
  never a fixed one: Small screen navi re-sends nothing (its window is latched), Full
  screen navi re-opens the full-screen projection (16), and a closed projection returns
  the stock view (18).
- Once DiPlay's own projection is confirmed on the cluster, the whole stock-map
  package is disabled so it cannot grab the surface back. The original state is
  journaled before the first write and restored on failure, stop, or the next app
  launch after a crash. A failed restoration retains the journal and retries using
  already authorized local ADB; force-stop cannot guarantee immediate restoration.
- The driver still picks the mode in the car's instrument cluster menu. When the mode becomes
  Full, DiPlay announces the new instrument state and sends the full-screen projection (16) so
  the dashboard follows; "Turn on by navi", which shows no projection, sends the simple-navigation
  card (39) so CarPlay's turn card follows instead.

Requires authorized local ADB.

HUD text defaults off and yields to active navigation. Leaving guidance for text
clears maneuver/distance records first. All model checks are lifted: HUD activates
on any head unit that exposes an enabled, exported BYD HUD receiver — firmware,
receiver version, signing certificate, system-app flag, receiver permission and
SDK no longer take part. The song/lyrics line is carried on both HUD backends: the
standalone receiver, and the SOME/IP gateway's road-name field for head units
without the receiver (that rendering is unverified pending a vehicle test).
Diagnostic exports include the firmware and installed receiver version, certificate
and permission metadata. Fork application-ID/build
changes and deleted HUD diagnostic tooling from #187 are intentionally excluded.
