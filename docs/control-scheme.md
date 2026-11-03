# Trove control scheme (controllers and hands)

Status: **implemented** (branch `controls-impl`). The rules are plain Kotlin in
`app/src/main/java/app/trove/quest/input/ControlScheme.kt` (unit tested in `ControlSchemeTest`),
run by `InputRouter` / `RouterCore` (`InputRouterTest`). Deviations from the text below: the both-grips
escape stays in `BlackoutGripSystem` (unchanged); put back freezes the panel until grip is released
instead of ending the grab (R5 fallback); the legend is one panel in front of you, not attached to
each controller; the 0.5 s escape ring and the per-context auto legend (3 times) are not built.

Base: 0.3.0 (`main`), plus what those three branches add: look-to-focus, posture presets and
"Bring screens to me" (`gaze-posture`), the universal wearer escape and Director commands
(`director`), `LocalPanelHeld` (a held panel doesn't scroll, `fixes-040`).

## 0. Today (0.3.x), and what goes wrong

| Input | Where it is read | What it does today |
|---|---|---|
| Trigger | SDK `PanelInputListener` (Compose), `PlayerSession` / `StormTile` scene-object listeners, `CardDragSystem` | Click on panels; on a video focus, then play/pause; Storm tile select (Focus: swap to the centre; never opens the video); hold on a card = new screen; trigger-drag a card off the wall |
| Grip | SDK `GrabbableSystem` | Move the panel you point at (wall, a flat screen with its controls, sphere controls, Storm panel) |
| Grip + stick | `HoldSystem` + `HoldRules` | ↑↓ distance (or size), ←→ tilt (first direction locks), stick press (centred) or **B/Y** toggles distance/size |
| Both grips 2 s | `BlackoutGripSystem` + `BlackoutState.gripTick` | Lifts Black Out; on `director`: the universal escape (stop, room, lift Black Out) in every state |
| Stick (not holding) | SDK `PanelInputListener` | Scrolls the Compose panel under the ray (SDK locomotion is off since 0.2.6). On a video: nothing |
| A / X | SDK `PanelInputListener` | Click, like the trigger (`PanelConfigOptions.DEFAULT_CLICK_BUTTONS` = A, X, TriggerL, TriggerR; read from the 0.14 toolkit jar). Undocumented, so nobody relies on it |
| B / Y | `HoldSystem`, `CardDragSystem` | Toggle size mode while gripping; cancel a card drag. Otherwise nothing |
| Menu (left) | nobody | Nothing |
| Meta (right) | system | Meta menu; the app pauses on `SessionState.VISIBLE`, resumes on `FOCUSED` |
| Hands | SDK | Pinch = trigger (assumed, untested); no grip, so no moving panels; palm-pinch = system. Escape since `hand-escape`: both open hands up, 2 s (section 2b) |

Problems: B means nothing almost everywhere, there is no "back"; the stick does nothing when you
watch; play/pause needs the ray on the video (hard lying down); six classes read `Controller` bits
on their own, each with its own edge and grip rules; nothing tells the wearer what a button does.

## 1. Principles

1. **One meaning per button, everywhere.** A plays, B goes back, the stick scrolls or seeks, Menu
   is the library, grip moves, trigger selects. A context may make a button do nothing; it never
   makes it do something unrelated. Chords (grip held + something) are their own, separate meaning.
2. **Mirrored hands.** X = A, Y = B, left stick = right stick. Every action except Menu works
   with either hand alone (lying on one arm, a drink in the other hand). Menu is left-only by
   hardware; B reaches the library from the right hand.
3. **Stop always wins.** Both grips held 2 s is the wearer's escape in every state (director
   branch), including Black Out and while carrying a panel. No mapping in this scheme delays,
   hides or reuses it. Long presses only ever *pause* (they remove stimulation, never add it), and
   nothing on a button resumes the toy or lifts STOPPED.
4. **Blind-safe.** In Black Out the wearer sees nothing, so every button is inert except the
   escape: a fumbled press never changes hidden state.
5. **Pointing optional.** Everything you do while watching (play/pause, seek, volume, library,
   back) works without aiming the ray, so it works lying down, eyes on the video. The ray is for
   choosing things.
6. **B is a shortcut, never the only way.** Every layer B closes also has a visible on-panel
   button (Done, ‹ Back to library, Back to video, Hide library, ✕). Hand users have no B.
7. **The SDK keeps what it does well.** Trigger clicks on Compose panels, grip grabs and stick
   scrolling of the panel under the ray stay the SDK's. The scheme only adds meaning where the SDK
   has none (face buttons, Menu, the stick when the ray is not on a scrolling panel).
8. **Discoverable.** One press (stick press, centred) shows what every button does right now,
   generated from the same table the router runs (`ControlScheme.legend`), so help can't drift.

## 2. Mapping per context

