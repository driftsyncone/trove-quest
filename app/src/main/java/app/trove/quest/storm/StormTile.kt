@file:OptIn(UnstableApi::class)

package app.trove.quest.storm

import android.os.Handler
import app.trove.quest.player.MediaPlayers
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.trove.quest.PanelIds
import app.trove.quest.api.Card
import app.trove.quest.api.TroveApi
import app.trove.quest.input.PanelInput
import app.trove.quest.input.PointerRegion
import app.trove.quest.input.Target
import app.trove.quest.input.rectAt
import app.trove.quest.input.rectFacing
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import app.trove.quest.posture.turnedPose
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.BlendFactor
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.LayerAlphaBlend
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.core.Vector4
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.StereoMode
import androidx.media3.common.C
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelCreationSystem
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import app.trove.quest.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One storm tile: a flat video panel with its own small ExoPlayer. A cut is `setMediaItem` on the
 * same player, so the decoder is reused and the compositor keeps showing the last frame until the
 * next one arrives (no second player, so one decoder instance per tile). Tiles are not grabbable:
 * the layout owns their places.
 */
class StormTile(
    private val activity: AppSystemActivity,
    private val api: TroveApi,
    private val scope: CoroutineScope,
    index: Int,
    pose: Pose,
    private val widthM: Float,
    private val heightM: Float,
    /** Focus: the selected tile is not bumped (its size is the layout's). */
    private val bumpSelected: Boolean = true,
    private val onClick: (StormTile) -> Unit,
    private val onFailed: (StormTile, Card) -> Unit,
    /** An original (not a preview) started by [play] is ready to render: how many ms it took (the Focus centre's upgrade timing). */
    private val onOriginalReady: ((StormTile, Long) -> Unit)? = null,
    /** The expected length (ms) of a card's preview (PreviewMontage), so it can start at a random point; 0 = unknown. */
    private val previewLengthMs: (Card) -> Long = { 0L },
    /** The first frame of a clip is on screen (the storm fades a new wall in on it). */
    private val onFirstFrame: ((StormTile) -> Unit)? = null,
) {
  private val main = Handler(Looper.getMainLooper())
  private var closed = false
  private var surface: Surface? = null
  private var panelId = PanelIds.next()
  private var entity: Entity? = null
  private var lastClickMs = 0L

  /** The slot this tile is in (Focus swaps change it). */
  var index: Int = index
    private set
  private var pose: Pose = pose
  private var layoutScale = 1f
  private var selectedNow = false
  private var visible = true
  private var stereo = StormStereo.NONE
  private var grabEnabled = false
  private var fade = 1f
  private var sceneObject: SceneObject? = null

  /** Size on screen relative to the registered quad: the layout's scale and the selected bump. */
  private val scale: Float
    get() = layoutScale * (if (selectedNow && bumpSelected) SELECTED_SCALE else 1f)

  val clipKind: ClipKind
    get() = kind

  /** The scene on this tile now, and where in it the clip began (0 for a preview: its position is not the scene's). */
  var card: Card? = null
    private set
  private var kind = ClipKind.PREVIEW
  private var previewSeeked = false
  private var originalRequestedAt = 0L

  private val player: ExoPlayer =
      // Many players at once: a small buffer each (a clip is a few seconds), HTTP/1.1 (MediaPlayers).
      MediaPlayers.create(activity, api.http, MediaPlayers.TILE_BUFFER_BYTES, bufferMs = intArrayOf(2_000, 6_000, 500, 1_000))
          .also { p ->
            StormLive.players.incrementAndGet()
            p.volume = 0f
            // Silent tiles decode no audio at all (one fewer decoder each); the sound tile turns it on.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            p.addListener(
                object : Player.Listener {
                  override fun onPlayerError(e: PlaybackException) {
                    Log.w(TAG, "tile $index: playback error for ${card?.id}", e)
                    card?.let { onFailed(this@StormTile, it) }
                  }

                  override fun onRenderedFirstFrame() {
                    onFirstFrame?.invoke(this@StormTile)
                  }

                  override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && kind == ClipKind.SEEK && originalRequestedAt > 0L) {
                      val took = SystemClock.elapsedRealtime() - originalRequestedAt
                      originalRequestedAt = 0L
                      onOriginalReady?.invoke(this@StormTile, took)
                    }
                    // Previews are short and loop: start each at a random point so tiles differ.
                    // Normally picked before loading ([play]); this seek is the fallback when the
                    // preview's length could not be told in advance.
                    if (state == Player.STATE_READY && kind == ClipKind.PREVIEW && !previewSeeked) {
                      previewSeeked = true
                      val d = p.duration
                      if (d > 4_000) p.seekTo((d * 0.7 * Math.random()).toLong())
                    }
                  }
                })
          }

  init {
    mount(StormStereo.NONE)
  }

  /** Registers a video panel for [st] and puts an entity on it; replaces the old one (the player stays). */
  private fun mount(st: StormStereo) {
    val id = PanelIds.next()
    StormLive.panels.incrementAndGet()
    val oldId = panelId
    val oldEntity = entity
    stereo = st
    panelId = id
    activity.registerPanel(
        VideoSurfacePanelRegistration(
            id,
            surfaceConsumer = { _, sf ->
              if (!closed && panelId == id) {
                surface = sf
                player.setVideoSurface(sf)
              }
            },
            settingsCreator = {
              MediaPanelSettings(
                  shape = QuadShapeOptions(widthM, heightM),
                  display = PixelDisplayOptions(SURFACE_W, SURFACE_H),
                  rendering =
                      MediaPanelRenderOptions(
                          stereoMode =
                              when (st) {
                                StormStereo.NONE -> StereoMode.None
                                StormStereo.LEFT_RIGHT -> StereoMode.LeftRight
                                StormStereo.UP_DOWN -> StereoMode.UpDown
                              }),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  input = PanelInput.triggersOnly,
              )
            },
        ))
    val e = Entity.create(listOf(Panel(id), Transform(pose), Scale(Vector3(scale)), Visible(visible), Grabbable(grabEnabled, GrabbableType.PIVOT_Y)))
    entity = e
    sceneObject = null
    listenForTrigger(e)
    if (oldEntity != null) {
      oldEntity.destroy()
      activity.panelRegistrations.remove(oldId)
      activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(oldId)
      StormLive.panels.decrementAndGet()
    }
  }

  /** Cuts to [card]: a preview from its start, or [startMs] into the original. */
  fun play(card: Card, kind: ClipKind, startMs: Long, playing: Boolean) {
    if (closed) return
    // Stereo is fixed at panel registration: a scene of another kind gets a new panel (same player).
    val want = Storm.stereoFor(card)
    if (want != stereo) mount(want)
    val wasPreview = this.kind == ClipKind.PREVIEW && this.card != null
    this.card = card
    this.kind = kind
    val previewStart = if (kind == ClipKind.PREVIEW) Storm.previewStartMs(previewLengthMs(card), Math.random()) else 0L
    previewSeeked = previewStart > 0L
    // A preview giving way to its original stops where it is: its last frame stays up (frozen) while the
    // original buffers, instead of the montage carrying on / jumping to its next segment.
    if (kind == ClipKind.SEEK && wasPreview) player.playWhenReady = false
    originalRequestedAt = if (kind == ClipKind.SEEK) SystemClock.elapsedRealtime() else 0L
    val url = if (kind == ClipKind.SEEK) api.streamUrl(card.id) else api.previewUrl(card.id)
    player.repeatMode = if (kind == ClipKind.PREVIEW) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    player.setMediaItem(MediaItem.fromUri(url), if (kind == ClipKind.SEEK) startMs else previewStart)
    player.prepare()
    player.playWhenReady = playing
  }

  /**
   * Scene time (ms) of what this tile shows now, for Solo and the Focus upgrade: an original plays
   * the whole file from its start ([play] passes the start to setMediaItem), so its position already
   * is scene time; a preview is a montage, so its position is mapped through [montage] (Stash's
   * segment layout) to the exact spot in the scene.
   */
  fun sceneTimeMs(montage: PreviewMontage): Long {
    val c = card ?: return 0L
    val pos = player.currentPosition.coerceAtLeast(0)
    if (kind == ClipKind.SEEK) return pos
    val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
    return montage.sceneTimeMs(pos, c.duration, dur)
  }

  /** The player's position in the clip (ms) and the loaded clip's length (0 when not known yet). */
  fun playerPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

  fun playerDurationMs(): Long = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

  /** Seeks the original ([ms] = scene time; the stream is the whole file). Previews are not seeked. */
  fun seekTo(ms: Long) {
    if (!closed && kind == ClipKind.SEEK) player.seekTo(ms.coerceAtLeast(0L))
  }

  fun setPlaying(playing: Boolean) {
    if (!closed) player.playWhenReady = playing
  }

  fun setVolume(v: Float) {
    if (closed) return
    player.volume = v
    val audio = v > 0f
    val params = player.trackSelectionParameters
    if (params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) == audio) {
      player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio).build()
    }
  }

  /** The selected tile is a bit bigger (wall / surround). */
  fun setSelected(selected: Boolean) {
    selectedNow = selected
    applyScale()
  }

  private fun applyScale() {
    entity?.setComponent(Scale(Vector3(scale)))
  }

  /** Moves the tile to [p] at [layoutScale] x its registered size (layout changes and swap frames). */
  fun place(p: Pose, layoutScale: Float) {
    if (closed) return
    this.layoutScale = layoutScale
    // Held with grip: the SDK moves it; the manager follows it ([adoptHeldPose]).
    if (isGrabbed()) return
    pose = p
    entity?.setComponent(Transform(p))
    applyScale()
  }

  fun layoutScale(): Float = layoutScale

  /** Only the Focus centre can be moved with grip (moves the whole layout, StormManager.tickGrab). */
  fun setGrabbable(on: Boolean) {
    if (closed || grabEnabled == on) return
    grabEnabled = on
    entity?.setComponent(Grabbable(on, GrabbableType.PIVOT_Y))
  }

  fun isGrabbed(): Boolean = entity?.tryGetComponent<Grabbable>()?.isGrabbed == true

  /** While held: the pose the grab has given the entity, taken as the tile's own (regions, scrub bar follow it). */
  fun adoptHeldPose(): Pose? {
    val p = entity?.tryGetComponent<Transform>()?.transform ?: return null
    pose = p
    return p
  }

  /**
   * Fades the tile: 1 = as is, 0 = gone. Done on the compositor layer (colour scale; source-alpha
   * blend so it is a true fade, not just a darkening). A tile whose layer isn't there yet keeps
   * the value and gets it when the scene object arrives.
   */
  fun setFade(a: Float) {
    fade = a.coerceIn(0f, 1f)
    applyFade()
  }

  private var blendSet = false

  private fun applyFade() {
    if (fade >= 1f && !blendSet) return
    val layer = (sceneObject as? PanelSceneObject)?.layer ?: return
    runCatching {
      if (!blendSet) {
        layer.setAlphaBlend(LayerAlphaBlend(BlendFactor.SOURCE_ALPHA, BlendFactor.ONE_MINUS_SOURCE_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SOURCE_ALPHA))
        blendSet = true
      }
      layer.setColorScaleBias(Vector4(fade, fade, fade, fade), Vector4(0f, 0f, 0f, 0f))
    }.onFailure { Log.w(TAG, "tile fade unavailable: ${it.message}") }
  }

  fun isPlaying(): Boolean = !closed && player.playWhenReady

  /** The tile now lives in slot [i] (Focus swap). */
  fun moveToSlot(i: Int) {
    index = i
  }

  /** The registered size, so a layout can tell the scale a pose needs. */
  val baseWidthM: Float
    get() = widthM

  val baseHeightM: Float
    get() = heightM

  /** Where a ray can hit this tile (the stick's Next clip rule). */
  fun pointerRegion(target: Target = Target.TILE, head: Vector3? = null): PointerRegion? {
    if (entity == null) return null
    val p = pose
    val hw = widthM * scale / 2f
    val hh = heightM * scale / 2f
    // Tiles are level and face the head: with the head known the rectangle comes from the two positions
    // alone (no quaternion maths between the tile's pose and the ray).
    val rect = if (head != null) rectFacing(p.t, head, hw, hh) else rectAt(p, Vector3(0f, 0f, 0f), hw, hh)
    return PointerRegion(target, rect)
  }

  /** Centre of the tile, or null once closed. */
  fun position(): Vector3? = if (entity == null) null else pose.t

  fun currentPose(): Pose = pose

  /** Turned about the vertical axis through [head] by [deltaDeg] ("Bring screens to me"); tiles are level. */
  fun turnAbout(head: Vector3, deltaDeg: Float) {
    if (entity == null) return
    place(turnedPose(pose, head, deltaDeg), layoutScale)
  }

  fun setVisible(visible: Boolean) {
    this.visible = visible
    entity?.setComponent(Visible(visible))
  }

  fun close() {
    if (closed) return
    closed = true
    player.clearVideoSurface()
    player.release()
    StormLive.players.decrementAndGet()
    entity?.destroy()
    entity = null
    activity.panelRegistrations.remove(panelId)
    activity.systemManager.findSystem<PanelCreationSystem>().panelCreator.remove(panelId)
    StormLive.panels.decrementAndGet()
  }

  /** Trigger on the tile (not with grip held). The scene object appears a frame or so after the entity: retry briefly. */
  private fun listenForTrigger(entity: Entity) {
    val listener =
        object : InputListener {
          override fun onInput(
              receiver: SceneObject,
              hitInfo: HitInfo,
              sourceOfInput: Entity,
              changed: Int,
              clicked: Int,
              downTime: Long,
          ): Boolean {
            if ((changed and clicked and TRIGGERS) != 0) {
              val grip = ((sourceOfInput.tryGetComponent<Controller>()?.buttonState ?: 0) and GRIPS) != 0
              main.post {
                val now = SystemClock.elapsedRealtime()
                if (!closed && !grip && now - lastClickMs >= CLICK_GAP_MS) {
                  lastClickMs = now
                  onClick(this@StormTile)
                }
              }
            }
            return false
          }
        }
    scope.launch {
      repeat(50) {
        if (closed || this@StormTile.entity !== entity) return@launch
        val future = activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)
        if (future != null) {
          future.thenAccept { so ->
            so?.addInputListener(listener)
            main.post {
              if (!closed && this@StormTile.entity === entity) {
                sceneObject = so
                blendSet = false
                if (fade < 1f) applyFade()
              }
            }
          }
          return@launch
        }
        delay(50)
      }
      Log.w(TAG, "no scene object for storm tile $index: trigger is off")
    }
  }

  private companion object {
    const val TAG = "StormTile"
    const val SURFACE_W = 960
    const val SURFACE_H = 540
    const val SELECTED_SCALE = 1.08f
    const val CLICK_GAP_MS = 300L
    val TRIGGERS = ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
    val GRIPS = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR
  }
}
