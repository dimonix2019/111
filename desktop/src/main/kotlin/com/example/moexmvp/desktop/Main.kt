package com.example.moexmvp.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    val repoRoot = detectRepoRoot()
    val csvArg = args.firstOrNull { !it.startsWith("-") }
    val csvPath = resolveM15CsvPath(repoRoot, csvArg)
    val points = csvPath?.let { runCatching { loadM15PointsFromCsv(it) }.getOrNull() }.orEmpty()
    SwingUtilities.invokeLater {
        DesktopReplayFrame(points, csvPath?.toString(), repoRoot.toString()).isVisible = true
    }
}

private class DesktopReplayFrame(
    private val points: List<DataPoint>,
    csvPath: String?,
    repoRoot: String,
) : JFrame("MOEX Bar Replay · Windows") {
    private var entryThreshold = 0.7
    private var exitThreshold = 0.5
    private var playing = false
    private var speed = 1f
    private var scrubbing = false

    private val statusLabel = JLabel(" ")
    private val chartPanel = ReplayChartPanel()
    private val playTimer = Timer(900) { onTimerTick() }
    private val slider = JSlider(0, 1000, 0)

    private lateinit var engine: BarReplayEngine

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(900, 600)
        setSize(1100, 720)
        layout = BorderLayout(8, 8)
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        if (points.size < 2) {
            statusLabel.text = buildString {
                append("<html>Нет CSV. Корень: ")
                append(repoRoot)
                append("<br>Нужен strategy-web/data/m15_tatn_255d.csv")
                if (csvPath != null) append("<br>Пробовали: $csvPath")
                append("</html>")
            }
            add(statusLabel, BorderLayout.CENTER)
        } else {
            rebuildEngine()
            val top = JPanel(BorderLayout())
            top.add(
                JLabel("Z-score · 15м Bar Replay · ${points.size} баров · ${csvPath?.substringAfterLast('\\') ?: "CSV"}"),
                BorderLayout.WEST,
            )
            statusLabel.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            top.add(statusLabel, BorderLayout.SOUTH)
            add(top, BorderLayout.NORTH)

            chartPanel.background = Color(0x13, 0x17, 0x22)
            add(chartPanel, BorderLayout.CENTER)

            val controls = JPanel(FlowLayout(FlowLayout.CENTER, 6, 4))
            controls.add(btn("⏮") { seekStart() })
            controls.add(btn("⏪") { stepBack() })
            controls.add(btn("▶/⏸") { togglePlay() })
            controls.add(btn("⏩") { stepForward() })
            controls.add(btn("⏭") { seekEnd() })
            for (s in BAR_REPLAY_SPEEDS) {
                controls.add(btn(formatReplaySpeed(s)) {
                    speed = s
                    engine.speed = s
                    if (playing) playTimer.delay = barReplayDelayMs(speed).toInt()
                })
            }
            add(controls, BorderLayout.SOUTH)

            slider.addChangeListener {
                if (!slider.valueIsAdjusting) {
                    scrubbing = false
                    return@addChangeListener
                }
                scrubbing = true
                playing = false
                playTimer.stop()
                val frac = slider.value / 1000f
                val minC = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(engine.lastIndex)
                val span = (engine.lastIndex - minC).coerceAtLeast(0)
                engine.seekTo((minC + frac * span).roundToInt())
                refreshUi()
            }
            val sliderPanel = JPanel(BorderLayout())
            sliderPanel.add(slider, BorderLayout.CENTER)
            add(sliderPanel, BorderLayout.PAGE_END)

            refreshUi()
        }
        setLocationRelativeTo(null)
    }

    private fun btn(text: String, action: () -> Unit) = JButton(text).apply {
        addActionListener { action() }
    }

    private fun rebuildEngine() {
        engine = BarReplayEngine(
            ReplayConfig(
                points = points,
                thresholds = DynamicThresholds(entryThreshold, exitThreshold),
                startIndex = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(points.lastIndex),
            ),
        )
    }

    private fun refreshUi() {
        val frame = engine.frameAtCursor()
        val range = barReplayVisibleIndexRange(points, frame.cursorIndex)
        val windowPoints = if (range.isEmpty()) emptyList()
        else points.subList(range.first, range.last + 1)
        chartPanel.update(
            windowPoints = windowPoints,
            edges = frame.signalEdgesSoFar,
            entry = entryThreshold,
            exit = exitThreshold,
        )
        val z = frame.visiblePoints.lastOrNull()?.zScore
        val zText = z?.let { "%+.2f".format(it) } ?: "—"
        val pos = when (frame.position) {
            ZStrategyPosition.Flat -> "Flat"
            ZStrategyPosition.Long -> "Long"
            ZStrategyPosition.Short -> "Short"
        }
        statusLabel.text = "${frame.barLabel} · Z $zText · $pos · сигн. ${frame.signalEdgesSoFar.size}"
        slider.value = (engine.progressFraction * 1000).roundToInt()
    }

    private fun togglePlay() {
        if (playing) {
            playing = false
            playTimer.stop()
            engine.pause()
        } else {
            engine.speed = speed
            engine.play()
            playing = true
            playTimer.delay = barReplayDelayMs(speed).toInt()
            playTimer.start()
        }
        refreshUi()
    }

    private fun onTimerTick() {
        if (scrubbing) return
        val next = engine.stepForward()
        if (next == null) {
            playing = false
            playTimer.stop()
        }
        refreshUi()
    }

    private fun stepBack() {
        playing = false
        playTimer.stop()
        engine.stepBackward()
        refreshUi()
    }

    private fun stepForward() {
        playing = false
        playTimer.stop()
        engine.pause()
        engine.stepForward()
        refreshUi()
    }

    private fun seekStart() {
        playing = false
        playTimer.stop()
        engine.seekToStart()
        refreshUi()
    }

    private fun seekEnd() {
        playing = false
        playTimer.stop()
        engine.seekToEnd()
        refreshUi()
    }
}