Contexts are worked out from state each frame (`ControlScheme.contextOf`), top one wins:
Login > Black Out > Dragging > Holding > Keyboard > Row open > Scene details > Library > Storm >
Sphere > Video. STOPPED is not a context: it changes no mapping (`stoppedChangesNoMapping`).

Short = released before 0.8 s. Long = held 0.8 s (fires while still held; a fill ring shows from
0.25 s). Stick steps: one at the push, repeats after 0.45 s, then every 0.25 s; the first direction
owns the push until the stick is centred (the Quest stick arcs a sideways push into a diagonal).
Stick press counts only with the stick centred now and for 200 ms before (`HoldRules.acceptToggle`).

### 2a. Controllers

| Context | Trigger | Grip | Grip + stick | Stick (not holding) | Stick press | A / X | B / Y | Menu (left) | Both grips 2 s |
|---|---|---|---|---|---|---|---|---|---|
| **Login** | Select, type in fields | – | – | Scroll (SDK) | Legend | – | Close keyboard if open | – | Escape |
| **Library wall** (no screens) | Open card · hold = new screen · drag out | Move wall | Wall: ↑↓ distance/size, ←→ tilt | On the wall: scroll grid / chip rows (SDK). Off it: – | Legend | – | – (root) · long: – | – | Escape |
| **Library over video / Storm** | as above | Move wall | as above | On the wall: scroll (SDK). Off it: seek / volume of the focused screen | Legend | Play/pause focused · long: pause everything | **Back to video** (hide library) · long: home | Hide library | Escape |
| **Scene details** | Chips, markers, Play, Back | Move wall | as above | Scroll (SDK) | Legend | Play/pause focused (if any) | **Back to library** (grid) · long: home | Hide library (if media) | Escape |
| **Video, focused** (flat/3D) | Play/pause | Move screen + controls | Screen: ↑↓ distance/size, ←→ tilt (roll) | ←→ seek ∓10 s (30 s steps after ~2 s held) · ↑↓ volume ±10 % | Legend | Play/pause · long: pause everything | **Close video** (closes the focused screen; the library shows once none is left) · long: show / hide the library | Library · **long: hide / show controls** | Escape |
| **Video, controls hidden** | Brings the controls back (no play/pause) | as above | as above | as above (no volume pill) | Legend | as above | **Close video** (closes the focused screen; the library shows once none is left) · long: show / hide the library | Library · long: show controls | Escape |
| **Video, unfocused** | Focus it (sound + original stream) | Move it | as above | Acts on the *focused* screen | Legend | Play/pause focused | Library | Library | Escape |
| **Sphere** (180/360/fisheye) | Play/pause (anywhere on the video) | Move its controls | Controls: ↑↓ distance/size, ←→ tilt | seek / volume | Legend | Play/pause · long: pause everything | Library | Library | Escape |
| **Storm wall** (no screen) | – | Move Storm panel (tiles are fixed) | Panel: as above | → next clip (selected tile, else all; the Focus centre is never cut) · ← – · ↑↓ volume | Legend | Pause all / Play all · long: pause everything | **Exit Storm** (closes it: back to the screens, else the library) · long: home | Library (or Storm controls when hidden) | Escape |
| **Focus info panel** (right of the centre) | Chips: facet = re-Storm with it, "Library" = open the library filtered by it, marker = seek the centre; Collapse / Hide | – | `Target.PANEL`: the SDK scrolls it, the router does nothing (the stick never seeks the centre from here) | Legend | as Storm | as Storm | Menu brings a hidden info panel back together with the Storm controls (`Snapshot.stormPanelHidden` = either hidden) | Escape |
| **Storm tile** | Sound to it, again = nothing (Focus layout: a ring tile swaps with the centre, the centre = nothing). Never opens the video: Solo is the Storm panel's button. Triggers within 300 ms of each other count once | – | – | (pointing at a tile) → next clip for the selected tile; on the Focus centre ←→ seek -10 / +10 s (`Target.CENTRE`) | Legend | as Storm | as Storm | as Storm | Escape |
| **Holding a panel** (grip down) | Ignored on videos (`ScreenClick`); see risk R3 | (holding) | Own stick: ↑↓ distance (or size), ←→ tilt (roll). **Other stick ↑↓: size, ←→: pitch** (no mode needed; pitch only with Face me off) | (= grip + stick) | Gripping hand: distance ↔ size. Other hand: – | Play/pause focused | **Put back** (drop it where you picked it up) | – | Escape (even with one panel in hand) |
| **Dragging a card** | Let go: drop (off wall = new screen, over wall = cancel) | Cancel | – | – (later: ↑↓ drop distance) | – | – | **Cancel** | – | Escape |
| **Keyboard open** | Type (system) | – | – | – | – | – | **Close keyboard** (text kept) | – | Escape |
| **Row open** (View, Stream, Markers, Timer on controls; Timer on the Storm panel; the whole VacuGlide panel; Sort, Account on the wall) | Pick a chip | Move panel | as above | On the row: scroll (SDK); off it: seek / volume | Legend | Play/pause focused | **Close the row** (= Done) | Library | Escape |
| **Black Out** | – | – | – | – | – | – | – | – | **Escape** (lifts Black Out, stops, room) |
| **STOPPED** (partner STOP ALL or own escape) | as the context below it | as below | as below | as below | as below | as below (play is allowed; the server latch keeps the toy stopped) | as below | as below | Escape again (idempotent) |

