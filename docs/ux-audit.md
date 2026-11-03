# Trove (Quest): UX/UI audit (base v0.2.8)

Scope: wall (login, header, grid, details), player control panel, update/about card, status tags,
hold hint, ghost panel, theme. Judged for a headset: rays at 1-3 m, reclining, dim room, partner-assisted
sessions. Panels are 700 dp per metre: the wall is 1.8 x 1.2 m = 1260 x 840 dp, a screen's controls
1.2 x 0.32 m = 840 x 224 dp. At 1.6 m, 14 sp is about 0.7 degrees of visual angle, so anything under
14 sp is unreadable and anything under 48 dp is a poor ray target.

File:line references are to the 0.2.8 sources before this pass.

## 1. Layout and hierarchy

| # | Problem | Where | Fix |
|---|---|---|---|
| L1 | The wall header is five stacked rows plus a conditional update area; row 1 holds ten unrelated things (brand, search, Search, Back to video, Sync, user name, version, Log out) and row 5 mixes the count, "Open in", Room, Restore and the pager. Nothing says which is primary. | MainPanel.kt:237-309 | Three tiers. Top bar: brand, search, Search, Back to video, sync pill, account button. Browse bar: saved-filter tabs, then sort. Session bar (right of sort): how cards open (Open in), Room, Restore, and a slot for other modes. Pager and count move to a footer under the grid. |
| L2 | Log out sits beside Search at full prominence; one stray ray click logs the wearer out. | MainPanel.kt:259 | Log out moves into the account panel (opened by the name/version button), next to Check for updates. |
| L3 | The version button and the user name are two separate header items; the About row is a third surface. | MainPanel.kt:257-258, UpdateUi.kt:31-69 | One account button ("name . v0.2.8 *") opening one panel: version/build/server, update status, Check for updates, Log out, Close. |
| L4 | "Open in" (This screen / New screen), Room and Restore are one conditional blob that swaps shape depending on whether screens are open; no label says they are about the session. | MainPanel.kt:458-493 | `SessionControls`: a labelled group, always the same slot, content decided by the pure `WallLogic.session(...)`. A separate empty `WallModeSlot` (ui/WallModeSlot.kt) is the place for the "Storm" button and any future mode. |
| L5 | Control panel title row carries title, Resume offer, Markers button, hold hint, Sync tag, Sound/Muted, quality tag and the time, all at 11-12 sp. | ControlPanel.kt:97-123 | Title row = title + one status area. Time moves next to the seek bar (elapsed left, total right). Resume offer moves to the primary row. Markers becomes a button in the secondary row. |
| L6 | One button row mixes primary (play, +-10 s) and secondary (View, Stream, Library, Close) with equal weight. | ControlPanel.kt:139-153 | Two rows: primary (-10 s, Play/Pause, +10 s, resume offer) and secondary (View, Stream, Markers, then Library, Close at the right). Panel height 0.32 -> 0.36 m (252 dp). |
| L7 | The View extra needs two chip rows (projection + Behind) but replaces a single 224 dp row; it overflows the panel. | ControlPanel.kt:154-182 | The extras area now has the two-row height of the primary + secondary rows, so View and Behind fit; each row has a text label (View / Behind / Stream / Markers). |
| L8 | The grid starts around 330 dp down an 840 dp panel and the 12-card page shows about two rows. | MainPanel.kt:237-334 | Header budget: top bar 56 + tabs 48 + tools 48 + footer 48 (+ transient rows). Tabs row is hidden when there are no saved filters. Page change scrolls to the top. |

## 2. Consistency

