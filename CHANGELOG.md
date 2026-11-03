# Changelog

User-facing notes for each published build. `scripts/publish-apk.sh` copies the section for the
version it publishes into the update card in the headset, and refuses to publish without one.
Newest first; one `## [x.y.z]` heading per version (a date after it is fine).

## [0.5.5]

- **Vertical videos are no longer huge**: a portrait video opens at most 1.7 m tall (it was sized by
  width, so a 9:16 video came out over 4 m tall). You can still resize it by hand.

## [0.5.4]

- **Move Storm Focus by gripping the centre video**: grip the big centre video and the whole layout (ring,
  scene info, scrub bar) follows your hand. Let go and it settles there. A facet re-Storm keeps the place.
- **Play / Pause on the scrub bar**: a button at the bar's left pauses or plays the centre alone.
- **Tidier Storm panel**: grouped rows with dividers, Hide / Close up in the header, and the panel now
  sits below the videos instead of over the bottom row.
- **Toy without a video**: **Start toy / Resume toy** is now in the library's top bar (not only under
  Account), and the Storm panel has a **Toy** row with Start / Resume and, once the VacuGlide is online,
  hold-to-use Tighter / Looser.
- **B / Y closes the video**: on a video (library hidden) B closes the focused screen and the library
  comes back once none is left; in Storm it exits Storm as before. With the library open over a video,
  B still just hides the library, so browsing never loses your video. **Hold B** on a video now shows /
  hides the library without closing anything (hold B in Storm still goes home).