| **VacuGlide panel** (under the focused screen's controls, only while the server is armed and reports the VacuGlide online) | Buttons; **Tighter / Looser are hold-to-use** (press = valve open, release = closed) | Move the screen (the panel hangs on its controls) | `Target.PANEL`: the stick does nothing there (never seeks) | Legend | Play/pause focused | Whole panel open: **fold it into the chip**; the chip is not a row, B walks on as for a video | Library | Escape (also closes the valve) |

Notes:
- **VacuGlide panel**: `VacuGlideControl` (`sync/VacuGlide.kt`), `VacuGlidePanel.kt`. Shown as a chip ("VacuGlide ▸") by default; the choice chip / whole / hidden is remembered. A held valve ends on release, the pointer leaving the button or the panel, the panel closing or hiding, B, the controls going down (focus moves, Hide controls), link loss, the app pausing or the Meta menu, Black Out, a STOP ALL or the wearer escape, a refusal from the server, and 5 s (the server adds its own 5 s and closes it when the socket closes). Hidden panel: **VacuGlide** next to the title on the controls brings it back.
- **"Stick (not holding)" depends on the ray of that hand.** On a Compose panel whose content
  scrolls (`Target.PANEL`) the SDK scrolls it and the router does nothing. On a video, a Storm tile,
  a control panel with no row open (`Target.CONTROLS`) or nothing, the stick drives the focused
  screen. One sentence for users: *"The stick scrolls what you point at; otherwise it seeks and sets
  the volume."*
- **Seek acts on the focused screen**, like A, never on the pointed-at one: focus is the one
  "current screen" concept (trigger, look-to-focus and Director all move it).
- **Volume** is a new app volume (0–100 %, remembered), applied to the screen with sound and to
  Storm; a pill "Volume 70 %" shows on the focused controls. The headset's own volume keys still work.
  A **Vol** slider (10 % stops) next to the seek bar on the screen controls and in the Storm panel's
  Clip row sets the same level and follows the stick.
- **Menu with nothing behind the library** does nothing (the library is all there is).
- **Meta button**: system. Unchanged (menu pauses, closing resumes). We never read it.

### 2b. Hands (hand tracking)

The SDK 0.14 has `MicrogesturesSystem` / `Microgestures` / `MicrogestureBits` (thumb tap and four
swipes per hand, found in the toolkit jar), so microgestures look reachable; needs a device test
(and possibly a manifest feature). Pinch is assumed to arrive as the trigger bits (untested).

| Hand input | Means | Controller equivalent |
|---|---|---|
| Index pinch (ray) | Select / click | Trigger |
| Pinch and hold | Long-press (card: new screen) | Trigger held |
| Pinch + drag | Card drag out of the wall; scroll if the SDK supports it for hands | Trigger drag |
| Left palm-up pinch | Library (system delivers it as the Menu button; verify) | Menu |
| Right palm-up pinch | Meta menu (system) | Meta |
| Thumb tap | Play / pause focused (long press not available) | A / X |
| Thumb swipe back (towards the palm) | Back | B / Y |
| Thumb swipe left / right | Seek −10 / +10 s (Storm: → next clip) | Stick ←/→ |
| Thumb swipe forward | Library | Menu |
| Grip | none: panels can't be grabbed with hands. Placement for hand users = posture presets and "Bring screens to me" (`gaze-posture`) | – |
| Escape | **Both open hands up in front of the face, 2 s** (see "Hands escape" below). Built, untested on a device. | Both grips 2 s |

#### Hands escape

The same universal escape as both grips 2 s (`TroveActivity.wearerEscape`: STOP ALL from the wearer,
`{"type":"wearer-stop"}`, room shown, Black Out lifted), in every state. The gesture is a two-handed
"stop" sign: **both hands raised in front of the face, open (fingers straight, thumb away from the
index), fingers up, palms flat facing forward or facing you, held 2 s**.

- Rule: `input/HandEscape.kt` (`HandPose` + the `HandEscape` hold timer), plain Kotlin, unit tested
  with synthetic joints (`HandEscapeTest`). Each frame needs: both hands and the head tracked; every
  finger but the thumb straight and extended (a fist, a pinch, the curled fingers of a thumb
  microgesture never count) and the thumb tip away from the index tip; fingers within ~50° of up;
  each palm plane across the line of sight (palm normal within ~45° of forward or back; either sign
  counts, so it does not depend on the joint orientation convention); both palms from chin to just
  above forehead height, at least 15 cm in front of the head (horizontally, so no head yaw is
  needed) and within 80 cm of it; palms 12 cm - 1 m apart (both hands collapsed onto one spot is a
  known tracking glitch). Any break restarts the 2 s: one frame off the pose, one hand or a joint
  lost, a gap over 250 ms between frames, a palm jumping more than 25 cm in a frame. It fires once
  per hold, like the grips.
- Why this gesture: it needs both hands, all eight fingers straight and both palms raised to the
  face, which normal hand use (pinching with palms down at chest height, thumb microgestures,
  pointing) never is; palms facing each other (clapping), stretching overhead, hands on the chest
  or beside the head don't count either. Two fists were the alternative, but clenched fists (or
  gripping something) are plausible by accident. Palms toward you also count, but palms out is the
  one to teach: palm toward you is where Quest shows its system pinch (pinching there opens the Meta
  menu; the escape never needs a pinch).