| # | Problem | Where | Fix |
|---|---|---|---|
| C1 | Four tag styles: SyncTag (outline/filled), ControlPanel `Tag`, `TagButton` (clickable outline), `ResumeOffer` (outline + inline buttons). Different paddings (6x1, 8x2, 8x3), radii (6) and colours. | SyncTag.kt, ControlPanel.kt:215-227, SeekExtras.kt:305-361 | One `StatusPill` for read-only status; anything clickable is a Chip or Button of at least 48 dp. Rule below. |
| C2 | Three chip families (FilterChip, AssistChip, InputChip) at 32 dp visual height, each with a different look when selected. | Widgets.kt:67-71, FacetChips.kt:251-301, SceneDetails.kt:471-507 | One `Chip` (selected = accent fill, unselected = raised surface with outline), 48 dp, optional kind label, optional trailing remove mark. |
| C3 | Spacing is ad hoc (4, 6, 8, 10, 12, 14, 16, 20, 24, 48 dp) and radii are 4/6/10/12/16/20/28. | everywhere | `Space` and `Radius` scales (below). |
| C4 | Colours are hard-coded in six files (amber script badge, amber markers, pink/blue/green facet kinds, a green and a red in SyncTag, white-with-alpha tags). Amber means "script", "marker", "warning" and "resume" at once. | Theme.kt, SyncTag.kt:142-143, SeekExtras.kt:287, MainPanel.kt:391 | One accent (blue). Amber is only for warnings. Script badge is neutral, marker ticks are white, resume is an accent chip. Facet kind colours stay (text only) as the one categorical palette. |
| C5 | Button styles are chosen per call site (Button, FilledTonal, Outlined, TextButton) with no rule. | MainPanel.kt, ControlPanel.kt, UpdateUi.kt | Four wrappers: Primary (one per context), Secondary, Outline, Quiet. All 48 dp. |
| C6 | Some files use fully qualified names inline (Widgets.kt GrabHint) and dead-end helpers. | Widgets.kt:79-96 | Rewritten in the new components file. |

## 3. Readability and targets

| # | Problem | Where | Fix |
|---|---|---|---|
| R1 | Material default type: label 11-14 sp, body 14 sp, in a headset that is under 0.7 degrees. Tags are `labelSmall` (11 sp). | Theme.kt (default typography), SyncTag.kt:134, ControlPanel.kt:220, Widgets.kt:92 | Custom `Typography`, nothing under 14 sp; body 16-18, labels 15-17, titles 20-26. |
| R2 | Targets: chips 32 dp, "i" button 36 dp, marker ticks 18 dp, tag buttons ~24 dp, Resume offer ~24 dp, text buttons 40 dp. | MainPanel.kt:399-403, SeekExtras.kt:363, SeekExtras.kt:325-341 | 48 dp minimum for everything clickable (info button 44 visual + 48 hit via padding). Marker ticks stay thin visually with a 28 x 28 dp hit box (they can sit close together, so wider would overlap); the same jumps are on the 48 dp chips behind the Markers button. |
| R3 | Low contrast: tags are white at 35-55 % alpha on a near-black translucent panel; muted text #B4B8C0; outlines nearly invisible. | ControlPanel.kt:117-118, Widgets.kt:93 | Muted text #C4CAD3 (>10:1 on the surface), outline #6B7483 (3:1), status text never below 70 % alpha. |
| R4 | The grab hint pill (11 sp, 55 % white) is the only discoverable cue that panels can be moved. | Widgets.kt, MainPanel.kt:96 | 14 sp, 75 % white, taller handle, reserved 44 dp strip at the panel bottom instead of overlapping the padding. |
| R5 | Card text: title one line at 14 sp with an invisible tail; badges 11 sp. | MainPanel.kt:408-416, 441-450 | Title 16 sp semibold, two lines (reserved), meta 14 sp, badges 14 sp on a darker scrim. |
| R6 | Ghost panel caption is 11 sp and clipped on a 0.25 m panel. | GhostPanel.kt:224-230, TroveActivity.kt:483-484 | 14 sp caption, panel 0.36 x 0.30 m (was 0.34 x 0.25). |

## 4. State clarity

| # | Problem | Where | Fix |
|---|---|---|---|
| S1 | Empty grid, a failed load and "no results" are indistinguishable: the grid is simply blank, an error is a red line above it. | MainPanel.kt:311, 328-350 | `WallLogic.content(...)` decides LOADING / ERROR / EMPTY / EMPTY_FILTERED / CONTENT. Each gets a centred state with a message and an action (Try again, Clear filters). While reloading the old page stays visible at reduced opacity. |
| S2 | The Sync tag says "Sync" in the wall and nothing at all in the controls; "STOPPED by partner" has different sizes in the two places. | SyncTag.kt, ControlPanel.kt:114 | `StatusTags` builds the list for both places from one precedence rule; the red pill is identical. |
| S3 | Control-panel tags have no precedence: with a hold hint, Sync, Sound and 720p at once they overflow and squeeze the title. | ControlPanel.kt:111-118 | `StatusTags.controls`: alert, error, hint, sync, sound/muted, quality; the hint replaces everything except an alert while the panel is held; at most 3 pills. |
| S4 | "Open in: This screen / New screen (6/6: replaces)" is a long, hard to parse label. | MainPanel.kt:472 | "New screen 2/6"; at 6/6 "New 6/6 (replaces)". |
| S5 | A scene left part way is invisible on the wall. | MainPanel.kt:375-407 | Thin accent progress bar on the thumbnail from the local resume point. |
| S6 | Loading a card's details shows a lone spinner; a failure shows red text with no way back except Back. | SceneDetails.kt:454-458 | Spinner with a label; error with "Try again". |
| S7 | Update card: three states look the same (grey card); error text is small; the "install" text is a paragraph. | UpdateUi.kt:90-160 | Accent edge for available, red for failed, green for installed; 16 sp text; thicker progress bar; shortened system-dialog text. |