- **Smoother re-Storm**: tapping a tag or performer in Focus fades the old wall out and the new one in
  (it comes in when the centre's first frame is ready) instead of cutting.

## [0.5.3]

- **Fixed: VacuGlide Tighter and Looser were the wrong way round.** Tighter now tightens and Looser
  loosens on the real device. **Keep suction** (which tops up with short Tighter pulses) was loosening
  too and now tightens. This is fixed on the Trove server, so it needs the server update as well.
- **Suction on the thumbstick**: a new **Stick: Volume / Suction** switch on the VacuGlide panel. With
  Suction, push the stick up to hold Tighter and down to hold Looser; let go and the valve closes
  (at most 5 s per push, as with the buttons). It only works while the VacuGlide panel would be there
  (toy armed and online, watching a screen, not Storm, not Black Out); otherwise the stick sets the
  volume as before. Left / right still seek. Your choice is remembered, and the button help shows it.
- **Volume slider**: a **Vol** slider next to the seek bar on every screen's controls, and in the Storm
  panel next to the clip length. It shows the volume and follows the stick.

## [0.5.2]

- **Session timer**: set 15 to 90 minutes from **Timer** on a screen's controls or the Storm panel
  (your partner can set it from their console too), add 10 minutes or cancel it. The time left shows
  next to the sound tag, correct even if the headset's clock is off. At 5 and 1 minute left a short
  notice appears, also when the controls and library are hidden (never during Black Out). At zero
  the toy stops and every video pauses; nothing starts again by itself.
- **Escape with hands only** (not tested on a headset yet): with hand tracking, hold both open hands
  up in front of your face like a "stop" sign for 2 seconds. It does what holding both grips does.
  It needs the body tracking permission, which Trove asks for once the first time it sees both hands.
- **Faster**: this build is release-optimised (no longer a debug build), Storm tiles all start at
  once and each preview opens at its own spot, and the library fetches the next page while you look
  at the current one.
- The last traces of the old name are gone from the app's insides; your login and settings carry
  over by themselves.
- The README's list of what is still untested on a headset is up to date.

## [0.5.1]

- **Pick the funscript**: when a scene has several scripts (Default, FULL, HALF…), choose which one
  drives the VacuGlide from the VacuGlide panel or the scene details. Switching while the toy runs
  loads the new script and carries on from where the video is ("Loading script…" until the toy has
  it). Your choice is remembered per scene on the server; it uses Stash Interactive Tools' list.

## [0.5.0]

- **Browse like Stash**: the library has **Scenes · Performers · Studios · Tags**. Each is a grid
  with pictures, scene counts, search, sort and a ★ Favorites filter. Open a performer, studio or tag
  to see their scenes, with **Play random** and **Storm** (just their scenes). B goes back to the grid,
  and Trove reopens on the section you left.
- **VacuGlide panel** under the controls of the screen with the sound, when the toy is armed and
  online: hold **Tighter** / **Looser** for suction, and set offset, intensity, speed limit and
  keep-suction (saved on the server). Letting go always closes the valve, also when you look away,
  the link drops, you take the headset off, Black Out or STOP ALL. It starts as a small chip; B folds
  it, Hide removes it, and a VacuGlide button on the controls brings it back.
- Sort scenes by **Performer** or **Studio**.

## [0.4.9]

- Fixed: the whole view going black when you pointed at the Storm Focus centre. The headset can
  only draw 16 video/panel layers at once and the scrub bar was the 17th. The small panels
  (controls, scrub bar, scene info, button legend, focus bars) no longer use those layers, and Storm
  keeps its tile count under the limit together with your screens and the library ("display layer
  limit" on the Storm panel when that is why you see fewer tiles).

## [0.4.8]

- **Scene info beside the Storm Focus centre**: title, date, length, studio, performers, tags and
  markers (a marker jumps the centre there). Tap a performer, studio or tag to **re-Storm with just
  their scenes**, or "Library" next to it to open the library filtered by it. Collapse it to a slim
  tab or hide it; Menu or B bring it back.
- A tile you swap into the centre now switches to its full video straight away instead of showing
  the jumping preview first (a few small tiles close if the headset needs the decoding room).
- The Focus scrub bar is now really two thirds of the centre's width, also after swaps.

## [0.4.7]

- **B exits Storm** and takes you back to your screens (or the library if none are open). With the
  library open over Storm, the first B hides it and the second exits.
- **Storm Focus**: the small tiles are now a row above and a row below the centre (no side tiles).
  The scrub bar sits inside the bottom of the centre video, about two thirds of its width.
- The centre video (and the tile you selected in the other layouts) now just plays on: it never cuts
  away, jumps or re-seeks, also with the Random seeks source.

## [0.4.6]

- **Storm Focus**: the small tiles now frame the centre (a row above, a row below and one or two at
  each side) and the whole layout fits in your view (about ±34° wide). Before, the ring spread out
  past the edges of the view.

## [0.4.5]

- **Storm Focus**: the centre is smaller (1.4 m) and the tiles around it about 40 % larger.
  Point at the centre to get a **scrub bar** with markers: pull the trigger on it to jump, and if
  the centre is still a preview it switches to the full video at that moment. It hides a moment
  after you look away. Stick left/right on the centre skips ±10 s.
- Fixed: Solo from a Storm tile that plays the full video could start at the wrong time.

## [0.4.4]

- **Storm**: tapping a tile only selects it (or swaps it into the centre in Focus); a double tap no
  longer opens the video. Solo is the panel's button. When a preview swaps into the centre, the
  real video now starts at exactly the moment the preview was showing (it uses your Stash preview
  settings). Storm can no longer end up running twice: changing Source, Layout or Re-storm always
  closes what it replaces first.
- **Toy on your own**: the headset has **Start toy** / **Resume toy** (on the screen's controls and
  in the account panel), and the sync label says what to press. A partner's STOP ALL needs a 2 s
  hold to resume from the headset; with "partner required" switched on in the console, only the
  console resumes it. The both-grips stop, headset-off stop and Black Out work as before.

## [0.4.3]

- **Face me** (on by default, in the library's Screens row): screens stay square to your eyes and
  tip toward you when placed high or low, even while you carry them. Your own tilt still applies.
  With Face me off, tip a screen you are holding with the **other** stick's left/right.
- **Screens no longer pile on top of each other**: when you move or resize one, the others ease out
  of its way, and a screen you hold pushes them aside as you go. New screens open in the nearest
  free spot to where you look (or where you drop a card).
- **Tidy** (Screens row) packs your screens into a neat grid around your view, following your
  posture.
- A STOP ALL that arrives while you are already stopped by your own escape no longer leaves the room
  view stuck on.

## [0.4.2]

- **Storm "Focus" layout**: one big centre video with the sound, surrounded by 6, 8 or 12 shuffling
  previews. Pull the trigger on a small one and they glide into each other's places. Clips now last
  20 s by default, with a 5-60 s slider on the Storm panel, and the panel has a **Hide** button (Menu
  or B brings it back). Storm now plays **3D scenes** (side-by-side and over-under) as tiles too, and
  changes the tile count without restarting every tile.
- **Multi-view**: only the screen with the sound shows its control panel, and it follows the focus.
  **Hide controls** on the panel, or hold **Menu**, hides them; the trigger on a screen, B or the
  library brings them back. Optional auto-hide in the account panel (15/30/60 s).
- **Taking the headset off pauses everything** (screens and Storm) and stops the toy; putting it back
  on carries on. A STOP ALL sent while the headset's Wi-Fi was dropping now still pauses and shows
  the room when it reconnects.
- The sync label says what is true ("Link ok · toy unarmed"), and after a stop the headset says what
  to do next ("Stopped by your partner — wait for them to resume").
- When your login expires, playback stops before the login screen appears.
- A short Menu press now registers when you let go of the button (it waits to tell it from a hold).
- Lower memory use (thumbnail cache capped, less work while carrying panels).
- With the updated server: **Continue** and **Watchlist** tabs in the library.

## [0.4.1]

- **Same buttons everywhere, on both controllers** (X works like A, Y like B):
  - **B / Y goes back**: closes a row or the scene details, or switches between the library and
    your video or Storm. Hold it to go straight to the library.
  - **A / X plays and pauses** the screen with the sound. Hold it to pause everything.
  - **Menu** (left controller) shows or hides the library.
- **Thumbstick while watching**: left/right skips 10 s (hold for bigger steps), up/down sets the
  volume. In Storm, right is the next clip. Pointing at the library or a chip row it still scrolls.
  **Press the stick** to see what every button does right now.
- **Holding a panel**: B / Y puts it back where you picked it up. To resize, push the *other*
  thumbstick up or down (or press the holding stick, as before).
- A and X no longer click buttons on panels: use the trigger.
- **Play tracking**: what you watch on the screen with the sound now counts in Stash (play count,
  last played), and "Resume at" uses the position saved on the server, so it matches the Stash UI
  and the web player. Other screens and Storm clips are never counted.
- First launch shows a short card with the new controls (Account → Button hints: Off turns hints
  off).

## [0.4.0]

**The app is now called Trove, a Stash VR Companion.** Same app, new name (the old name belongs to another
product). Your login, screens and settings stay as they are.

- **Look to focus**: look at a screen for a moment and it gets the sound and the full-quality
  stream, no click needed. Turn it on or off in the library's new Screens row.
- **Posture layouts**: one tap lays your screens out for **Sitting**, **Lying back** (overhead,
  tipped down at your face) or lying on your **Left side** / **Right side**. Grabbing a screen keeps
  its tilt.
- **Bring screens to me**: turns all your screens, the library and the Storm wall round to where you
  are facing now.
- **Escape, any time**: hold **both grip buttons for 2 seconds** to stop everything: screens and
  Storm pause, Black Out lifts, you see the room, and the partner console shows "STOPPED by the
  wearer". Only the console resumes. Works even when the link is down.
- **Director** (needs the updated server): the partner console (or your own phone's "Send to
  headset") can open, swap, close and focus screens, start Random or Storm.
- Storm keeps the tile you picked: it no longer cuts away from the one with the sound.
- Holding the library with grip no longer scrolls it with the stick.

## [0.3.0]

- **Storm**: a new button on the library header fills your view with a wall of 4 to 25 clips that
  keep cutting to random scenes from what the library shows. Curved wall in front of you or all
  around you. Point and pull the trigger on a tile for its sound, again (or Solo) to open it as a
  normal screen. Tiles play Stash's small previews; 3D/VR scenes are left out. The tile count
  follows the headset's video decoder limit, and the panel says when it shows fewer.
- **Random**: a new button on the library header plays a random scene from what the library shows
  (everything when nothing is filtered). **Next random** on a screen's controls swaps that screen to
  another random scene.
- **Cleaner, bigger controls everywhere**: the library header is reorganised (search and account on
  top, Sort as one chip, Open in / Room / Random / Storm grouped, paging at the bottom; Log out is
  under your name). Text and buttons are larger, the video controls have Play/Pause and ±10 s on
  one row, and all status labels sit in one place.
- Scene cards show two-line titles and a thin bar where you stopped watching. Clear messages with
  a button when the library is loading, empty or fails to load. Tidier login, scene details and
  update card.
- The partner console shows "storm: on" while Storm runs; the toy does not follow random clips.

## [0.2.9]

- Fixed: Trove could crash (run out of memory) after a while with high-resolution videos,
  especially with more than one screen. Each screen now buffers less video in memory, and videos
  load over plain HTTP/1.1 so a paused screen no longer keeps filling memory.

## [0.2.8]

- **Click a video to play or pause it.** With several screens, the first click on another screen
  only moves the sound and focus there. Works on 180°/360° videos too.
- **Drag a scene out of the library into the room**: hold the trigger on a card, pull it off the
  library and let go where you want it; a new screen opens there. Let go over the library, or press
  grip, B or Y, to cancel. Tapping and long-pressing cards work as before.

## [0.2.7]

- New **Room** chip in the library's header (shown when no video is open): turn it on to see your
  room behind the library instead of black. Off by default and remembered; it is darkened like
  your View → Behind choice.

## [0.2.6]

- Fixed: a held panel no longer swoops in from the side when you let the stick go after tilting.
  The cause was Meta's built-in turning: in Trove, the stick no longer turns your view or
  teleports you; it only moves, resizes and tilts the panel you hold. Turn your head or body, or
  recenter with the Meta button, to face another way.

## [0.2.5]

- Fixed: tilting a held panel with the stick could push it away or pull it closer instead (the
  "swoop"). A sideways push on the Quest stick drifts up or down as your thumb moves; now the first
  direction you push decides whether the stick tilts or moves the panel, until you let the stick go
  back to the centre. Tilting starts a little sooner (0.2 s).
- **Black Out** (needs the updated server): your partner can blank the headset from `/companion`.
  Every screen and panel disappears and the sound is muted; the videos keep playing underneath and
  the toy stops. It lifts when they tap again, when either side loses the link, or on STOP ALL. To
  lift it yourself, hold both grip buttons for 2 seconds.

## [0.2.4]

- **Search suggests performers, studios and tags** as you type. Tap one to filter the wall; remove
  it again with its ✕.
- **Scene details**: the new **i** button on each card shows tags, performers, studio, date, length
  and markers. Tap a marker to play from there.
- **Markers** show as ticks on the seek bar, and the **Markers** button lists them.
- **Resume**: scenes remember where you stopped on this headset and offer "Resume at mm:ss".
- **Fisheye 190°/200°/220°** videos now play on a real fisheye dome, so the edges are no longer
  stretched.
- **See your room behind flat and 3D screens**: View → Behind: Off, Room, 50 % darker or 80 %
  darker. Remembered; off for 180°/360°/fisheye.
- **Partner console**: the app now links to the server's sync. A partner on `/companion` sees your
  screens, battery and whether you're in the app, and can pause, play and seek. Their **STOP ALL**
  pauses everything, shows the room and a red "STOPPED by partner" tag until they resume. A
  "Sync" tag on the wall shows the link. Toys stay off: sync is not armed on the server.
- New app icon.

## [0.2.3]

- Pushing, pulling or resizing a panel no longer tilts it by accident when the stick drifts a little
  sideways. Tilting now needs the stick held straight left or right for a moment, and turns a bit
  slower so it is easier to stop where you want. Centre the stick to switch between moving and
  tilting.
- Fixed: a tilted panel could sometimes swoop in from the side while you held it.

## [0.2.2]

- Fixed: moving the thumbstick while holding a panel kept switching between distance and size (the
  hint flipped every time), so stick down never pulled the panel closer. Pushing the stick no longer
  switches modes; stick down pulls the panel closer (or makes it smaller in size mode).
- To switch between distance and size, press the stick straight in without moving it, or press **B**
  (right hand) / **Y** (left hand) while gripping.
- Fixed: the first update could leave Trove frozen after the "allow installing apps" window. Stash
  XR no longer opens that window itself; the system's install dialog asks for it when needed. If Stash
  XR ever looks stuck after a system window, close it and open it again.

## [0.2.1]

- **Thumbstick while you hold a panel with grip** (the wall, a video screen, or the controls of a
  180°/360° video). Only the stick on the hand that is gripping does this:
  - **Up / down** pushes the panel further away or pulls it closer (0.6 m to 10 m), so you are no
    longer limited by the reach of your arm.
  - **Press the stick in** to switch up/down to **size**: then up makes it bigger and down smaller.
    Press again to go back to distance. Every new grab starts on distance.
  - **Left / right** tilts it counter-clockwise / clockwise, handy when you lie on your side. Let go
    close to level and it snaps straight.
  - A short hint on the panel shows what the stick does.
- Screens remember their distance, size and tilt in "Restore last screens". The wall remembers its
  size and distance.

## [0.2.0]

- **Updates from your own server**: Trove now checks the Trove server for a new version when
  it starts and when the wall opens (at most every 6 hours) and shows an "Update available" card on
  the wall. Install downloads it, checks it and hands it to the Quest's installer; no cable needed.
- The version number next to your name on the wall opens an About row with **Check for updates**.
- The first update asks you once to allow Trove to install apps.

## [0.1.0]

- First version: log in with your phone (code or QR) or name and password, the wall with saved
  filters, search and sort, and a player for flat, 3D, 180° and 360° videos.
