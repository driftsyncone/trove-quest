# Trove for Quest

Trove, a Stash VR Companion. A native Meta Quest app (sideloaded APK) for browsing and playing a self-hosted
[Stash](https://stashapp.cc) library. It is the headset counterpart of
[trove](https://github.com/driftsyncone/trove) (the web wall + WebXR player): the same login, saved filters,
wall and per-scene projection, but the video goes to the compositor through the
Meta Spatial SDK instead of WebXR in the browser.

The app only talks to the Trove server (you enter its address on first launch; the built-in placeholder is `https://trove.example.com`).
It never talks to Stash directly; the Stash API key stays on the server.

Why native: ExoPlayer decodes into a `VideoSurfacePanelRegistration` surface that the
compositor samples directly (no extra copy), so 8K SBS HEVC stays sharp. The SDK has
stereo modes (LeftRight / UpDown) and shapes (Quad, Equirect 180 / 360) built in.

## What works (milestone 1)

- **Login screen** (`ui/LoginScreen.kt`): the Server field on top (it applies to both ways in),
  then two cards: pairing and name + password.
- **Login with your phone (device pairing)**: the login screen shows a 6-character code
  and a QR code. Scan it with a phone (or open `<server>/pair` and type the code), log in
  there as usual and tap **Approve**; the headset logs in within ~2 s. The app calls
  `POST /api/pair/start`, then `POST /api/pair/poll {pairId, secret}` every 2 s; on
  approval the server sets the normal `trove_session` cookie, so everything after that is the
  same as a password login. Codes last 5 minutes and are replaced automatically, up to 6
  (30 min); after that, or after **Deny**, press **New code**. New code also applies the
  Server field above, so type the server first if it isn't the default. Code in
  `api/DevicePairing.kt` (state machine) and `ui/QrCode.kt` (ZXing core → bitmap).
  The app sends `User-Agent: Trove/<version> (<model>; Android <n>)`, which the phone
  page shows as the device asking.
- **Login with name and password** (fallback, right card of the login screen): name, password → `POST /api/login`. The password field has a Show/Hide button;
  **Next** on the keyboard goes Server → Name → Password, **Go** logs in.
- **Keyboard on panels** (`ui/PanelIme.kt`, put `Modifier.panelIme()` on every text field):
  on Horizon OS a panel is a window on its own virtual display, and the system only lets the
  window with system focus (re)bind the keyboard. While the keyboard is open that is vrshell's
  focus placeholder, and after it closes it is our activity on display 0, so moving to another
  field (Next, or a click) left the keyboard bound to the old field: the keyboard showed, but
  keystrokes went nowhere (logcat: `setComposingText on inactive InputConnection`). Only the
  first field of a session worked. `PanelIme` notices a field gaining focus while the panel
  lacks system focus, injects DOWN + CANCEL into a corner of the panel's own display (the same
  hidden `InputManager.injectInputEvent` call the SDK uses for controller clicks) so the window
  manager focuses the panel, then restarts input and shows the keyboard. Any later focus gain
  of the panel with a field focused also restarts input. Debug builds log under `TroveIme`
  and take `adb shell am broadcast -a app.trove.quest.DEBUG_IME --es op next`
  (ops: next, prev, clear, restart, show, hide, grab) to move focus without adb taps, which
  would move system focus themselves.
- The `trove_session` cookie is kept in an OkHttp `CookieJar` backed by SharedPreferences,
  so you stay logged in. Log out ends the session on the server and drops the cookie.
- **Wall** (`ui/Wall.kt`; look and rules in [docs/ux-audit.md](docs/ux-audit.md), tokens in
  `ui/theme/`). Header in three tiers: the **top bar** (Trove, search + Search, **Back to video**
  while screens are open, the sync pill, and the account button `name · v0.2.8`), the **saved-filter
  tabs** ("All" + Stash saved filters; hidden when the server has none) and the **tools row**: one
  **Sort: …** chip that opens the full sort row (and closes it after a pick), direction, Shuffle for
  random, and on the right the session group: *Open in* This screen / New screen n/6 while screens
  are open, else the **Room** chip and **Restore last screens**, then `WallModeSlot` (room for other
  viewing modes). The count and **‹ Prev / Next ›** are a footer under the grid. 12-per-page grid;
  a new page scrolls to the top and the old page stays up, dimmed, while the next loads. Empty /
  loading / error states are centred messages with an action (Try again, Show everything).
  Thumbnails come from the server's resized `/media/scene/<id>/thumb` (Coil, same OkHttp client =
  same cookie). Cards: 16:9 thumbnail with badges resolution · projection (3D SBS, 180 SBS, …),
  Script and duration, an accent bar where you left off on this headset, the **i** button, a
  two-line title and studio · date. Text is never below 14 sp and every target is at least 48 dp.
- **Facet search**: typing two or more letters in the search box shows matching performers,
  studios and tags as a chip row (`GET /api/suggest`, the web wall's dropdown; chips because popups
  are unreliable in panels). Tapping one adds it as a filter chip, sent to `/api/scenes` as
  `performers` / `studios` / `tags` (comma-separated ids) and ANDed with the saved-filter tab and the
  sort, as on the web. The applied search text and every facet show as removable chips (✕), with
  **Clear all** for two or more; switching tabs keeps them. Code: `ui/FacetChips.kt`, `AppModel`.
- **Scene details**: the **i** button on a card (long-press stays "new screen") shows the scene in
  place of the grid: date · duration · resolution codec fps · size, studio / performer / tag chips
  (tap one to filter the wall by it), the description and the markers (tap one to play from there;
  on the scene the focused screen already plays it just seeks). **Play** follows the header's
  Open in toggle; **Resume at mm:ss** / **From start** when the scene was left part way;
  **New screen** while screens are open; **‹ Back to library** (top of the left column) returns to
  the grid; a failed load offers **Try again**. `ui/SceneDetails.kt`.
- **Player**: tapping a card opens a screen with ExoPlayer streaming
  `/media/scene/<id>/stream` (Media3 OkHttp data source = same cookie). The server's
  projection picks the panel:

  | Server projection | Panel shape | Stereo mode |
  |---|---|---|
  | `flat` | Quad 2.4 m wide | None |
  | `sbs` | Quad (one eye's aspect, e.g. 16:9 for 7680x2160) | LeftRight |
  | `ou` | Quad | UpDown |
  | `sbs180` / `ou180` / `mono180` | Equirect180, r = 50 m | LeftRight / UpDown / None |
  | `sbs360` / `ou360` / `mono360` | Equirect360, r = 300 m | LeftRight / UpDown / None |
  | `fisheye190/200/220` | Our fisheye dome mesh (190° / 200° / 220°), r = 20 m | LeftRight |

  Fisheye (M4, `player/FisheyeMesh.kt` + `FisheyeShapeOptions.kt`): the SDK has no fisheye panel
  shape, but every panel shape is a `sceneMeshCreator` plus an optional compositor layer. The fisheye
  video panel drops the layer, so the SDK backs the video Surface with an ImageReader feeding a
  `SceneTexture`, and our mesh samples it: an equidistant fisheye dome (the WebXR player's model:
  distance from the image centre grows linearly with the angle off the axis, the image circle meets
  the edges of each eye's half at half the FOV), 64 rings x 96 segments. The material's stereo mode
  picks each eye's half of the SBS frame. The mesh is drawn in the eye buffer (one extra sampling
  of the video, no layer), hence the smaller radius. The compositor's own fisheye layer
  (`Scene.createFisheyeLayer`, the experimental `XR_METAX1_composition_layer_fisheye`) is not used.

  Control panel (`player/ControlPanel.kt`, 1.2 x 0.36 m): the title with one **status area** at its
  right (see below); marker ticks over the seek bar (trigger on one jumps there) with elapsed time on
  the left and total on the right; the **primary row** −10 s / Play-Pause / +10 s (and the Resume
  offer, when there is one); the **secondary row** **View** (projection
  override; saved on the server for everyone like the web UI, reset to auto when you pick
  the detected one; on flat/3D screens View also has a **Behind** row, see Passthrough below), **Stream** (Stash's transcodes when the original won't decode),
  **Markers (n)** (the markers as chips), and at the right **Library** / **Hide library** and Close. Opening a scene hides the main (wall) panel so it
  doesn't block the video; Library brings it back in front of you (picking another scene hides
  it again), and closing the screen restores it (`player/LibraryVisibility.kt`). Shown over a
  playing screen the wall can cover the controls, so it then has a **Back to video** button too.
  Quad screens are grabbable (pivot on Y) and carry their controls; spheres are centred on
  your head and their controls float in front of you, grabbable on their own.
- **Passthrough** (M4, `player/Room.kt`): View → **Behind:** on a flat/3D screen picks what is
  behind the screens: **Off** (black, as before), **Room** (passthrough), **Room, 50 % darker** or
  **Room, 80 % darker** (100 % darker is the same as Off). Darkening is a passthrough colour LUT
  (`Lut.fromScale`, every colour × (1 − dim)). The choice is app-wide and kept across launches
  (SharedPreferences `room_view`). A 180/360/fisheye sphere keeps passthrough off. The wall on its
  own (no screens open) shows the room only with the **Room** chip in its header on (off by default,
  `room_on_wall`), darkened like the Behind choice.
- **Moving panels**: point at a panel and hold **grip** (the side button under your middle
  finger); it follows the controller until you let go and turns to face you. The trigger
  still clicks. Works on the wall (it shows a "Grip to move" pill at the bottom), a flat/3D
  screen (its controls come along) and the controls of a 180/360 sphere. This is VRFeature's
  `SIMPLE_CONTROLLER` input with toolkit `Grabbable`, as in Meta's media_view and focus
  showcases. The 0.14 default (Interaction SDK) unregisters the grip GrabbableSystem; its
  `IsdkPanelGrabHandle` is a hover-only 2.5 mm border 2 cm outside the panel edge (invisible
  until hovered), which is why the earlier "grab bar" attempt showed nothing.