- Data: the 0.14 SDK has no Kotlin API for per-finger hand joints (its `HandTracker` /
  `xrLocateHandJointsEXT` use is native and internal; `Microgestures` and the hand `Controller`'s
  pose and pinch bits can't tell an open hand from a fist). It does expose body tracking:
  `Scene.setBodyTrackingJointSet(JointSet.DEFAULT)` + `Scene.updateBodyTrackingBuffersAtTime`
  (XR_FB_body_tracking, `@SpatialSDKExperimentalAPI`), whose joint list includes both hands' 26
  joints each (`JointType.LEFT_HAND_*`) and the head, each with valid / tracked flags (`ControllerPose`).
  While hand tracking is on, body tracking's hand joints follow it. `input/HandEscapeSystem.kt` is the
  glue: it does nothing while any controller is active (then the grips are the escape), needs both
  hands as active `Controller(type = HAND)`, and only uses joints whose location is valid **and**
  tracked. Needs the manifest feature `com.oculus.software.body_tracking` (optional) and the
  permission `com.oculus.permission.BODY_TRACKING`: the app asks once per run, the first time both
  hands are tracked with no screen or Storm open (the dialog pauses the app, and so the toy). Denied
  or unavailable: the hands escape is off and the buffers are retried every 5 s; if the SDK throws,
  it is off for the run.
- Feedback: none while holding, as for the grips (the 0.5 s ring of section 6 is not built for
  either); when it fires, the room and the tag "Stopped — you're in control" show. No new panel (the
  16-layer budget is untouched).
- Debug builds log every 500 ms, while both hands are tracked, why the pose does not count (or
  `POSE`), under `TroveInput` ("hands escape: left not open"), for tuning the thresholds on a device.

Volume has no hand gesture (headset volume keys). `ControlScheme.microgesture` maps each gesture to
its controller input, so the router feeds them through the same rules.

## 3. B always goes back

### The back stack

The stack is **derived from state every press, not recorded as history** (`ControlScheme.back`).
State changes from many places (on-panel buttons, the partner's Director, a screen closing, Black
Out, link loss); a history stack would hold stale entries. Layers, top first; B undoes the
top-most one that is present and visible:

| # | Layer (present when) | B does | On-panel equivalent |
|---|---|---|---|
| 0 | Black Out | nothing (blind-safe; escape is both grips) | – |
| 1 | Holding a panel | **Put back**: drop it where, as big and as tilted as it was at grab start | (let go and move it back) |
| 2 | Dragging a card | Cancel the drag | let go over the wall |
| 3 | Keyboard open | Close the keyboard, keep the text | keyboard's own close |
| 4 | A row is open on a *visible* panel: View / Stream / Markers / Timer on a screen's controls, Timer on the (shown) Storm panel (`Row.STORM`; closed before B exits Storm), the whole VacuGlide panel under them (folds into its chip), Sort / Account on the wall | Close the **newest** such row | Done / pick / Close / Collapse ▾ |
| 5 | Scene details on the visible library | Back to the grid | ‹ Back to library |
| 5b | A performer / studio / tag page on the visible library (the Scenes section filtered by it, under its header) | **Back to grid**: the Performers / Studios / Tags grid it was opened from, as left (search, sort, page) | ‹ Performers (etc.) in the page header |
| 6 | Library visible over video or Storm | Hide the library: back to the video / Storm | Back to video |
| 7a | Video with the library hidden and the controls hidden (Hide controls) | Show the controls | Hide controls' counterpart: trigger on a screen |
| 6b | Storm (any layout) with the library hidden | **Exit Storm**: Storm closes, back to the screens that were open, else the library (a hidden Storm panel or the Focus scene-info panel comes back with Menu) | Close (panel) |
| 7 | Video with the library hidden | Show the library in front of you | Library |
| 8 | Bare library, nothing open | nothing ("root") | – |

Rules:
- **B never closes a screen, never lifts Black Out, never resumes anything.** Closing a screen is
  always the explicit ✕ / Close (destroying a 6-screen layout on a stray B is too costly). Storm is
  the exception: nothing is lost by closing it, so B in Storm (no row, hold, drag or keyboard open,
  library hidden) exits it. With the library raised over Storm the first B hides the library (back
  to Storm), the second exits.
- Rows of a hidden panel don't count (the wall's Sort row while the library is hidden).
- Rows are newest-first across the app (LIFO), so with Markers open on screen 2 and Sort on the
  wall, B closes whichever was opened last.
