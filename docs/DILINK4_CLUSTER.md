# Experimental DiLink 4 cluster video

The contributor reported testing this approach on a 2022 BYD Seal with DiLink 4.0 / Android 10. The updated upstream branch still needs a vehicle retest.
It keeps the main CarPlay stream on the head unit and launches a separate Activity
for the instrument-cluster stream. Other firmware has not been vehicle-tested.

## Setup

1. Enable the car's native cluster-map/projection mode.
2. In DiPlay settings, enable **DiLink 4 cluster video via ADB (experimental)**.
3. Tap **Authorize ADB / retry cluster routing** and approve the car's debugging
   prompt. Local ADB must be available; background retries never request approval.
4. Connect or reconnect the iPhone and open Apple Maps. The main CarPlay display
   stays on the head unit while the independent map appears on the cluster.

The route detects the current logical display ID from the `fission_`-named virtual
panel in `dumpsys display`; the exact `fission_bg_xdjaVirtualSurface` name wins
over other prefix hits. The owner is not checked — the stock map owns the panel
through a system app — and the measured `real W x H` sizes the stream instead of
a fixed canvas. It does not assume display 1.
An absent or ambiguous target is rejected. The Activity validates its
per-launch token and actual display before handing its surface to stream 111.
Some firmware reports display 0 to the view; in that case the exact Activity task
is checked in ADB's per-display Activity history.

A successful shell launch is not proof of visible output. Unconfirmed launches
retry after five seconds. Turning the feature off or destroying the host cancels
queued retries and invalidates launch tokens. Failed routing is recorded in the
normal diagnostic export. No stock task is moved and no OEM projection mode is
changed.

## Dismissing the stock map

The stock map shares the panel and can cover DiPlay's window. While the ADB
option is on, DiPlay's window is confirmed on the panel and the iPhone session is
live, DiPlay sends one `input -d <panel displayId> 4` (KEYCODE_BACK) to the panel
in two cases:

- the wheel-menu mode listener sees the cluster menu enter **Small screen navi**
  (3) or **Full screen navi** (4) from another known mode — switching between
  Small and Full counts as well;
- the driver taps **Open CarPlay** in DiPlay settings after returning there with
  the system back key.

The stock map's projection Activity closes when it is on top; DiPlay's own
Activity is not focusable and ignores the key. Nothing is sent when the panel is
unconfirmed or the phone is away, so the stock map is never dismissed while
DiPlay shows nothing.

## Layout and navigation

The stream and texture buffer follow the panel's measured size at 100% scale
(1920×720 on the measured Seal). The transparent,
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

DiLink 5/5.1 and public cluster displays take priority even if the ADB switch is
saved. Until an activity confirms the private display, the original virtual stream
remains. If confirmation arrives after CarPlay starts, DiPlay reconnects once to
request 1920×720 and covers the cluster during that transition. Turning off only
the ADB option leaves the saved cluster-map enable preference intact.
The DiLink 3 cluster-mode commands run here as on DiLink 3. Display creation is
skipped whenever DisplayManager or the authorized shell already lists a
`fission_` panel: on DiLink 4 the stock map owns that container, so DiPlay
reuses the existing panel instead of creating a second screen with AutoContainer.
The mode commands still call AutoContainer; on firmware whose stock map drives
the native casting they can switch the cluster to half-screen or close that
casting, so parked-car verification is pending. A pending DiLink 3 recovery
journal restores as usual. The user-selected native casting mode is preserved.

USB reconnection and colour controls retain the implementations already on main. Automated tests cannot establish visible placement on other cars.

The direct `am start-activity --display … -f 0x18000000` approach follows the legacy
platform-21 implementation published by 寒叙 (@Hanxu4131):
https://github.com/Hanxu4131/BYD-CarPlay (LegacyClusterTarget.kt,
LegacyClusterMap.kt and ClusterMapActivity.kt).

Vehicle testing and feedback: @ojjj13. Implementation and debugging assistance:
ChatGPT/Codex. DiPlay/xcertplay authors and existing licence notices are retained.

## Optional HUD text

HUD text defaults off and yields to active navigation. Leaving guidance for text
clears maneuver/distance records first. Existing firmware, receiver/version and
signing-certificate restrictions remain. DiLink 4 cluster video does not imply
DiLink 4 HUD support: no verified DiLink 4 HUD receiver profile was supplied with
these PRs. Diagnostic exports include the firmware and installed receiver version,
certificate and permission metadata needed to review a new profile. Do not add
one based only on package presence.