- **Thumbstick while gripping** (0.2.1): only the stick of the hand that holds grip does this; the
  other stick and every stick when nothing is held work as before.

  | While holding grip | What it does |
  |---|---|
  | Stick up / down | **Distance** (default): pushes the panel away / pulls it closer along your pointer, about 1 m/s, between 0.6 m and 10 m from your head. Its real size stays, so it looks smaller further away. |
  | Press the stick in (stick centred) | Toggles up/down between **distance** and **size**. Every new grab starts on distance. |
  | The **other** hand's stick up / down | **Size**, with no mode to switch (two hands). |
  | **B** / **Y** | **Puts the panel back** where, how big and how tilted it was when you picked it up (it stays there until you let go of grip). |
  | Stick up / down in size mode | Bigger / smaller in place, ×1.5 per second. Screens 0.4 – 8 m wide (aspect kept), the wall and a sphere's controls ×0.5 – ×2.5. |
  | Stick right / left | **Tilt** clockwise / counter-clockwise round the panel's own facing axis, 30°/s, after holding left/right for 0.2 s (for lying on your side). The first direction you push decides: a push that starts sideways only tilts, one that starts up/down only moves or resizes, until the stick is centred again. Let go within 3° of level and it snaps level. |

  Every motion eases in over 0.3 s (the stick only reports directions, not how far it is pushed), so
  a flick makes a small change. Grabbing shows "Stick ↑↓ distance · ←→ tilt · other stick: size · B: put back"
  for 2.5 s, and a press shows "Stick: distance" / "Stick: size": on the wall's grip pill, or as a tag
  on a screen's control panel. A video screen's controls keep their size when the screen grows or
  shrinks (they stay readable) and stay centred just below it; the focus bar follows its width; both
  tilt with it.
  Saved: a screen's position (so its distance), width and tilt go into the saved layout ("Restore last
  screens"); the wall's size and distance are kept across launches, its tilt only until the app
  closes (the wall is put level in front of you at every launch).
  How (`hold/`): `HoldRules` is the SDK-free math (unit tested in `HoldRulesTest`). `HoldSystem` runs in
  `lateExecute`, after the SDK's `GrabbableSystem`, which writes a level, user-facing pose to a held
  panel every frame. Tilt keeps the grab's facing and puts the roll back on top (not held, nothing
  rewrites the Transform, so the tilt stays). Distance replaces the grab's private `GrabInfo` (distance
  along the ray) through reflection, so the grab itself carries the panel further and never snaps it
  back; the same trick rotates the grab point once when a tilted panel is grabbed (otherwise it would
  jump). Size is a `Scale` component, which the grab doesn't touch. If the reflection fails (another
  SDK version), tilt and size still work and distance is off (logcat `TroveHold`).
  Note: the SDK also scrolls the panel under the pointer with the stick, so pushing a panel whose
  content scrolls can scroll it too.
  Stick bits: the SDK reports the stick as U/D/L/R bits plus a click bit. On the Quest the click bit
  also goes down when the stick is only pushed, so 0.2.1 flipped distance/size on every up/down push
  (the hint changed each time and stick-down never seemed to pull). Since 0.2.2 a stick click counts
  only with the stick centred now and for 200 ms before (`HoldRules.acceptToggle`, now read by
  `InputRouter`). Since the unified controls B / Y no longer toggle (they put the panel back, see Controls) and the
  other stick resizes. Also: when U and D are both set, down wins, as in the SDK's own panel scrolling; the
  push/pull limits use the larger of the head distance and the grab's ray distance, so a wrong head
  pose can't block a pull. Debug builds (what we publish) log the bits,
  mode, distances and delta about twice a second while a stick is moved during a hold:
  `adb logcat -s TroveHold:*`.
  Opening the Meta menu pauses playback; closing it resumes. Taking the headset off (or anything
  else that pauses the app) does the same, see "Headset off and the Meta menu" under Funscript sync.
- **Resume points and play tracking**: where each scene was left is saved on the headset with the
  layout autosave (every 5 s), on the Meta menu and when a screen closes (`player/ResumePoints.kt`,
  SharedPreferences JSON, up to 300 scenes, oldest dropped; the cache and the offline fallback). The
  first 30 s never count (opening and closing a scene keeps the old point), the last 30 s or 5 % count
  as finished and forget it. The app also reports to the server (`play/ActivityReporter.kt`,
  `POST /api/scenes/<id>/activity`): every 10 s while the **focused** screen plays (other screens
  count nothing, Storm tiles report nothing), a final report when it pauses, loses focus, closes, the
  Meta menu opens, STOP ALL hits or the app goes to the background, and `ended` at the end. Play counts,
  "last played" and the resume point are then the Stash UI's and the web player's too. The server's
  point (`resumeTime` of the scene, or the answer of a report) wins over the local one, except a
  local point clearly newer than the server's last play (watched offline); with no server data the
  local point is used. A report that fails (offline, 502) is retried with the next one; a server
  without the endpoint (404) switches reporting off until the app restarts. A scene opened from the
  start shows **Resume at mm:ss** (✕ = from the start) on its controls for 20 s (it may update a moment
  later when the server knows better); the details view offers it too.
