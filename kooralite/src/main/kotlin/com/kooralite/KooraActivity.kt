// Place this file in an Android application module (NOT in the CloudStream plugin module).
// Add dependency in app/build.gradle:
// implementation "com.google.android.exoplayer:exoplayer:2.19.0"
// And in AndroidManifest.xml add INTERNET permission and declare the activity if required.

package com.kooralite.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.MediaSource
import kotlin.random.Random

class KooraActivity : AppCompatActivity() {

    private val servers = listOf(
        "cdn1.4job.online",
        "cdn2.4job.online"
    )

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var tryOrder: MutableList<String>
    private var currentIndex = 0
    private lateinit var messageOverlay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tryOrder = servers.shuffled(Random(System.currentTimeMillis())).toMutableList()

        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 3000
        }
        container.addView(playerView)

        messageOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.CENTER
            }
            setBackgroundColor(0x99000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            text = ""
            textSize = 16f
            setPadding(24, 12, 24, 12)
            visibility = android.view.View.GONE
        }
        container.addView(messageOverlay)

        setContentView(container)
    }

    override fun onStart() {
        super.onStart()
        initializePlayerIfNeeded()
        currentIndex = 0
        playCurrentServer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayerIfNeeded() {
        if (player != null) return

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.playWhenReady = true

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    tryNextServer("Playback error: ${error.message}")
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) hideMessage()
                }
            })
        }
    }

    private fun playCurrentServer() {
        if (currentIndex >= tryOrder.size) {
            showMessage("تعذر تشغيل البث — الرجاء المحاولة لاحقاً.")
            return
        }

        val host = tryOrder[currentIndex]
        val hlsUrl = buildHlsUrl(host)
        showMessage("Connecting to $host ...")

        val mediaSource = buildHlsMediaSource(this, Uri.parse(hlsUrl))
        player?.setMediaSource(mediaSource)
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun tryNextServer(reason: String? = null) {
        reason?.let { showMessage("خطأ على الخادم ${tryOrder[currentIndex]}: ${it}\nالمحاولة التالية...") }
        currentIndex++
        if (currentIndex < tryOrder.size) {
            playerView.postDelayed({ playCurrentServer() }, 800)
        } else {
            playerView.post { showMessage("جميع الخوادم فشلت. الرجاء التحقق من الروابط أو المحاولة لاحقاً.") }
        }
    }

    private fun buildHlsUrl(host: String): String {
        return "https://$host/live/mbc/playlist.m3u8"
    }

    private fun buildHlsMediaSource(context: Context, uri: Uri): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val mediaItem = MediaItem.fromUri(uri)
        return HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun showMessage(text: String) {
        messageOverlay.text = text
        messageOverlay.visibility = android.view.View.VISIBLE
    }

    private fun hideMessage() {
        messageOverlay.visibility = android.view.View.GONE
    }
}