private class ReplayChartPanel : JPanel() {
    private var windowPoints: List<DataPoint> = emptyList()
    private var edges: List<ZStrategy15mSignalEdge> = emptyList()
    private var entry = 0.7
    private var exit = 0.5

    fun update(
        windowPoints: List<DataPoint>,
        edges: List<ZStrategy15mSignalEdge>,
        entry: Double,
        exit: Double,
    ) {
        this.windowPoints = windowPoints
        this.edges = edges
        this.entry = entry
        this.exit = exit
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (windowPoints.size < 2) return

        val padL = 48
        val padR = 16
        val padT = 12
        val padB = 28
        val w = width - padL - padR
        val h = height - padT - padB
        if (w <= 0 || h <= 0) return

        val zValues = windowPoints.map { it.zScore }
        var yMin = (zValues.minOrNull() ?: -1.0) - 0.2
        var yMax = (zValues.maxOrNull() ?: 1.0) + 0.2
        yMin = minOf(yMin, -entry - 0.3, -exit - 0.3)
        yMax = maxOf(yMax, entry + 0.3, exit + 0.3)
        val ySpan = (yMax - yMin).coerceAtLeast(0.5)

        fun yOf(z: Double): Int = padT + (h * (1.0 - (z - yMin) / ySpan)).toInt()
        fun xOf(index: Int): Int {
            val last = (windowPoints.size - 1).coerceAtLeast(1)
            return padL + (w * index / last)
        }

        g2.color = Color(0x61, 0x61, 0x61)
        for (value in listOf(entry, -entry, exit, -exit, 0.0)) {
            val y = yOf(value)
            g2.drawLine(padL, y, padL + w, y)
        }

        g2.color = Color(0x42, 0xA5, 0xF5)
        for (i in 0 until windowPoints.size - 1) {
            g2.drawLine(
                xOf(i), yOf(windowPoints[i].zScore),
                xOf(i + 1), yOf(windowPoints[i + 1].zScore),
            )
        }

        val edgeByTs = edges.associateBy { it.bar.timestampMillis }
        windowPoints.forEachIndexed { index, point ->
            val edge = edgeByTs[point.timestampMillis] ?: return@forEachIndexed
            g2.color = when (edge.signal) {
                ZStrategySignal.EnterLong -> Color(0x69, 0xF0, 0xAE)
                ZStrategySignal.EnterShort -> Color(0xFF, 0x52, 0x52)
                ZStrategySignal.ExitLong -> Color(0x80, 0xCB, 0xC4)
                ZStrategySignal.ExitShort -> Color(0xFF, 0xAB, 0x91)
                ZStrategySignal.None -> return@forEachIndexed
            }
            val x = xOf(index)
            val y = yOf(point.zScore)
            g2.fillOval(x - 4, y - 4, 8, 8)
        }

        val lastIdx = windowPoints.lastIndex
        val cx = xOf(lastIdx)
        g2.color = Color(0xFA, 0xCC, 0x15)
        g2.drawLine(cx, padT, cx, padT + h)
        g2.fillOval(cx - 4, yOf(windowPoints[lastIdx].zScore) - 4, 8, 8)
    }
}