- **App icon**: adaptive vector (headset with a play glyph) with a themed-icon layer.
- **Self-update** from the Trove server: "Update available" card on the wall, Install
  downloads, verifies and installs the new APK; version + **Check for updates** in the wall header.
  See [Updates](#updates-self-update-from-the-trove-server).
- Every screen is an independent `PlayerSession` (own ExoPlayer, own runtime-registered
  video and control panels); see milestone 2 below for several at once.

## Library sections (Scenes, Performers, Studios, Tags)

The wall browses like Stash. A row of four chips under the top bar switches between **Scenes** (the
wall as before, with the saved-filter tabs), **Performers**, **Studios** and **Tags**. Each of the
last three is a grid of cards: a picture (a portrait for performers, the whole logo for studios),
the name, and "N scenes" plus age and country for performers or the parent studio for studios, a
star on Stash favourites. Search (type, then Search), the Sort chip (name, scene count, rating,
random, recently added; performers also birthdate and age), the direction chip, a Favorites toggle
and the pager work as on the scene wall. Each grid keeps its own search, sort and page while you
visit another one, and the app reopens on the section you left it in.

Tapping a card opens its **page**: the Scenes section filtered by that performer, studio or tag,
under a header with the picture, name, facts (age, country, parent studio, scene count), aliases
and Stash's description, and three actions: **Play random** (a scene from just this one),
**Storm** (a Storm wall of just their scenes, the same query as the Storm info panel's chips) and
**‹ Performers** back to the grid. **B / Y** does the same as that button (after closing an open
row or the scene details), long B goes home. Search and sort work inside a page; adding another
chip or picking a saved-filter tab turns it back into an ordinary filtered wall.

Server: `GET /api/performers|studios|tags` and `/api/<kind>/<id>` (see the Trove README); the
sort keys come from `/api/filters` (`sections.sorts`), with a built-in list for older servers. Code:
`ui/Sections.kt` (cards, grid, page header), `ui/SectionRules.kt` (pure rules), the section state
in `ui/AppModel.kt`, tests in `SectionsTest`. Markers are not a section yet.

## Controls

One set of buttons, the same in every state and the same on both controllers, so one hand is
enough (lying on an arm, a drink in the other hand). Full rules and the reasoning:
[docs/control-scheme.md](docs/control-scheme.md); the rules themselves are plain Kotlin in
`input/ControlScheme.kt` (tested), run by `input/InputRouter.kt`.

| Input | What it does |
|---|---|
| **Trigger** | Select / click (wall, panels), focus then play/pause on a video, Storm tile sound (it never opens the video; Solo is a panel button). Hold on a card = new screen; drag a card out of the wall. |
| **Grip** | Move the panel you point at. **Both grips for 2 s** = stop everything and show the room, in every state (also Black Out and while carrying a panel). |
| **Grip + stick** | Stick up / down distance (or size), left / right tilt (roll), stick press = distance <-> size, **other stick up / down = size, left / right = pitch** (Face me off), **B / Y = put back**. |
| **A / X** | Play / pause the screen with sound (Storm: pause all / play all). **Hold** = pause everything. A hold never plays anything. |
| **B / Y** | **Back**: closes the newest open row (View / Stream / Markers on a screen, Sort / account on the wall), the scene details, switches between the library and your video, and in Storm (nothing else open) **exits Storm** (the library over Storm hides first). **Hold** = straight to the library. B never closes a screen, never lifts Black Out, never resumes anything. |
| **Menu** (left) | Show / hide the library (on release). **Hold 0.8 s** = hide / show the screens' control panels. |
| **Stick** (not holding) | Pointing at a panel that scrolls: it scrolls. Anywhere else: left / right seeks the focused screen -10 s / +10 s (30 s steps after ~2 s held), up / down sets the app volume (10 % steps, remembered, also Storm), or, with **Stick: Suction** on the VacuGlide panel, **holds** suction Tighter (up) / Looser (down) while pushed (see "VacuGlide panel"). Storm: right = next clip on the selected tile (else all), up / down volume; on the Focus centre left / right seek it. |
| **Stick press** (centred) | Shows what every button does right now (5 s, press again to hide). While holding with that hand: distance <-> size. |
| **Meta** | System (menu pauses, closing resumes). |

Black Out ignores every button except both grips. STOPPED changes no mapping. Panels click on the
triggers only (`PanelInputOptions(clickButtons)` on every registration: A / X are not clicks any
more). Back is worked out from state every press (`ControlScheme.back`), not recorded as history;
every layer it closes also has a visible button (Done, Back to library, Back to video, Close).

- **Button legend** (`input/ButtonLegend.kt`): a small panel in front of you, generated from the same
  rules the router runs. Stick press shows it; the first launch of a new controls version shows a
  coach card once (pref `controls_v2_seen`). Account panel -> **Button hints: Auto / Off** turns the
  automatic card off (a stick press still shows it). Never shown in Black Out.
- **Volume**: app-wide 0 - 100 %, applied to the screen with sound and to Storm, shown as a tag on
  the focused controls / Storm's panel when the stick changes it. A **Vol** slider (10 % stops, with
  the value) sits right of the seek bar on every screen's controls and in the Clip row of the Storm
  panel; it sets the same level and follows the stick (`AppVolume` is Compose state). The headset's
  volume keys still work.
- **Hands** (hand tracking): pinch = trigger. Thumb microgestures stand in for the buttons
  (tap = A, swipe back = B, swipe forward = Menu, swipe left / right = stick left / right), read
  from the SDK's `Microgestures` component; not tested on a device yet. **Hands-only escape**
  (0.5.2, untested on a device): raise **both open hands in front of your face, fingers up,
  palms flat facing forward (a two-handed "stop" sign; palms toward you also count) and hold 2 s**:
  exactly what both grips 2 s do (below, "Wearer escape"), in every state. Pinching, thumb
  microgestures, pointing, fists, one hand out of view or a tracking jump never count; any break
  restarts the 2 s. Rule in `input/HandEscape.kt` (unit tested), joints from body tracking
  (`input/HandEscapeSystem.kt`, the 0.14 SDK has no other hand-joint API), so it needs the **body
  tracking permission**: the app asks once, the first time it sees both hands with no video or Storm
  open. Denied: no hands escape (partner's STOP ALL and the physical button remain). Off while a
  controller is active. Details: docs/control-scheme.md section 2b.
- Debug builds log the actions the router fires under `TroveInput`, plus every 500 ms each hand's pointer target and ray (`pointer ...`).

## Multi-screen (milestone 2)

Up to six screens at once, like monitors. Mirrors the WebXR player's VR multi-screen
(Trove server README "VR multi-screen (phase 2c)").

| Control | What it does |
|---|---|
| Tap a card on the wall | Opens it in **the focused screen** (replaces it in place, same position and size) and hides the wall. |
| Wall tools row **Open in: This screen / New screen n/6** | With *New screen* a tapped card opens in an extra screen and the wall stays up so you can add more. At 6/6 it replaces the focused screen. Shown while a screen is open. |
| **Long-press** a card (hold the trigger) | Always opens a new screen, whatever the toggle says. |
| **Trigger on a screen** | Unfocused screen: focuses it (sound + original stream move there), nothing else. **Focused screen** (a lone screen always is): plays / pauses. On a 180/360/fisheye sphere a click anywhere on the video toggles. Clicks on the control panel or the focus bar never toggle (they are separate panels; a touch on the controls still focuses their screen), nor does the trigger of a grip-grab. Rule in `player/ScreenClick.kt`. |
| **Drag a card** off the wall | Point at a card, hold the trigger and move: past ~12 dp (or off the wall) a small ghost (thumbnail, title, caption) follows the ray at the wall's distance, facing you level. Release **off the wall** = opens in a new screen right there (1.6 m wide; the first screen 2.4 m); release **over the wall**, grip, B or Y = cancel. At 6/6 (or behind a sphere) the drop replaces the focused screen and the caption says so. See below. |
| Grip on a screen | Moves it with its controls and focus bar (as before); with the stick: distance, size, tilt (see Moving panels). |
| Control panel | Acts on its own screen: play/pause, seek, View, Stream, Markers, Library, Close, **Hide controls**. With several screens **only the focused screen shows its panel**; it fades / slides to the next screen when focus moves (trigger, look-to-focus, Director). The others show nothing (the focused screen keeps its thin accent bar). Its status area shows **Sound**, **Muted** or **720p** as before. See "Hiding the controls". |
| Wall **Screens row** (screens open): **Posture**, **Face me**, **Look to focus**, **Tidy**, **Bring screens to me** | See "Face me", "Screens keep clear of each other", "Look to focus", "Posture layouts" and "Bring screens to me" below. |
| Wall **Restore last screens (n)** | Shown when nothing is open and a layout was saved: reopens those scenes at their positions, sizes, times and focus. Never automatic, so launching the app doesn't start six videos. |

- **Drag a card into the room** (`drag/`): the wall only sees the ray while it is on the wall, so a
  press on a card (`pointerInput`) reports to `CardDrag`, and past the threshold (or as soon as the ray
  leaves the wall) `CardDragSystem` takes over from the controller pose: ghost panel at the wall's
  distance from the head, level facing, trigger bit watched, drop decided by a ray-rectangle test
  against the wall's pose and size (3 cm margin, so releasing at the rim cancels). The drag must
  start within the long-press time (0.4 s), since a long-press is "new screen". Taps and
  long-presses are unchanged. The ghost is not hittable, so the pointer stays on the wall.
- **Hiding the controls**: the panel's **Hide controls** button, or **hold Menu (0.8 s)**, hides the
  control panel of every screen (one flag for the layout, so focus moving does not bring another one
  up). The focus bar stays as the only indicator with several screens; a lone screen shows nothing.
  They come back with the **trigger on a screen** (it only brings them back, no play / pause),
  **hold Menu** again, **B / Y** (with the library already hidden: first press shows the controls,
  the next the library) or anything that raises the library (Menu, Library). Opening a screen shows
  them. Optional **Auto-hide controls** in the account panel (Off / 15 / 30 / 60 s of no pointing at
  the controls, no button / stick use and no trigger on a screen; default Off, remembered). A hidden
  panel is invisible to the ray and the back stack (rows on it are ignored), its Compose content is
  disposed, so its position poll stops; nothing runs for it per frame. Rules: `player/ChromeState.kt`
  (tested in `ChromeTest`). Stick volume / seek still work while hidden but show no pill.
- **Focus**: only the focused screen has sound. With more than one screen visible it gets a
  thin accent bar along its top edge and an accent border round its controls. Closing the
  focused screen focuses the one focused before it.
- **Placement**: the first screen goes in front (2.4 m wide at 2.2 m), as before. New screens are
  1.6 m wide at 2.6 m and open in the **free spot nearest to where you look** (or where you dropped
  the card): sideways first, then up or down, never on top of another screen. See below.
- **180 / 360 / fisheye stay single-scene**: when a sphere opens (or a screen's View is switched
  to one) every other screen pauses, hides and releases its decoder (`ExoPlayer.stop()`); they
  come back at the same time and play state when the sphere closes or turns flat again. While a
  sphere is open, *New screen* replaces it rather than adding a screen next to it.
- **Library / Back to video** work as before with several screens: the wall's *Back to video* and
  every screen's *Library* button toggle the same wall; it comes back by itself only when the
  last screen closes.
- **Layout persistence**: scene, world pose, width, tilt, time, playing and focus of every screen go to
  SharedPreferences (`trove` / `screen_layout`, JSON, `player/SavedLayout.kt`) on every open,
  close, focus or View change, every 5 s while screens are open (times, grabbed poses), when the
  Meta menu opens and on shutdown/logout (saved before the screens close, so closing them one
  by one doesn't shrink it). Nothing open = nothing saved, so the layout survives until you open
  a scene again; opening a scene instead of restoring starts a new layout.

### Face me

**Face me** (wall Screens row, default on, remembered in `face_me`) keeps every flat screen square
to your eyes: its normal points at your head, in yaw **and pitch**, so a screen placed high or low
tips towards you instead of staying upright. It applies when a screen opens, is moved (grip, the
Director, a posture preset, Bring screens to me, the layout easing below), is restored, and when you
let go; **while you hold a screen it faces you every frame**. Your manual tilt (roll, the grip stick's
left / right) stays on top. Turn it on and every screen turns to face you where it is.

With Face me **off** screens keep their pitch, and you tip a gripped screen with the **other hand's
stick left / right** (right = the top tips away, the screen faces up; ±85°). The other stick's up /
down still resizes and the gripping stick still moves (up / down) and rolls (left / right); nothing
to switch. Posture presets just place screens now: Face me works out the yaw and pitch (with it off,
Lying back still tips them down at you). `layout/SphereLayout.kt` (`Facing`), `HoldSystem.applyFacing`.

### Screens keep clear of each other

Every flat screen lives on a sphere round your head: a direction, a distance and, from those, an
angular size. After any move, resize, open or close a short relaxation (`layout/`: pure
`SphereLayout`, applied by `ScreenArranger` with a small per-frame `LayoutSystem`) eases overlapping
screens apart along the shell:

- **Soft and minimal**: each overlapping pair separates along the axis it overlaps least on
  (sideways is slightly preferred), each taking half, with a 2° margin; screens that don't overlap
  never move. It eases in about 300 ms. Distances never change (only direction on the shell), and
  nothing is pushed above 80° (the pole).
- **A held screen never moves**, and it **pushes the others aside in real time** while you drag or
  resize it; when you let go they settle and the layout is saved.
- **Depth**: screens less than 0.5 m apart in distance collide. A screen 0.5 m or more nearer than
  another may sit in front of it (pull one close to look at a detail and the room stays put).
- **New screens** take the nearest free spot to where you look (a card you drop lands where you let
  go, or beside whatever is there); with the room full it takes the least overlapping one and the
  relaxation does the rest.
- **Tidy** (Screens row) packs the screens into a neat grid round the current view, for the current
  posture: rows of as many as fit within about 120° (the focused screen at the centre of the
  eye-level row, the others in their current left-to-right order, more rows above and below), each
  at the standard distance with its size and roll kept; it eases there.
- Saved layouts load as they were; Storm and spheres are not part of this. Not tuned on a device:
  the margin, the 300 ms and the 120° row are by feel.

### Look to focus

The screen you face for about 0.8 s gets focus (sound + original stream), no click. Toggle **Look to
focus** in the wall's Screens row (default on, remembered in `gaze_focus`); trigger-click focus works
either way. The rule is `gaze/GazeRule.kt` (plain Kotlin, `GazeRuleTest`), fed every 100 ms by
`gaze/GazeFocus.kt` with the head pose from `scene.getViewerPose()`:

- A screen is **faced** when the gaze is inside 75 % of its angular radius (half its angular width
  from the head), so the gap between screens and their edges face nothing; where two overlap, the
  more central one wins.
- **Hysteresis**: the focused screen keeps focus until another is more central by a quarter radius
  *and* has been faced for the whole dwell. Scanning across the wall never takes focus; a gaze
  resting on a border doesn't flicker. After a switch the next waits 1.5 s (a focus change swaps
  decoders and the toy follows the focused screen).
- **Quiet while**: a panel is gripped (`HoldSystem.holding`), the pointer moves over a screen's
  controls (1.5 s window, `panelPointer`), the wall is up, or Black Out is on. The dwell starts over
  when the block ends.

### Posture layouts

The wall's Screens row has **Sitting / Lying back / Left side / Right side**. A preset re-lays every
open flat screen (and the wall) round your head where it is now; the choice is remembered (`posture`)
and the wall follows it too. The math is `posture/PostureLayout.kt` (`PostureLayoutTest`); every
posture starts from the sitting arc (the focused screen straight ahead, the others alternating right /
left, touching with a 4° gap) and turns it as a whole about the head:

- **Lying back**: about the head's right axis by 70°, so the arc is overhead and every screen is
  pitched down at your face (the picture's top towards the head of the bed). "Ahead" is read from the
  top of the head when you look at the ceiling (`bodyYawDeg`), so it works lying down.
- **Left / right side**: about the head's forward axis by ∓90°, so the arc runs up and down and each
  screen is rolled with your head.
- Pitch is saved with the layout (`pitchDeg`, old layouts restore upright). A grab writes a yaw-only
  facing; `HoldSystem` puts the **pitch and then the tilt** back on top of it (yaw, pitch, roll),
  and rotates the grab offset by both, so grabbing a lying-back screen doesn't flatten it. With
  **Face me** on (default) the pitch is simply the screen's elevation seen from your head, so the
  presets only choose positions.
- The Storm wall is not re-laid for a posture (it is rebuilt round where you look when it starts).
- Needs the headset: the 70° elevation, and which side is "left", are tuned by feel.

### Bring screens to me

**Bring screens to me** (wall Screens row) turns the whole layout about you, yaw only, until its centre
(the mean direction of the screens) is where your body faces now: screens and their controls, sphere
controls, the wall and the Storm wall. Distances, heights, pitch and tilt stay, so a posture layout stays
as it is (lying down, it faces "ahead" as for Lying back). The saved layout is updated. The control
panel's secondary row has no room for it, so it lives on the wall (the screens' **Library** button
brings the wall up).

### Streams and the decoder and layer budget

Thumbnails (Coil) have their own caps so they cannot crowd the players: 32 MB memory cache and
64 MB disk cache (`cacheDir/images`) instead of Coil's default of a quarter of the heap.

The Quest 3 HEVC decoder handles up to 16 instances and 7.78 M blocks/s; one 8K60 SBS stream is
about 50 % of that. So:

- The **focused screen plays the original file** (`/media/scene/<id>/stream`, seekable).
- **Unfocused screens whose file is bigger than 3840 x 1080** play Stash's 720p H.264 transcode
  through the server's media proxy: `/media/scene/<id>/stream.mp4?resolution=STANDARD_HD&start=<s>`
  (the URL from the scene's stream list when it has one, else built the same way). Stash scales the
  short side, so 7680 x 2160 SBS becomes 2560 x 720. Smaller files always play the original. A
  lone screen always plays the original. Each unfocused big screen is a live ffmpeg transcode on
  the server (first byte after ~5.4 s for 8K HEVC).
- Streams change **1.5 s after focus settles** (`FOCUS_DWELL_MS`; sound moves at once), downgrades
  first. An upgrade waits (up to 10 s) until no other visible screen still decodes a big original,
  so two 8K originals overlap only briefly at most.
- **Swaps are double buffered**, no black frames intended: a second ExoPlayer prepares the new
  source with no surface (so no decoder yet), at the time the old one will have reached by then
  (+6 s for a transcode, which Stash takes ~5 s to start; +1.5 s for the original, which seeks).
  When the old stream reaches that time the surface moves to the new player and the old one is
  released. If the new original was slow, it seeks to catch up; a slow transcode may jump back a
  second or two. A source that isn't ready in 30 s is dropped and the old one keeps playing; if
  the original fails to come back, that screen stays on the transcode and says so.
- Transcodes count time from their `start`, so the session keeps an offset: the seek bar, ±10 s,
  saved times and swaps use scene time. Seeking a transcode restarts it at the new time (the old
  one plays until the new one is ready). A stream picked by hand with **Stream** is kept (no
  automatic swaps for that screen) until you pick the first (original) again.

Code: rules without SDK types in `player/ScreenRules.kt` (`MultiScreen`: thresholds, transcode
URLs, arc placement) and `player/ScreenSet.kt` (focus, sound, quality, dwell, sphere
exclusivity, decoder wait), unit tested in `MultiScreenTest`; `PlayerManager` creates, places and
saves; `PlayerSession` does the swaps and the trigger listener (an `InputListener` on the video
panel's scene object, which leaves the grip to `GrabbableSystem`).

## Storm (MediaStorm-style video wall)

Tools like MediaStorm fill the screen with many clips at once, each tile cutting to another random
clip every few seconds, for sensory overload. In VR the wall can go round you instead of sitting on
a monitor. The wall header's **Storm** button reads the wall's current view (tab, search, facets,
sort; not the page) into a pool and opens the wall; **Re-storm** (same button) rebuilds the pool
after you changed the view (the panel's **Library** button brings the wall back to do that).
Code in `storm/` (rules `StormRules.kt`, tiles `StormTile.kt`, owner `StormManager.kt`, Compose
`StormPanel.kt`), unit tested in `StormRulesTest`. The Trove server needs nothing new: previews
and originals already go through its `/media` proxy.

- **Pool**: up to 4 pages of 60 scenes of the query (random pages when it has more). **Flat 16:9** scenes
  (aspect 1.55 - 2.05) and **3D screens** (side by side, over / under, full or half: one eye is 16:9) go in;
  a 3D tile's panel is registered with the matching stereo mode like a screen (and re-registered on the same
  player when a cut brings a scene of the other kind; the pool prefers the tile's kind). 180 / 360 / fisheye
  spheres and other aspects stay out. A view with fewer than 3 usable scenes says so on the wall
  header, with **Storm whole library**. The pool is a
  shuffled deck (no repeats until all were shown; scenes on screen are skipped when possible); a
  scene that fails to play leaves the pool and the tile takes another.
- **Preview to scene time**: a preview mp4 is a montage of `previewSegments` clips (default 12, each
  `previewSegmentDuration` = 0.75 s, at least 0.75) sampled evenly across the scene, minus
  `previewExcludeStart` / `previewExcludeEnd` (seconds or a percentage like "2%"). A position in it maps
  exactly to the scene: `excludeStart + i * ((duration - excludeStart - excludeEnd) / segments) + offset`
  with `i = floor(pos / segmentDuration)` and `offset` the time inside that clip (`PreviewMontage`,
  after Stash's `pkg/scene/generate/preview.go`). A scene shorter than segments x clip length has a
  single clip, so preview time = scene time. The settings come from the server's `GET /api/preview-config`
  (Stash's defaults if it can't answer). Used by the Focus swap/upgrade and Solo.
- **One storm**: Storm is one session: one set of tiles (each with one player and one video panel), one
  cut loop, one control panel. Re-storm, layout, source and swaps close what they replace first; debug
  builds log the live counts after every change (tag `TroveStorm`) and fail if they differ from the plan.
- **Sources** (panel, Clip row): **Previews** (default) are Stash's per-scene preview mp4
  (`/media/scene/<id>/preview`, ~15 s H.264, tiny, no transcode on the server); each starts at a random
  point and loops (picked before loading from the montage's expected length, `Storm.previewStartMs`,
  so the tile opens there; a seek after loading only when the length can't be told). All tiles load at
  once: media has its own OkHttp dispatcher (32 per host) and connection pool (`MediaPlayers`), where
  OkHttp's default let 5 calls per host wait for their headers at a time. **Random seeks** play a random clip inside the original (`/stream`, range
  requests) but only for H.264 files up to 1920 x 1088 and 30 s+; every other scene in that mode
  still plays its preview. Stash's transcodes are not used (a live ffmpeg per tile on the server).
- **Focus layout** (Layout row): one big centre tile (1.4 m wide, 2.2 m ahead; ring tiles 0.87 m with 6 round it, 0.70 m with 8, 0.42 m with 12; constants `FOCUS_CENTRE_W_M` and `FOCUS_RING_SCALE` in `StormLayout`) with 6 / 8 / 12 preview
  tiles in a row above and a row below it (no side columns; the top row gets the extra tile; Around row; the centre is one more tile, all within the decoder budget).
  The centre never cuts or re-seeks (also not with the Random seeks source: it plays on from where it was) and has the sound; it plays its scene's original when the block budget has room
  (previews are silent), else its preview. The ring keeps shuffling. A trigger on a ring tile **swaps**
  it with the centre: both glide to each other's place in 350 ms (eased, a depth bulge so they pass in
  front of / behind each other, no new players); the new centre gets the sound and upgrades, the old one
  drops back to its preview and rejoins the shuffle. Trigger on the centre does nothing (Solo is the panel's button).
  **Scrub bar**: pointing at the centre shows a seek bar inside its bottom edge (2/3 of its width, centred) (marker ticks if the scene
  has markers, elapsed, slider, total, like a screen's controls); trigger on the slider or a tick seeks.
  It hides 1.5 s after the ray leaves. A centre that is still a preview first becomes the original at the
  chosen scene time (if the block budget has room; else the bar only shows the time). Stick left / right
  on the centre seeks it -10 / +10 s. One bar panel (`StormScrubBar`), moved on swaps, counted in the
  debug audit; rules in `storm/ScrubRules.kt`. The bar is registered once at 2/3 of the layout centre's width and
  its entity scale is the centre's *rendered* width over that (`ScrubRules.placement`), so it is exactly 2/3 of
  the centre however that tile got there (a ring tile swapped in carries a large layout scale that the bar must not inherit).
  **Centre upgrade**: a tile that becomes the centre (a swap, Storm starting) starts its original at once, at the preview's
  spot in the scene (`CentreUpgrade`), and keeps its preview frame frozen while that buffers; the centre never stays on the
  jumping preview montage. When the decoder budget lacks room the last ring tiles close for it (the plan keeps the centre's
  original cost reserved, so they don't reopen); only when the open screens leave no room at all the preview is paused on its
  frame. The debug audit logs the request and the time until the original is ready.
  **Scene info panel** (`StormInfoPanel`, a panel of its own right of the centre, as tall as it, outside the ring rows and inside
  the +-34 degree view): the centre's title, date / duration / resolution, studio, performers, tags (chips) and markers, fetched per
  scene id (cached) and following the centre on swaps. A marker seeks the centre. A performer / studio / tag chip **re-Storms with
  just that facet** (all of their scenes; the saved filter, other facets and search are dropped; stays in Storm); the small "Library" beside it opens the library filtered by it. Collapse
  shrinks it to a slim tab (remembered); Hide removes it until Menu / B bring it back with the Storm controls. The stick scrolls it
  (a `PANEL` pointer region), so it never seeks the centre from there. One panel, counted in the debug audit.
- **Layout** (Tiles row): 4 / 9 / 16 / 25 tiles. **Curved wall**: a grid (2 x 2, 3 x 3, 4 x 4 ...,
  last row centred) on a 2.2 m cylinder in front of you, tiles 16:9 and facing you, up to 34 degrees
  wide each and at most 130 degrees of arc. **Surround**: rings of tiles round your head on a 2 m sphere
  (1 ring up to 8 tiles, 2 up to 16, else 3), the layout is placed relative to where you face
  when it opens. Tiles are not grabbable; the control panel is (grip).
- **Cycling**: each tile plays N seconds (slider **Clip**, 5 - 60 s, default 20), +/- 40 % jitter, the first cuts
  staggered, then cuts to the next pool scene. At most one cut per 300 ms, so **Shuffle now** rolls
  through the tiles one after another. One ExoPlayer per tile and no spare decoders: a cut is
  `setMediaItem` on the same player, which reuses the decoder and keeps the last frame on the
  surface (an accepted plain cut; whether it ever flashes black is for the headset to say).
- **Tile count changes** (budget, Solo, screens opening, the Tiles / Around chips) close the last tiles or
  open new ones and re-lay the rest in place; only a new storm or another layout rebuilds. Tiles without
  the sound have their audio track disabled (no audio decoders).
- **Hide** (panel): hides the Storm panel (Storm keeps running). **Menu** brings it back (when the library
  is hidden), and so does **B** (once; B again shows the library). Black Out and Close reset it.
- **Sound**: muted by default. Point at a tile and pull the trigger: it gets the sound and grows a
  little (the panel names its scene); trigger again on it does nothing (a second click never opens the video; the SDK's duplicate
  trigger events within 300 ms are dropped). The panel's **Solo** opens that scene as a regular screen at
  full quality, from the clip's spot for seeks and, for a preview, from the exact spot in the scene it is
  showing (below). **Mute** silences all. The storm keeps running next to the new screen.
- **Decoder and layer budget** (`DecoderBudget`, `LayerBudget`): never more than 16 decoder instances with the open screens, 2
  kept free (Solo, a screen's stream swap), and tiles use at most 80 % of 7,776,000 blocks/s minus
  what the screens cost (focused screen: its file at 60 fps; others: 720p at 30 fps). A preview tile
  is about 27,600 blocks/s (640 x 360 at 30 fps), a seek tile up to 489,600. So with no screens:
  **14 tiles at most** (16 and 25 ask for more and show 14, saying so on the panel), with one screen
  13, and so on; the plan is re-checked every second, so opening screens trims the wall and closing them
  brings tiles back. Assumes the H.264 hardware decoder shares the 16 of the HEVC one.
  The compositor also takes only 16 layers per frame (one more: `XR_ERROR_LAYER_LIMIT_EXCEEDED`, the
  view goes black). Every video panel (tile, screen) is a layer; UI panels (scrub bar, info, Storm
  controls, legend, ghost, focus bars, screen controls) are drawn into the eye buffer
  (`PanelRender.eyeBuffer`, SDK `PanelRenderMode.Mesh`) and cost none. Only the library wall stays on a
  layer (sharper text) and counts while shown. `LayerBudget` allows 14 (2 kept free for the system), so
  tiles are capped at 14 minus open screens minus 1 for the wall: 14 tiles with nothing else, 13 with the
  library up, and the plan re-runs when the library or a screen appears. The panel says "N of M tiles:
  display layer limit" when layers are the reason. The debug audit line (`TroveStorm`) shows the live layer
  count and logs an error (never throws) above 14. The VacuGlide panel (below) is two more eye-buffer panels per
  screen (chip and whole, only one is ever shown, created when first needed): no layer; the debug audit `TroveVg`
  logs an error when more than one is up.
- **Safety**: **Black Out** hides every tile and the panel and mutes them (they keep playing, like
  screens). The partner's **STOP ALL** pauses all tiles and the cut timers; they stay paused until
  **Play all** on the panel. The Meta menu pauses them and resumes when it closes. **Pause all** on the
  panel does the same by hand.
- **Partner console**: while Storm is on the headset reports no focused screen (open screens are sent
  with `focused: false`), so the toy never runs from random clips, and the headset line carries
  `storm: on, 9 tiles`. Close Storm for the toy to follow a screen again.
- Storm also counts as "screens open" for passthrough: the panel has the **Behind** row (View -> Behind).

## Funscript sync and partner STOP (milestone 3 + 5, app side)

The app is a sync "player" of the Trove server (its README "Funscript sync and partner
console"). The server drives the VacuGlide from the **focused screen's** clock and shows the
session on the partner's phone at `/companion`; the app never talks to a toy itself. Code in
`sync/` (`SyncClient` socket + backoff, `SyncProtocol` messages, `SyncReporter` cadence,
`HeadsetStatus`), glue in `TroveActivity` and `PlayerManager.syncScreens / stopAll / remote`.

- **Link**: WebSocket `/api/sync/ws?role=player&client=quest&caps=director` with the normal
  `trove_session` cookie (`caps=director`: this player obeys the Director commands below) (no `Origin`, which the server allows for players). Open while logged in and the
  activity is started (visible); closed on logout, when the app goes to the background and on
  shutdown. Reconnects after 1, 2, 4, 5, 5 ... s (30 s after a 401). OkHttp pings every 15 s.
- **Reports** (`{"type":"state"}`): every open screen with `id` (`screen-<n>`), `sceneId`,
  `title`, `positionMs` (scene time, transcode offset included), `durationMs`, `playing`
  (ExoPlayer `isPlaying`: false while paused, buffering, ended or behind a sphere), `rate`,
  `focused`, and `projection` (today's server ignores it). Sent at once on open/close, play/pause,
  focus, scene, rate or a position jump of more than 400 ms, else every 750 ms, the heartbeat
  the server's 3 s dead-man waits for (same cadence as the web player). Polled every 250 ms.
- **What they see**: the state message also carries `view` (head `yawDeg` / `pitchDeg` from
  `scene.getViewerPose()`, `facing` = the id of the faced screen, `posture`) and, per screen,
  `yawDeg`, `pitchDeg`, `distM`, `widthM`, `rollDeg` from the head (all from `gaze/GazeFocus.kt`,
  every 100 ms). The console draws them as a map and "Looking at: screen N"; the reporter sends at
  once when the faced screen or posture changes or the head turns 10° (`HeadView.key`). Protocol:
  no new messages.
- **Headset values** the console shows as-is: `inOurApp` (activity resumed and the XR session
  focused, so false while the Meta menu is open or another app is in front), `battery` %,
  `charging`, `tempC` (battery temperature; `ACTION_BATTERY_CHANGED`, read at most every 10 s),
  `app` (version). Not reported: **worn / proximity**. Horizon OS has no public API for it; taking
  the headset off pauses the activity after a moment, which shows as `inOurApp: false`. The server
  stops the toy while `inOurApp` is false (Trove server README "Wearer away"; not a latch).
- **Headset off and the Meta menu** (`sync/SafetyEdges.kt` `AwayGate`, `TroveActivity.updateAway`): the
  activity paused (headset taken off, another app or a system screen in front) or the Meta menu open is one
  "away" period. Going away pauses every screen and Storm (remembering what played, `PlayerManager.pauseAll`)
  and reports at once (`playing: false, inOurApp: false`); coming back (resumed and the menu closed) resumes
  only what it paused. The menu closing while the headset is still off resumes nothing; a STOP ALL meanwhile
  forgets the list, so coming back doesn't start anything it stopped.
- **Commands**: `pause-all` (partner STOP ALL, or the server shutting down) pauses every screen,
  including ones behind a sphere, and forgets the Meta-menu / away pause so coming back doesn't
  restart them. A STOP ALL (partner's or the wearer's) seen in a `status` acts the same on its rising
  edge (`PersonStopEdge`), so one that came during a Wi-Fi gap still pauses the video and shows the room
  once the reconnect's status arrives; the server also resends `pause-all` then (both are harmless twice). Play is not blocked afterwards: the server's latch keeps the toy stopped until
  the partner presses Resume. `play` / `pause` / `seek ±ms` act on the named screen, else the
  focused one. Scene commands are the Director family below.
- **Passthrough on STOP ALL**: the partner's STOP ALL (not a server restart) also switches
  passthrough on so the wearer sees the room; it goes off again when the partner resumes. A
  paused 180/360 scene still covers the view (it is paused, not hidden).
- **Black Out**: the console's Black Out button sends `{"type":"command","cmd":"blackout","on","seq"}`.
  `TroveActivity.applyBlackout` does everything in one main-thread call, so it lands in one frame:
  the wall and, per screen, the video, controls and focus bar get `Visible(false)` (nothing is
  destroyed, spheres and fisheye included), every ExoPlayer volume goes to 0 (`PlayerSession.applyVolume`;
  the volume is always derived from "audible", so lifting restores exactly what was there), and
  `Room.blackout` forces passthrough off (pure black, not the room). Videos keep playing, nothing is
  paused or seeked, and no text or indicator is shown. The app answers at once with
  `{"type":"blackout","on","seq"}` (the console shows "confirmed by headset" and the round trip) and
  reports `blackout` in every heartbeat. It lifts: on the server's `on:false`; on STOP ALL (shows the
  room, as before); when the sync socket drops or the app stops (never black without the partner
  link; OkHttp's 15 s ping detects a half-open link); and by the wearer's escape (below). After a
  partner lift the sync tag says "Black Out lifted" for 4 s. Needs the Trove server branch `blackout-server`.
- **Wearer escape** (unreleased, replaces the Black-Out-only grip lift; Trove server README "Wearer escape"):
  hold **both grips for 2 s, in any state**: playing, Storm, Black Out, with the wall or panels up,
  even carrying a panel in one hand (one grip alone never counts; the timer in
  `BlackoutState.gripTick` fires once per hold, so release and squeeze again for another). It
  does, in one main-thread call (`TroveActivity.wearerEscape`): lifts Black Out, pauses every screen
  and Storm, switches passthrough on undimmed (`Room.stopOverride`), shows the tag **Stopped — you're in
  control** for 4 s (then **Stopped — resume from the console** while the server stays latched), and sends
  `{"type":"wearer-stop"}`; the server latches STOPPED "by the wearer" (toy stopped, the console shows it
  loudly). **Nothing resumes**: only the partner's (or your own console's) Resume does. If the link is
  down, everything local still happens at once; the stop is kept and sent after the reconnect, once, only
  if the server is not stopped by then and it is under 5 minutes old (`EscapeRules`). Until the server has
  been seen latched and released, the headset counts itself as stopped (`WearerEscapeState`), so Director
  scenes open paused. While the Meta menu is open the app gets no controller input (and is already
  paused). With hand tracking only: both open hands up in front of your face for 2 s does the same
  (above, "Hands"; needs the body tracking permission, untested on a device).
- **Director** (unreleased; Trove server README "Director commands"): the console's Director section and the flat
  web app's "Send to headset" send numbered commands `open` (new screen / replace a screen or the focused
  one, optional `startMs`), `swap`, `close`, `focus`, `close-all`, `random`, `storm` (with a query). They
  run on the main thread through `player/DirectorExecutor` and `PlayerManager` (`sync/DirectorRules` maps a
  command and the open screens to a plan, unit tested) and are answered with `{"type":"ack","seq","ok"}`
  (`ok:false` + `error`, e.g. "No such screen", or a scene that does not exist). Safety is not bypassed:
  while the server is latched or after the wearer's escape new scenes (and Storm) open **paused**; during
  Black Out they open hidden and silent; no command starts the toy or resumes anything.
- **Session timer** (Trove server README "Session timer"; `sync/SessionTimer.kt`, unit tested in
  `SessionTimerTest`): the server owns it, one per user, and puts it in every status as
  `"timer": {"endsAt": <epoch ms>, "setBy": "wearer"|"partner"}` or `null`. **Timer** on the focused
  screen's controls (secondary row) and on the Storm panel (bottom row) opens a chip row in the same
  place: 15 / 30 / 45 / 60 / 90 min, and while one runs +10 min and Cancel; a pick sends
  `{"type":"timer","op":"set","minutes":N}` / `{"op":"extend"}` / `{"op":"cancel"}` and closes the row
  (B or Done closes it too; `Row.Kind.TIMER`). The server answers `{"type":"timer-ack","op","ok","error?"}`
  (a refusal shows as a notice) and its next status. The time left is a tag next to Sound / Muted
  (`Timer 28:41`, amber in the last 5 minutes) and a pill on the Storm panel's title row, counted
  on the server's clock: the headset's wall clock plus the offset from the hello's `serverNow`
  (`SyncState.clockOffsetMs`), so it is right even when the Quest's clock is off. The server's `{"type":"timer","event":"warn","minutesLeft":5|1}`
  shows as the short sync notice ("5 min left", "1 min left"); at zero the server latches STOPPED
  ("Session timer ended") and sends `pause-all` with `reason: "timer"`: every screen and Storm pause
  (no passthrough, it is no emergency) and "Timer ended" shows. Notices never show during Black Out,
  and Black Out is unchanged. With the library and every control panel hidden (no sync tag in view),
  the warnings and "Timer ended" also show for 5 s in the button legend's small eye-buffer panel in
  front of you (`ButtonLegend.showNotice`, `SessionTimer.noticePanel`). The timer never starts or resumes anything; Resume works as after any
  latch (`status.resume`). No new panel (no compositor layer). Fixtures `p2s-timer-*`, `s2p-timer-*`,
  `s2p-status-timer*`, `s2p-cmd-pause-all-timer`.
- **Indicator**: a pill in the wall's top bar says only what is true about the toy, from the server's
  `status` (`armed` since server 0.4.x): **Link ok · toy unarmed** (grey; the server's fake driver, nothing
  reaches a toy), **Link ok · toy armed** / **toy on** (accent; armed, idle / running), **Link ok · toy
  stopped** (armed, latched), or just **Link ok** for an older server that doesn't say. Also **Sync:
  connecting / reconnecting** (amber) and, in red until Resume, **Stopped by your partner — wait for them
  to resume** after the partner's STOP ALL or **Stopped — resume from the console** after the wearer's
  escape. The screens' controls show only the reconnecting and stopped states.
- **Status pills** (`ui/StatusTags.kt`, unit tested): a pill is read-only; anything tappable is a
  button or chip. A screen's controls have one status area on the title row, at most three pills in
  this order: Stopped by your partner (red), an error, the stick hint while the panel is held (it
  replaces everything but the first two), sync reconnecting / notices, Sound or Muted, then 720p /
  Transcode. The server's own latch after
  a restart ("not started yet") and dead-man latches are left to the console.
- **Session ended** (a 401 on the wall): every screen and Storm is paused (as STOP ALL) and reported
  before the login shows (`AppModel.onSessionLost`), so nothing keeps playing on a dead session.
- **Needs** the Trove server with `/api/sync/ws` (branch `master` from 0f481a3). The
  "Stopped by your partner" tag after a reconnect needs branch `sync-player-server`, which sends the
  current latch right after `hello`; older servers only send it on the next change.
- **Tests**: `SyncProtocolTest` (encoding, decoding, cadence, tag, head view, `armed`), `SafetyEdgesTest` (away
  periods, the STOP ALL edge), `AppModelSessionTest` (401 stops playback before the login), `DirectorRulesTest` (Director decode,
  ack / wearer-stop encoding, command-to-plan rules, escape rules and state), `BlackoutStateTest` (the
  grip hold timer), `SyncClientTest` (cookie,
  reports, commands, Director acks, queued wearer stop, status, reconnect, a STOP ALL seen only in the
  status after a reconnect, against MockWebServer) and `SyncLiveServerTest`, skipped
  unless pointed at a local, unarmed Trove server:
  ```
  # in a trove checkout (npm install), with any fake Stash on :19871 serving /scene/42/funscript
  DATA_DIR=$(mktemp -d) PASSWORD=e2e-pass-12345 node server/cli.ts adduser e2e
  DATA_DIR=<same> PORT=19870 HOST=127.0.0.1 STASH_URL=http://127.0.0.1:19871 SYNC_PARTNER_REQUIRED=0 node server/index.ts
  # here
  TROVE_SYNC_E2E_URL=http://127.0.0.1:19870 TROVE_SYNC_E2E_USER=e2e TROVE_SYNC_E2E_PASSWORD=e2e-pass-12345 \
    ./gradlew testDebugUnitTest --tests '*SyncLiveServerTest*'
  ```
  It checks what the console sees (screen, headset, clock within 1 s), Resume -> the fake driver
  starts, remote seek, STOP ALL -> pause-all + "by partner", and the dead-man latch when the app
  disconnects while the device runs. Never point it at the real server.

## VacuGlide panel (milestone 3, app side)

The controls of the Stash plugin "VacuGlide Sync", on the headset. A sub-panel docks under the
focused screen's control panel (Storm never drives the toy, so only with screens) and appears by
itself when the server says it is **armed** and the VacuGlide is **online** (status `vg.connected`:
the Trove server probes Autoblow every 10 s while a player is connected). It is a chip ("VacuGlide ▸")
until you open it; **Collapse ▾** folds it back, **Hide** removes it (a **VacuGlide** button next to the
title on the controls brings it back; the choice is remembered). Unarmed, offline, reconnecting, controls
hidden, another screen focused, or Black Out: it is gone. Code: `sync/VacuGlide.kt` (rules + state,
unit tested), `player/VacuGlidePanel.kt` (Compose), `PlayerSession.refreshVg` (entities), two eye-buffer
panels (no compositor layer, so the 16-layer budget is untouched).

| Control (plugin label) | What it does |
|---|---|
| Suction **Tighter** / **Looser** (hold) | Press = the valve opens, release = it closes. A tap still nudges (server keeps it open 250 ms), at most 5 s per press, one valve at a time. Which Autoblow valve each opens is the server's (`VALVE_NAMES`: Tighter = `stroke-plus`, Looser = `stroke-minus`, swapped after 0.5.2 when the real device showed them reversed); the app sends only "tighter" / "looser" |
| Keep suction Off / Low / Medium / High | The server tops up with short Tighter pulses while the script plays |
| **Stick: Volume / Suction** (Trove only, remembered, pref `vacuglide_stick_suction`) | Suction: the thumbstick up = **Tighter held**, down = **Looser held** (the valve is open while the stick is pushed, closed when it centres), instead of the volume. Same rules as the buttons: 5 s cap per push (push again for more), one valve at a time (a second stick or a button is refused while one holds), released by everything below. Only while this panel would be up (armed, toy online, a screen and not Storm, no Black Out); otherwise, and pointing at a panel that scrolls, the stick is what it always was. Left / right still seek. The button legend says which it is |
| Offset −50 / +50 ms (±5000) | Positive = the device moves earlier; the measured network latency is added by the server |
| Intensity −10 / +10 (20–200 %) | Scales the strokes; the script is re-uploaded (a few seconds) |
| Speed limit Off / 500 / 400 / 300 / 200 /s | Limits every move; same re-upload |
| Reload script | Forget the upload and send it again |
| Script (only when the scene has several funscripts) | One chip per variant Stash Interactive Tools lists (Default, FULL, HALF…), the one in effect highlighted. A tap sends the choice; "Loading script…" until the server has it on the toy (stop, upload, restart at the current clock). The row takes the place of the suction hint |
| Toy state | Pill: Idle / Script playing / Script paused with the device speed, and the held valve; the line below shows the plugin's hints, or a refusal / error |

Not mirrored: the device's own buttons driving the video, the device token box, and the plugin's
"Stop device": use the partner's STOP ALL or both grips (which also close any valve).

**A held valve can't get stuck.** It is released on: button up (or, for the stick, the stick centring, pushing another way, pointing at a scrolling panel, switching back to Volume), the pointer leaving the button or
the panel (a cancelled gesture), the panel closing, collapsing or hiding, B, the screen's controls
going away (focus moved, Hide controls, screen closed), link loss, the app pausing / headset off /
Meta menu, Black Out, a partner STOP ALL or the wearer escape, the toy going offline, a refusal from
the server and 5 s. The server closes it too (5 s, socket closed, silent headset, STOP ALL, Black
Out, shutdown). Pressing is refused (and the buttons grey out) during a person's STOP ALL and Black Out.

Wire: Trove server README, "VacuGlide controls"; status `vg`, player `vg`
(`valve` / `set` / `reload`), server `vg-ack`; fixtures `p2s-vg-*`, `s2p-vg-*`, `s2p-status-vg-*`.
Settings are absolute values computed from what the panel shows, so quick taps add up; the server's
next status is always the truth. Tests: `VacuGlideTest` (protocol, visibility, hold/release safety incl. the stick's, steps,
B and the pointer region), `InputRouterTest` (the stick's press / release), `SyncContractTest`, `SyncClientTest`.

**Script variants** (server README "Script variants"): the same choice is a **Script** row in the scene details
(any interactive scene with two or more scripts, whether it plays or not). The server keeps the choice per user and
scene and is the only source of truth: the app shows the tapped chip until the server's status (or, for another scene,
its `/api/scenes/<id>/scripts` list) shows it, and remembers nothing itself. Wire: player `vg` op `script`
(`sceneId`, `id`), status `script` (`sceneId`, `options`, `chosen`, `loading`, `error`), `vg-ack` op `script`. Code:
`sync/ScriptChoice.kt` (`ScriptPicker`, unit tested in `ScriptPickerTest`).

**Needs the device**: the valves, offset and re-upload against a real VacuGlide, switching scripts mid-play, the panel's look at
arm's length, the hold gesture with a real ray (cancel on leaving the button), and the placement under
sphere (180/360) controls.

## Build

Needs the Android Studio JBR (the system `java` is JRE-only) and the Android SDK with
platform `android-36.1` (installed here). No global Gradle: use the wrapper.

```sh
cd ~/code/trove-quest
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk      # or put sdk.dir=... in local.properties
./gradlew assembleDebug                    # -> app/build/outputs/apk/debug/app-debug.apk (~94 MB, debuggable)
./gradlew assemblePublish                  # -> app/build/outputs/apk/publish/app-publish.apk (what is published)
./gradlew testDebugUnitTest                # JVM tests: projection mapping, fisheye mesh, passthrough rules, API client, cookie jar, pairing, QR, updates
./gradlew lintDebug                        # report in app/build/reports/
```

Optional live check (one deliberately wrong login; counts toward the server's per-IP
lockout of 10 failures / 15 min, so don't loop it):

```sh
TROVE_LIVE_URL=https://trove.example.com ./gradlew testDebugUnitTest
```

Versions (see `gradle/libs.versions.toml`):

| | Version | Why |
|---|---|---|
| Meta Spatial SDK + `com.meta.spatial.plugin` | 0.14.0 | Latest on Maven Central (2026-09); same as Meta-Spatial-SDK-Samples `main` |
| Gradle | 9.4.1 | Same as the samples |
| AGP | 8.13.2 | Samples use 8.11.1; AGP 9 changes Kotlin setup and is untested with the Spatial plugin |
| Kotlin | 2.2.21 | |
| compileSdk / minSdk / targetSdk | 36.1 / 34 / 34 | Horizon OS = Android 14; samples pin min = target = 34 |
| Media3, Compose BOM, Coil, OkHttp | 1.8.0, 2025.10.01, 3.3.0, 5.1.0 | Newest releases need compileSdk 37 (and so AGP 9) |

There are no Spatial Editor scenes: everything is built in code, so the build does not
need the Spatial Editor CLI (which has no Linux version).

## Sideload

1. Developer mode needs a **verified** Meta developer account (an organisation at
   developers.meta.com, account verified). Turn on Developer Mode for the
   headset in the Meta Horizon phone app → Devices → Headset settings → Developer Mode.
2. Connect the headset by USB-C, put it on and allow USB debugging for this computer.
3. Install:

   ```sh
   /opt/android-sdk/platform-tools/adb devices        # should list the Quest
   /opt/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. In the headset: App Library → filter **Unknown sources** → Trove.

Logs: `adb logcat -s PlayerSession:* ExoPlayerImpl:* MediaCodec:* AndroidRuntime:*`.
Keyboard binding: `adb logcat -s TroveIme:* KeyboardInputMethodService:* RemoteInputConnectionImpl:*`
(each field change should give `onStartInput: restarting=true`; `dumpsys input_method`
shows the bound field's `mCurrentEditorInfo`).

The headset must reach your Trove server.
Cleartext HTTP is allowed only for `localhost`; add a `<domain>` entry in
`res/xml/network_security_config.xml` to test against a plain-HTTP LAN server.

Sideloading by cable is needed once: for the first install, and once more to get a build with the
updater (0.2.0) onto a headset that has 0.1.0. After that, updates come from the server (below).

## Updates (self-update from the Trove server)

The Meta Store is not an option (adult content puts the developer account at risk), so the app
updates itself from the Trove server on the server.

**How it works**

1. `scripts/publish-apk.sh` (on the PC) builds the APK, reads its versionCode/versionName with
   `aapt2`, takes the notes from `CHANGELOG.md`, computes the SHA-256 and copies the APK plus a
   `latest.json` to `<DATA_DIR>/apps/quest/` on the server (the server's `DATA_DIR`).
2. The server answers `GET /app/quest/latest.json` and serves `GET /app/quest/<file>.apk` (with
   `Range`), both behind the normal login (Trove server README "Quest app updates", `server/apps.ts`).
3. The app (`update/`) fetches `latest.json` with the session cookie when it starts or resumes and
   when the wall opens, at most once per 6 h (`UpdateThrottle`; the time of the last answered check
   is in SharedPreferences). A manual **Check for updates** ignores the 6 h. If the server's
   versionCode is higher than the installed one, the wall shows a card.
4. **Install** downloads the APK with the same OkHttp client (so the cookie goes along) into the
   app's cache (`cache/updates/`, resumes a broken download), checks size and SHA-256, checks that
   the APK is our package and signed with the same key as the installed app, and commits it to a
   `PackageInstaller` session. Results come back to `InstallResultReceiver`.

| File | What |
|---|---|
| `update/UpdateInfo.kt` | `latest.json` model + checks, versionCode comparison, 6 h throttle |
| `update/UpdateClient.kt` | check + download (Range resume, size + SHA-256 check); plain JVM, unit tested |
| `update/Sha256.kt` | hashing |
| `update/ApkInstaller.kt` | permission check, signer check, PackageInstaller session, result receiver |
| `update/UpdateModel.kt` | state machine for the card (Compose state) |
| `update/UpdateUi.kt` | account button, account row, update card |

**What you see in the headset**

- On the wall's top bar, the account button (`name · v0.2.0`). Clicking it opens the account row:
  version and build number, server, update status, time of the last check, **Check for updates**
  and **Log out**. A dot (`name · v0.2.0 •`) means an update is waiting that you put off with Later.
- When there is an update: a card under the header, "Update available: 0.3.0 · You have 0.2.0 ·
  download 95 MB", the release notes, **Later** and **Install** (the card has an accent edge, red
  when it failed, green once installed). Nothing blocks the wall or the
  player; Later hides the card until the app restarts.
- Install: a progress bar ("Downloading 42 % (40.0 MB of 94.7 MB)").
- **First time only**: the system installer asks to allow Trove to install apps ("Install
  unknown apps") as part of its own confirm flow. Since 0.2.2 the app no longer opens that Settings
  screen itself: in 0.2.1 it did, and on Horizon OS closing that 2D window left focus on vrshell's
  placeholder, so Trove never resumed and looked frozen until it was reopened. Now Install always
  commits the PackageInstaller session and the result comes back by broadcast, so nothing waits for
  `onResume`. If Trove still seems stuck after a system window, close it and open it again.
- The system dialog "Do you want to update this app?" → **Update**. Trove closes while Android
  replaces it; open it again from the App Library (Unknown sources). Android does not restart it.
- Once Trove is the app's installer of record (after its first self-update), Android may skip the
  confirm dialog on later updates (`USER_ACTION_NOT_REQUIRED` + `UPDATE_PACKAGES_WITHOUT_USER_ACTION`);
  if it doesn't, the dialog shows as above.
- Errors show on the card with **Retry**: server unreachable, not logged in, damaged download
  (checksum), different signing key, install cancelled.
- An older server without the update routes shows "This server has no app updates" in the About row.

Logs: `adb logcat -s TroveUpdate:*`.

### Versioning

- `version.properties` holds `versionCode` (the last **published** build) and `versionName` (semver,
  the version to publish next). `app/build.gradle.kts` reads both; `-Ptrove.versionCode=` /
  `-Ptrove.versionName=` override them for one build.
- `scripts/publish-apk.sh` builds with `versionCode + 1` and, after a successful publish, writes
  that number back to `version.properties`; commit it (the script prints the command). So every
  published build has a higher versionCode, which is the only thing the app compares. Android also
  refuses an update with a lower or equal code.
- `versionName` is up to you: bump it (0.2.0 → 0.2.1 / 0.3.0) and add a `## [x.y.z]` section to
  `CHANGELOG.md` before publishing. The script refuses to publish without notes for that version.
  Publishing a rebuild under the same name is possible (new code) but its notes are the same.
- The script also refuses when the server already has an equal or higher versionCode.

### Publishing

```sh
cd ~/code/trove-quest
# 1. set versionName in version.properties, add "## [x.y.z]" with user-facing notes to CHANGELOG.md
scripts/publish-apk.sh --dry-run   # builds, checks, stages build/publish/ (APK + latest.json); copies nothing
scripts/publish-apk.sh             # same, then copies to the server (over ssh) and bumps versionCode
git commit -m "Release x.y.z" version.properties CHANGELOG.md && git tag vx.y.z
```

The script builds the **`publish`** variant (`assemblePublish` ->
`app/build/outputs/apk/publish/app-publish.apk`): a release-style build that is **not debuggable**
(Compose and ART run at full speed; `BuildConfig.DEBUG` is false), no R8 / minify yet, same
applicationId and version properties, signed with the same debug key as the debug builds published
up to 0.5.1, so it installs over them in place. What `BuildConfig.DEBUG` turns off in it is only
diagnostics: the Storm invariant audit (tag `TroveStorm`), the VacuGlide panel audit (`TroveVg`),
the IME debug broadcast receiver, the input router / hold / hands-escape debug logs. Nothing about
safety or the updater depends on it.

Options: `--apk FILE` publishes an existing APK (its versionCode must be higher than
`version.properties`), `--release` builds the release variant (needs the release keystore, below),
`--debug` builds the old debuggable debug variant,
`--keep N` APKs kept on the server (default 3). `TROVE_SERVER_HOST`, `TROVE_SERVER_SSH_KEY`, `TROVE_SERVER_APPS_DIR`
override the target. Files go up under a temp name and are renamed, APK first, so the app never
sees a `latest.json` for an APK that isn't there yet; both are `chown 1000:1000`.

### Signing

Android installs an update only if it is signed with the **same key** as the installed app;
otherwise it fails (the app catches this before installing and says so).

- Debug and `publish` builds are signed with the debug key in `~/.android/debug.keystore` on the machine that built them. A build from another machine (or after that file is lost) cannot update an existing install. Back the file up if you stay on it.
- Recommended: a dedicated release keystore. Steps (nothing here is generated or stored by the repo):
  1. `keytool -genkeypair -v -keystore ~/keys/trove-release.jks -alias trove -keyalg RSA -keysize 4096 -validity 36500`
     and keep the file and passwords in a password manager plus a backup. Lose it and no update can
     install over that build again.
  2. Give it to Gradle through the environment (`TROVE_KEYSTORE`, `TROVE_KEYSTORE_PASSWORD`,
     `TROVE_KEY_ALIAS`, `TROVE_KEY_PASSWORD`) or a git-ignored `keystore.properties` in the repo
     root (`storeFile=`, `storePassword=`, `keyAlias=`, `keyPassword=`). Without either, the
     release variant builds unsigned.
  3. Switching keys needs one cable install: `adb uninstall app.trove.quest` (drops app data, so
     log in again) and `adb install` the first release-signed build. From then on publish with
     `scripts/publish-apk.sh --release`.

## Server changes needed

- **Self-update** needs the Trove server with `/app/quest/*` (branch `self-update`, its README
  "Quest app updates"). An older server answers with the web app's HTML; the app then shows "This
  server has no app updates" and carries on.
- **Device pairing** needs the Trove server with `/api/pair/*` and the `/pair` page
  (branch `device-pairing`, see its README "Device pairing"). Against an older server the
  pairing side shows the server's error ("Not found") and retries; the password form
  still works.

- **Funscript sync** needs `/api/sync/ws` (Trove server `master` from 0f481a3); the
  latch-on-connect status is on branch `sync-player-server` (see "Funscript sync").

Otherwise the native client uses the existing cookie session: it sends no `Origin` header,
so `checkOrigin` passes, and the media proxy already accepts the cookie.

Worth doing later:

- **HLS for transcodes (M2)**: the server drops Stash's HLS/DASH streams because the
  playlists embed upstream URLs with the API key. Rewriting playlist URLs to `/media/...`
  would give seekable transcodes; today only progressive mp4/webm transcodes are offered,
  and those restart at 0 on seek.
- **Low-res streams for extra screens (M2)**: the app uses
  `/media/scene/<id>/stream.mp4?resolution=STANDARD_HD&start=<s>` (live transcode). Transcoding
  8K HEVC on the server is CPU-heavy with several unfocused screens, so a pre-generated low-res proxy
  may be the better source later.
- **Bearer token** (optional): `POST /api/login` could also return the token for
  `Authorization: Bearer`, so clients don't need a cookie jar. Not needed now.
- **Play tracking** (optional): an endpoint to bump Stash's play count / resume time.

## Milestones

1. **M1**: login, wall, one player with projection mapping and controls.
2. **M2 multi-screen** (built, not yet run on the headset): up to 6 `PlayerSession`s, focus =
   sound + original stream, 720p transcodes for unfocused big files, arc placement, spheres
   single-scene, saved layout with "Restore last screens". Resizing, push/pull and tilt with the
   thumbstick while gripping came in 0.2.1. Layout presets (row / arc / stack) are not done.
3. **M3 VacuGlide funscript sync + partner STOP ALL**: fetch `/media/scene/<id>/funscript`,
   drive the device via the server or the Autoblow cloud from the focused screen's
   ExoPlayer clock. Ships together with the first part of M5 (monitor + STOP ALL +
   dead-man stop): toys never ship without a partner stop button. **App side built** (the
   server drives the device; see "Funscript sync"), not yet run on the headset.
4. **M4 fisheye + passthrough** (built, not yet run on the headset): a fisheye dome mesh
   with the video panel's texture for `fisheye190/200/220`, passthrough with 0 / 50 / 80 %
   darkening behind flat/3D screens.
5. **M5 partner console** (`/companion` on the server, phone browser, normal login).
   Purpose: in a sensory-overload session the partner ("top") monitors what the wearer
   sees and controls the session without asking "what can you see", which matters most
   when the wearer is restrained or can't easily speak or remove the headset.
   - Mirror state, not pixels: the app pushes open screens, scene ids, times, layout and
     focus over a websocket; the phone plays the same scenes in sync as small 720p
     muted videos (Stash transcodes from the server), laid out like the headset. No encoder
     load on the headset; works for the WebXR player too.
   - App status the phone shows: headset off (proximity), Meta menu open / left our app,
     headset battery and temperature, stalled stream, toy disconnected.
   - STOP ALL (top priority, ships with M3): every toy to zero, all video paused,
     optional passthrough so the wearer sees the room.
   - Dead-man rule: if the headset, the phone page or the server loses the link, toys
     stop (same rule as the panel firmware).
   - Remote control: toy levels/intensity, pause/seek, open/close scenes on the headset.
   - Optional hands-free wearer signal (e.g. gaze straight up for 3 s or a head-shake
     pattern) that alerts the phone and can trigger STOP ALL. Extra only: the people
     involved keep their own non-verbal signals as the real safety mechanism.
   - Rejected: streaming the rendered view from the app (only covers our app, competes
     with the video decoders, two distorted eye images). For real pixels of any app use
     web scrcpy (`ws-scrcpy` on the PC/the server over wireless adb), occasional use only
     since wireless adb resets on reboot.

## Known gaps

**Confirmed on the headset** (as of 0.5.1): multi-screen, dragging a card out of the wall,
click to play/pause, grip moving and tilt, Storm (Focus, scrub bar, info panel, re-Storm),
Black Out, toy sync with a real VacuGlide, starting the toy from the headset, the VacuGlide panel,
self-update via Check for updates (used for every release) and device pairing.

**Not yet verified on the headset** (unit tested or run against a local server only):

- Switching the funscript while the toy runs (0.5.1).
- The Library sections (Scenes / Performers / Studios / Tags), facet chips, scene details,
  marker ticks and resume points.
- Storm under heavy load: whether the `LayerBudget` and decoder limits hold with many tiles next
  to open screens.
- Fisheye (M4): orientation, which eye gets which half, the seam, and sharpness against the old
  SBS 180 layer.
- Passthrough (M4): behind quad screens, the LUT darkening levels, and on STOP ALL.
- Hand tracking: pinch as trigger, pinch-drag and thumb microgestures.
- 8K HEVC decode and sharpness claims, and stereo/equirect orientation (which way a 180 dome faces).
- Typing on the Quest keyboard (the keyboard fix is checked with adb only).
- Partner-side details: the Meta-menu and headset-off reports (`inOurApp`), battery/temperature
  values, and how closely the reported clock keeps a real device to the picture.
- Self-update's first-time permission path, and whether later updates skip the confirm dialog.

**Other gaps**

- Facets are not kept across app restarts (the wall starts unfiltered, like the tab and sort).
- Resume points are local to each headset; no play-count tracking yet (server `play-tracking`).
- Marker jumps (and the seek bar) do nothing while a screen plays the unfocused 720p transcode; the
  touch focuses it, so they work once it is back on the original.
- `DropdownMenu` is avoided (popups in Spatial panels are unreliable); pickers are chip rows.