## 5. Flows

| # | Problem | Where | Fix |
|---|---|---|---|
| F1 | Login: "New code (uses the Server field)" explains an implementation detail; the Server field hides in the password column although it governs both halves. | MainPanel.kt:102-111, 159, 175-182 | Server is a shared row above both halves. "New code" is a plain button. The pairing steps become a numbered list. Errors use the error colour at 16 sp. |
| F2 | "Search" requires a click; suggestions and active chips appear in two different rows. | MainPanel.kt:262-273 | Kept (keyboard Search/Go works), but the search field, suggestions and active chips share a "refine" block directly under the top bar. |
| F3 | The details view has Back last, below three buttons. | SceneDetails.kt:437-446 | Back is first in the left column; Play/Resume, From start, New screen stacked full width. |
| F4 | Sort has twelve chips in a scrolling row that pushes the screen controls away. | MainPanel.kt:286-297 | Sort is one chip ("Sort: Release date") that expands the full chip row when tapped and collapses after a pick. |

## Design system

All values live in `ui/theme/Tokens.kt`; components in `ui/theme/Components.kt`; Material is themed from
`ui/theme/Theme.kt` so stock widgets (text fields, slider, buttons) follow it.

**Spacing** (dp): xs 4, sm 8, md 12, lg 16, xl 24. Panel padding 24 (wall) / 16 (controls). Gaps between
rows: sm; between groups: md-lg.

**Type** (sp; nothing below 14): Title 26 semibold (brand, details title), Heading 20 semibold (card
titles, section heads), Body 18 (primary text, fields), BodySmall 16 (card titles, secondary text),
Label 17 medium (buttons, chips), LabelSmall 15, Caption 14 (meta, pills, hints).

**Colour** (dark): panel #F2101216, surface #1A1D23, raised #272C35, outline #6B7483, text #F4F6F8, muted
#C4CAD3, accent #8AB4FF (on-accent #08142B), ok #7FD8A0, warn #FFC27A, alert #E5484D (white text), error
text #FF9A92. One accent: it means "interactive / selected / focused / sound". Amber is warnings only.
Facet kinds (performer pink, studio blue, tag green) are text-only category colours.

**Radius**: chip/button 12, card 14, pill 8, panel 28 (wall) / 20 (controls), badge 6.

**Sizes**: touch target 48; large target 56 (primary play); chip visual = 48 (no smaller hit box);
pill height 28; thumbnail aspect 16:9; icon button 48 (44 visual).

**Icons**: only Unicode glyphs already used (arrows, check marks, X); `material-icons-extended` is not a
dependency and a 90 MB APK should not grow for six icons. Every glyph button also has a word.

**Status tag rules**
1. A pill is read-only. Anything you can tap is a Chip or Button of at least 48 dp.
2. Pill levels: neutral (information), accent (focus/sound/hint), ok, warn, alert (filled red, bold).
3. Wall: at most one sync pill, top bar, right side. Controls: one status area on the title row, at most
   three pills, ordered by `StatusTags` precedence (alert > error > hint > sync > sound/muted > quality).
4. A transient hint (stick help) replaces everything except an alert while it shows.
5. An alert is never hidden by anything else.

**Buttons**: Primary (accent fill, one per context: Play, Install, Log in, Back to video), Secondary
(raised fill), Outline (neutral actions: Search, Prev/Next), Quiet (text: Later, Close). Destructive Close
on the controls is Outline with a warm label, not red (red is reserved for STOPPED).