- Layers 6 and 7 make B swap between the library and what plays: the library is "one back" from
  the video you opened from it, and the video is "one back" from a library you raised over it.
  Layer 6b is the user's "B should exit Storm".
- Layer 5b is derived like the others (`Snapshot.pageOpen` = the model has an open page). Scene
  details opened from a page close first (back to the page's scenes), then the page itself. A page
  ends by itself when another chip is added, its chip removed, or a saved-filter tab is picked.
- **Long B / Y (0.8 s) = home**: closes every row, the details and the page and shows the library grid in
  front of you, in one go. During hold, drag or keyboard it does the same as a short B.
- STOPPED doesn't change the stack.

### Put back (layer 1), and the B/Y size-mode conflict

Today B/Y while gripping toggles distance ↔ size (added in 0.2.2 because the stick-press bit was
unreliable). That clashes with "B always goes back". Resolution:

- **B/Y while holding = put back**, which *is* "back" for a panel: the previous state of what you
  hold. Implementation: on the press, `HoldSystem` restores the grab-start pose, size and tilt and
  ends the grab (`Grabbable` disabled for one frame, then re-enabled; the SDK grabs on the squeeze
  edge, so it won't re-grab while grip stays down). Device test needed (risk R5).
- **Size gets two ways that don't need B/Y:**
  1. **The other hand's stick ↑↓ resizes** while one hand holds: no mode, no hint to read,
     the most direct option (two hands).
  2. **Stick press (centred) on the gripping hand** toggles ↑↓ distance ↔ size, as today
     (one hand). `acceptToggle` made it reliable in 0.2.2; the toggle stays per grab and every grab
     starts on distance.
- Rejected: *trigger as a size modifier while gripping* (the SDK would also click whatever Compose
  button is under the ray of the held panel; R3); *both grips to scale* (both grips is the escape:
  a 2 s two-handed resize would stop everything); *A/X on the gripping hand* (A is play/pause
  everywhere).
- While holding, A/X keep play/pause and Menu does nothing (Menu could hide the wall in your hand).

### Other current B/Y/A/X uses

| Today | New |
|---|---|
| B/Y cancel a card drag | Unchanged (it is "back", layer 2). Grip still cancels too |
| B/Y toggle size while gripping | Put back; size = other stick or stick press |
| A/X click panels (SDK default) | Panels click on the trigger only (`PanelInputOptions(clickButtons = TriggerL or TriggerR)` on every panel registration); A/X = play/pause |

### Pitch and Face me (0.5.0, branch `layout`)

Screens used to have a tilt (roll, the holding stick's ←→) but no pitch the wearer could set: only
the posture presets tipped them. Now:

- **Face me** (wall Screens row, default on, remembered): every flat screen is kept square to the
  eyes, its forward axis from the head to its centre (yaw **and** pitch), so a screen placed high or
  low tips towards you. It is applied when a screen is placed, moved (grab, Director, posture, bring to
  me, layout relaxation), restored, and on release; while a screen is held it faces you every frame
  (`HoldTarget.faceHead` / `HoldSystem.applyFacing`). The roll stays on top, as the wearer set it.
  Rule: `layout/SphereLayout.kt` (`Facing`).
- **Pitch by hand (Face me off)**: the **other hand's stick ←→** while a screen is gripped, no mode
  to switch: → tips the top away (the screen faces up), ← the other way, 30°/s with the usual
  0.3 s ease-in and a 0.2 s delay so a flick does nothing, within ±85°. It pairs with what the other
  stick already does (↑↓ size) and leaves the gripping stick's modes (distance / size, ←→ tilt)
  alone. A shared `AxisGate` keeps one push to either ↑↓ or ←→ (the thumb arcs). With Face me on the
  push shows "Face me is on: pitch follows your head" instead.
- Posture presets only place; pitch is Face me's job. Their own pitch is only used with Face me off
  (lying back then still tips the screens down at you).

## 4. Stick when not holding

- **Video, sphere, a screen's controls, nothing under the ray**: ←→ seek the focused screen
  −10 / +10 s per step, 30 s steps after six repeats (~2 s held); ↑↓ volume ±10 %.
- **"Stick: Suction"** (VacuGlide panel toggle, pref `vacuglide_stick_suction`): in that same case ↑↓
  is no longer stepped but **held**: up = suction Tighter held, down = Looser held, from the start of
  the push until it ends (`SUCTION_TIGHTER` / `SUCTION_LOOSER`, then `SUCTION_RELEASE` per hand; the
  router follows `StickStepper.holding`, so the first-direction lock applies and a seek's arc never
  opens a valve). It ends too when the rule stops giving it: the ray moves onto a scrolling panel,
  a grip hold starts, Storm opens, Black Out, the last screen closes, or the toggle / the toy goes
  (`Snapshot.stickSuction` = toggle on + toy armed and online + no Black Out; `ControlScheme.stickSuction`
  adds a screen and not Storm). The press goes through `VacuGlideControl.stickPress` with the hand as
  owner: same 5 s cap, one valve at a time, every release path of the panel buttons; a button or the
  other stick never ends it and it never ends a button's press. Storm, no toy, or the toggle on
  Volume: ↑↓ is the volume as above. The legend says "Hold: suction tighter / looser".
- **Storm with no screen open** (or the ray on a tile): → next clip (the selected tile, else a
  shuffle of all, = "Shuffle now"); ← nothing (no clip history); ↑↓ volume.
- **Ray on a scrolling Compose panel** (wall grid, details, an open chip row): the SDK scrolls it
  (↑↓ vertical, ←→ horizontal chip rows) and the router does nothing for that hand. Paging stays
  on the footer's ‹ Prev / Next › (stick paging was considered and dropped: ←→ already scrolls the
  saved-filter, facet and sort chip rows, and a scene-object hit doesn't say which part of the wall
  the ray is on).
- **Holding a panel**: the gripping hand's stick is `HoldSystem`'s; the other stick ↑↓ resizes.
  While anything is held no panel scrolls (generalise `fixes-040`'s per-panel `LocalPanelHeld` to
  "any hold active", because the other stick's ray may be on another panel).
- First-direction lock (`StickStepper`), same reason as `AxisGate`: a sideways push on the Quest
  arcs into up+left / down+right after a few frames and must not change the volume while seeking.

## 5. A / X, Menu, long and double presses

- **A / X = play / pause** the focused screen; in Storm (no screen) Pause all / Play all. On the
  wall with nothing open: nothing.
- **Long A / X (0.8 s) = pause everything** (all screens, also ones behind a sphere, and Storm).
  Never "play all": a long press only removes stimulation. The partner console sees it as the
  usual paused state, not a STOP.
- **Menu (left) = Library**: show it in front of you / hide it, like the controls' Library button.
  Nothing when only the library is open, while holding, dragging or typing. A short Menu now fires
  on **release** (it has to wait to tell it from a long one).
- **Long Menu (0.8 s) = hide / show the controls** of the screens (video only; Storm has no
  control panels). Chosen because B is "back" (long B = home), A is play, and the stick press is
  help; Menu was the one button with no long meaning. Hands: the panel's own **Hide controls**
  button (no long press). With several screens only the focused screen's panel is up anyway
  (`ChromeRules`); hiding hides that one too, and the flag holds while focus moves. Brought back by
  trigger on a screen, long Menu, B (layer 7a), or the library coming up. Optional auto-hide
  (account panel, default off) after idle seconds.
- **Stick press (centred) = button help** (the legend, section 6); on the gripping hand while
  holding = distance ↔ size.
- **Double presses: none.** They would delay every single press (play/pause would wait ~300 ms to
  see if a second press comes), and are hard to do in gloves of a controller grip.
- Trigger long-press stays "new screen" on a card; elsewhere a long trigger is nothing.

## 6. On-screen hints

**Button legend** (`ControlScheme.legend(snapshot, hand)`):
- What: one line per input that does something right now ("A Play / Pause", "Hold B Library
  home", "Stick ←→ −10 s / +10 s", ...), always ending with "Both grips 2 s Stop + room". Built
  from the same rules the router runs, so it can't disagree with the buttons.
- Where: a small panel (about 0.16 × 0.10 m, Caption 14 sp, the design system's pill style) just
  above each controller, attached to the controller entity (`AvatarAttachment`-style child
  transform), facing the head. Fallback if attaching is not possible: the existing status area
  (control panel title row / the wall's grip strip), at most three lines.
- When: on a stick press (centred) for 5 s, a second press hides it; automatically for 4 s the
  first three times each context is entered after the controls change (counter per context in
  SharedPreferences, `legend_seen_<context>`); and while a long press fills (just that line).
- Off: account panel → **Button hints: Auto / Off** (Off still shows it on a stick press). Never
  shown in Black Out.
- Hold hint (`HoldHint.GRAB_HINT`) becomes "Stick ↑↓ distance · ←→ tilt · other stick: size · B: put back"; with Face me off a screen's hint reads "... other stick: ↑↓ size, ←→ pitch ..." (`HoldHint.GRAB_HINT_PITCH`).

**First-run coach marks** (once per install after the update, pref `controls_v2_seen`): a card on
the wall, four short lines, dismissed by "Got it" (trigger) or B:
1. **B** goes back (rows, details, library ↔ video). Hold B: straight to the library.
2. **A** plays / pauses. Hold A: pause everything.
3. **Stick** seeks ←→ and sets the volume ↑↓ (on a panel it scrolls). **Menu**: library.
4. **Both grips for 2 s**: stop everything and show the room (hands only: both open hands up in
   front of your face, 2 s). Press the stick for this help.

**Escape feedback**: from 0.5 s of both grips, a ring fills in front of the head ("Keep holding to
STOP"), so an accidental two-handed carry is noticed and released (R6).

## 7. Migration from 0.3.x

| Was | Now |
|---|---|
| B / Y while gripping: distance ↔ size | Put the panel back. Size: the other stick ↑↓, or press the gripping stick |
| B / Y elsewhere: nothing (cancel drag) | Back (drag cancel unchanged) |
| A / X on a panel: a click (undocumented) | Play / pause; panels click on the trigger only |
| Stick on a video: nothing | Seek / volume |
| Menu: nothing | Library |
| Both grips 2 s: lifts Black Out only | Universal escape (from `director`) |
| Hold hint text | New text, see section 6 |

Draft changelog entry (for whoever publishes; this branch does not edit CHANGELOG.md):

```markdown
- **New, consistent buttons** (the same on both controllers, so one hand is enough):
  - **B / Y goes back**: closes a row, the scene details, or switches between the library and
    your video. Hold it to go straight to the library. Nothing you watch is closed by B.
  - **A / X plays and pauses** the screen with sound (in Storm: all tiles). Hold it to pause
    everything.
  - **Thumbstick** while watching: left / right skips 10 s (hold for bigger steps), up / down
    changes the volume. Pointing at the library it still scrolls.
  - **Menu** (left controller) shows or hides the library.
  - **Press the thumbstick** to see what every button does right now.
- **Holding a panel**: **B / Y now puts it back** where you picked it up. To resize, push the
  *other* thumbstick up or down, or press the stick of the hand that holds it (as before).
- A and X no longer click buttons on panels; use the trigger.
- Holding **both grips for 2 seconds** always stops everything and shows your room.
```

README: replace the "Thumbstick while gripping" table's B/Y row and add a "Controls" section that
links here.

## 8. Implementation plan

### Architecture

```
InputRouter (SystemBase, execute, runs first)
  reads every local Controller once per frame: buttonState, changedButtons, type (CONTROLLER/HAND)
  + Microgestures component (hands)
  ├─ edges + PressTimer per face button / Menu, StickStepper per stick, centred-click gate
  ├─ builds Snapshot from AppState providers (model, players, storm, blackout, holds, drag, IME, rows, PointerTargets)
  ├─ ControlScheme.onButton / onStick / back → Action
  ├─ dispatch(Action) on the main thread → ActionSink (TroveActivity glue)
  ├─ publishes RouterFrame { perHand bits, stickDirs, putBackPressed, holdModeToggle, otherStickV } for HoldSystem
  └─ both-grips detection → BlackoutState.gripTick / WearerEscape (replaces BlackoutGripSystem's query)
HoldSystem (lateExecute)  takes its stick + toggle + put-back from RouterFrame (no own bit reading)
CardDragSystem            takes cancel (B/Y edge, grip) from RouterFrame
ScreenClick / StormTile   unchanged (pointer-based trigger on scene objects)
PointerTargets            per-hand "what the ray is on": InputListener hover on every panel scene
                          object (time-stamped, 100 ms freshness), DragRules.rayHitsRect fallback
ButtonLegend              panel per controller + coach card, fed by ControlScheme.legend
```

### Files

| File | Change |
|---|---|
| `input/ControlScheme.kt` | exists (this branch): contexts, actions, back stack, legend, `PressTimer`, `StickStepper` |
| `input/InputRouter.kt` | new: the one reader of `Controller` bits; edges, timers, dispatch; debug log `TroveInput` |
| `input/PointerTargets.kt` | new: hover registry per hand |
| `input/ActionSink.kt` | new interface; implemented in `TroveActivity` (calls players / storm / model / library) |
| `input/ButtonLegend.kt`, `input/CoachCard.kt` | new Compose panels |
| `TroveActivity.kt` | register router before HoldSystem; drop `BlackoutGripSystem`; `UIPanelSettings(input = PanelInputOptions(clickButtons = triggers))` on main / ghost panels; implement `ActionSink` |
| `player/PlayerSession.kt` | same `clickButtons` on control / bar panels; `Extra` hoisted from `ControlPanel`'s `remember` into session state (B must see and close it); volume applied |
| `player/ControlPanel.kt` | read/write `session.extra`; volume pill in the status area |
| `player/PlayerManager.kt` | `pauseEverything()` (screens + behind sphere + Storm), app volume, `seekFocused(ms)` |
| `storm/StormManager.kt`, `StormPanel.kt` | `nextClip()` (selected tile or shuffle), volume, `clickButtons` |
| `ui/AppModel.kt`, `ui/Wall.kt` | `sortOpen` hoisted into the model; a `RowStack` (LIFO of open rows) the panels push/pop |
| `update/UpdateModel.kt` | `aboutOpen` registers as an ACCOUNT row; "Button hints" setting |
| `ui/PanelIme.kt` | expose "keyboard open" + `hide()` for CLOSE_KEYBOARD |
| `hold/HoldSystem.kt` | input from `RouterFrame`; grab-start snapshot + `putBack()`; other-stick size; new hint |
| `hold/HoldRules.kt` | `acceptToggle` loses the face-button arg (or B/Y passes false) |
| `hold/BlackoutGripSystem.kt` | deleted (router owns the grips) |
| `drag/CardDragSystem.kt` | cancel from `RouterFrame` |
| `hold/HoldSystem.kt` `LocalPanelHeld` | becomes `LocalStickCaptured` = any hold active |

### Order of work (each step shippable)

1. Rebase on `main` after `gaze-posture`, `director`, `fixes-040` merge.
2. Router skeleton: reads bits, logs edges/steps (debug), dispatches nothing. Move the both-grips
   escape into it (one reader) and delete `BlackoutGripSystem`. Device check: escape still fires.
3. `clickButtons = triggers` on every panel; A/X → play/pause, long → pause everything.
4. Hoist rows (`ControlPanel.Extra`, `sortOpen`, `aboutOpen`) + `RowStack`; B/Y back stack; long B
   home; Menu = library.
5. `PointerTargets`; stick seek / volume / Storm next clip; app volume; `LocalStickCaptured`.
6. Hold changes: B put back, other-stick size, new hint; `HoldSystem` and `CardDragSystem` read the
   `RouterFrame` instead of `Controller`.
7. Legend panel, coach card, "Button hints" setting, escape ring.
8. Hands: microgesture spike (enable, log bits), then map; the hands escape decision.
9. README "Controls", changelog entry (at publish time).

### Risks

- **R1 SDK panel input also reads buttons.** `PanelInputListener` clicks with A/X by default and
  turns the stick into scroll events for the panel under the ray. Fix for A/X: `clickButtons`
  on every registration (Compose *and* video panels); check that no panel is created through
  another path that keeps the default. Scroll can't be switched off per frame, only per container
  (`userScrollEnabled`), hence the "the ray decides" rule; `PointerTargets` must agree with what
  the SDK hits or the stick both scrolls and seeks.
- **R2 Menu / keyboard delivery.** Unverified that the left Menu button reaches the app as
  `ButtonMenu` (and that left palm-pinch does too with hands), and whether the app gets any
  controller input while the system keyboard has focus (README: system focus is vrshell's then).
  If not, CLOSE_KEYBOARD is dead code and the keyboard's own close button remains.
- **R3 Trigger while holding.** The SDK may still deliver a trigger click to the Compose button
  under the held panel's ray; `ScreenClick` only guards videos. Keep the trigger meaningless while
  holding and test whether clicks leak.
- **R4 Hands.** Pinch-as-trigger, microgestures in 0.14 (component present, may need a manifest
  feature or `VRFeature` flag), palm-pinch = Menu: all untested. The hands escape (section 2b) is
  built on body tracking's hand joints and untested: whether `updateBodyTrackingBuffersAtTime`
  returns data at all with hands only, whether the hand joints carry the tracked flag, whether the
  hand entities report `ControllerType.HAND`, and the thresholds all need a device.
- **R5 Put back.** Ending a grab by disabling `Grabbable` for a frame is not an SDK API; if the
  SDK re-grabs or snaps, fall back to "put back on release" (B marks, release restores).
- **R6 Escape vs two-handed carrying.** Holding two panels at once for 2 s fires the escape. That is
  the intended trade-off (stop wins); the 0.5 s ring makes it noticeable.
- **R7 Edge semantics.** `changedButtons` is per frame; with one reader that's fine, but any
  remaining direct reader (listeners) must not also act on A/B/X/Y/Menu.
- **R8 Stick bits.** Direction bits only, the click bit fires on a push, sideways arcs: handled by
  `StickStepper` and the centred-click gate, both unit tested; device-check repeat timing.

### Tests

- `ControlSchemeTest` (this branch, 23 tests): back stack order and visibility, Y = B / X = A,
  long-press rules, Black Out inert, STOPPED changes nothing, A only pauses on long, stick vs SDK
  scroll by target, Storm, hold routing, seek growth, `StickStepper` (repeat, arc lock, down wins),
  `PressTimer`, contexts, legend content, microgestures.
- Later: `RowStackTest` (push/pop/remove when closed elsewhere), `InputRouterTest` with fake bit
  frames (edge detection across frames, both grips + one hold, router ignores the trigger),
  `HoldRulesTest` for put-back pose and other-stick size.
- Device checklist: escape in every context incl. Black Out and while holding; A/X no longer click
  panels; stick on a video seeks while on the wall it only scrolls; B through every layer; Menu;
  legend placement; hands (pinch, palm, microgestures, the hands escape: fires in every state,
  never from pinching / microgestures / pointing, not with one hand out of view, permission prompt
  only with nothing playing).
