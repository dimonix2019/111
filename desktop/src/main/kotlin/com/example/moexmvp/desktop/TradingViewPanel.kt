package com.example.moexmvp.desktop

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.BorderLayout
import java.awt.Color
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JPanel

/** TradingView lightweight-charts (тот же z_chart.html, что Android). */
internal class TradingViewPanel(
    html: String,
) : JPanel(BorderLayout()) {
    private val jfxPanel = JFXPanel()
    private var webEngine: javafx.scene.web.WebEngine? = null

    @Volatile
    private var pageReady = false

    private val pendingScripts = ConcurrentLinkedQueue<String>()

    init {
        background = Color(0x13, 0x17, 0x22)
        add(jfxPanel, BorderLayout.CENTER)
        Platform.runLater {
            val webView = WebView()
            val engine = webView.engine
            webEngine = engine
            engine.isJavaScriptEnabled = true
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) {
                    pageReady = true
                    engine.executeScript("window.moexChartPageReady && window.moexChartPageReady()")
                    drainPending()
                }
            }
            engine.loadContent(html, "text/html")
            jfxPanel.scene = Scene(webView)
        }
    }

    fun pushReplayPayload(payloadJson: String, playing: Boolean) {
        val b64 = encodeReplayPayloadForJs(payloadJson)
        val script = buildString {
            append("window.setReplayCursorFromBase64('")
            append(b64)
            append("');window.setReplayPlaying(")
            append(if (playing) "true" else "false")
            append(");")
        }
        enqueueScript(script)
    }

    private fun enqueueScript(script: String) {
        Platform.runLater {
            if (pageReady) {
                webEngine?.executeScript(script)
            } else {
                pendingScripts.add(script)
            }
        }
    }

    private fun drainPending() {
        Platform.runLater {
            while (true) {
                val script = pendingScripts.poll() ?: break
                webEngine?.executeScript(script)
            }
        }
    }
}
