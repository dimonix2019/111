package com.example.moexmvp.desktop

import javafx.application.Platform
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font as AwtFont
import java.awt.GridLayout
import java.util.concurrent.CountDownLatch
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.table.DefaultTableModel
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    initJavaFxToolkit()
    val repoRoot = detectRepoRoot()
    val csvArg = args.firstOrNull { !it.startsWith("-") }
    val csvPath = resolveM15CsvPath(repoRoot, csvArg)
    val points = csvPath?.let { runCatching { loadM15PointsFromCsv(it) }.getOrNull() }.orEmpty()
    SwingUtilities.invokeLater {
        DesktopReplayFrame(points, csvPath?.toString(), repoRoot.toString()).isVisible = true
    }
}

private fun initJavaFxToolkit() {
  try {
        Platform.startup { }
    } catch (_: IllegalStateException) {
        // toolkit already running
    }
    val latch = CountDownLatch(1)
    Platform.runLater { latch.countDown() }
    latch.await()
}

private class DesktopReplayFrame(
    private val allPoints: List<DataPoint>,
    csvPath: String?,
    repoRoot: String,
) : JFrame("MOEX Bar Replay · TradingView") {
    private var entryThreshold = 0.7
    private var exitThreshold = 0.5
    private var playing = false
    private var speed = 1f
    private var scrubbing = false
    private var chartPeriod = ReplayChartPeriod.D30

    private val statusLabel = JLabel(" ")
    private val progressLabel = JLabel("0%")
    private val playTimer = Timer(900) { onTimerTick() }
    private val slider = JSlider(0, 1000, 0)
    private val playButton = JButton("▶  Play")
    private val speedButtons = mutableListOf<JButton>()
    private val periodButtons = mutableListOf<JButton>()

    private lateinit var engine: BarReplayEngine
    private lateinit var chartPanel: TradingViewPanel
    private val tradesModel = DefaultTableModel(
        arrayOf("№", "Тип", "Вход", "Z вх", "Выход", "Z вых", "Статус"),
        0,
    )

    init {
        applyDarkChrome()
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(1100, 720)
        setSize(1280, 800)
        layout = BorderLayout(0, 0)

        if (allPoints.size < 2) {
            add(
                JLabel(
                    "<html><h3>Нет данных CSV</h3><p>$repoRoot</p>" +
                        "<p>Нужен <code>strategy-web/data/m15_tatn_255d.csv</code></p></html>",
                ),
                BorderLayout.CENTER,
            )
        } else {
            rebuildEngine()
            chartPanel = TradingViewPanel(loadTradingViewChartHtml())

            add(buildTopToolbar(csvPath), BorderLayout.NORTH)
            add(buildStatusBar(), BorderLayout.PAGE_START)

            val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, chartPanel, buildTradesPanel()).apply {
                resizeWeight = 0.78
                dividerLocation = 920
                border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0x2A, 0x2E, 0x39))
            }
            add(split, BorderLayout.CENTER)
            add(buildControlDeck(), BorderLayout.SOUTH)

            refreshUi()
        }
        setLocationRelativeTo(null)
    }

    private fun applyDarkChrome() {
        contentPane.background = Ui.bgPanel
    }

    private fun buildTopToolbar(csvPath: String?): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = Ui.bgToolbar
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.border)
        }
        bar.add(JLabel("TATN/TATNP Z · 15м").apply { foreground = Ui.textPrimary; font = font.deriveFont(AwtFont.BOLD, 13f) })
        bar.add(JLabel("· ${allPoints.size} баров · ${csvPath?.substringAfterLast('\\') ?: "CSV"}").apply {
            foreground = Ui.textMuted
        })
        bar.add(Box.createHorizontalStrut(12))
        bar.add(JLabel("Период:").apply { foreground = Ui.textSecondary })
        for (period in ReplayChartPeriod.entries) {
            val btn = chipButton(period.label, period == chartPeriod) {
                chartPeriod = period
                updatePeriodSelection()
                refreshUi()
            }
            periodButtons += btn
            bar.add(btn)
        }
        bar.add(Box.createHorizontalStrut(12))
        bar.add(JLabel("Вх ±").apply { foreground = Ui.textSecondary })
        entryCombo = JComboBox((3..20).map { "%.1f".format(it / 10.0) }.toTypedArray()).apply {
            selectedItem = "%.1f".format(entryThreshold)
            addActionListener { applyThresholdsFromUi() }
        }
        bar.add(entryCombo)
        bar.add(JLabel("Вых ±").apply { foreground = Ui.textSecondary })
        exitCombo = JComboBox((1..15).map { "%.1f".format(it / 10.0) }.toTypedArray()).apply {
            selectedItem = "%.1f".format(exitThreshold)
            addActionListener { applyThresholdsFromUi() }
        }
        bar.add(exitCombo)
        return bar
    }

    private var entryCombo: JComboBox<String>? = null
    private var exitCombo: JComboBox<String>? = null

    private fun applyThresholdsFromUi() {
        entryThreshold = entryCombo?.selectedItem?.toString()?.toDoubleOrNull() ?: entryThreshold
        exitThreshold = exitCombo?.selectedItem?.toString()?.toDoubleOrNull() ?: exitThreshold
        rebuildEngine()
        refreshUi()
    }

    private fun buildStatusBar(): JPanel {
        val p = JPanel(BorderLayout()).apply {
            background = Ui.bgPanel
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        }
        statusLabel.foreground = Ui.textPrimary
        statusLabel.font = AwtFont("Monospaced", AwtFont.PLAIN, 12)
        p.add(statusLabel, BorderLayout.CENTER)
        return p
    }

    private fun buildTradesPanel(): JScrollPane {
        val table = JTable(tradesModel).apply {
            background = Ui.bgChart
            foreground = Ui.textPrimary
            gridColor = Ui.border
            tableHeader.background = Ui.bgToolbar
            tableHeader.foreground = Ui.textSecondary
            rowHeight = 22
            fillsViewportHeight = true
        }
        return JScrollPane(table).apply {
            preferredSize = Dimension(300, 400)
            border = BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.border)
            background = Ui.bgPanel
        }
    }

    private fun buildControlDeck(): JPanel {
        val deck = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Ui.bgToolbar
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.border)
            preferredSize = Dimension(10, 148)
        }

        val transport = JPanel(FlowLayout(FlowLayout.CENTER, 10, 8)).apply {
            background = Ui.bgToolbar
            add(transportButton("⏮  В начало") { seekStart() })
            add(transportButton("⏪  −1 бар") { stepBack() })
            playButton.addActionListener { togglePlay() }
            playButton.preferredSize = Dimension(120, 38)
            playButton.font = AwtFont(AwtFont.SANS_SERIF, AwtFont.BOLD, 13)
            stylePrimary(playButton)
            add(playButton)
            add(transportButton("⏩  +1 бар") { stepForward() })
            add(transportButton("⏭  В конец") { seekEnd() })
        }
        deck.add(transport)

        val speeds = JPanel(FlowLayout(FlowLayout.CENTER, 6, 4)).apply {
            background = Ui.bgToolbar
            add(JLabel("Скорость:").apply { foreground = Ui.textSecondary })
            for (s in BAR_REPLAY_SPEEDS) {
                val btn = chipButton(formatReplaySpeed(s), kotlin.math.abs(speed - s) < 0.01f) {
                    speed = s
                    engine.speed = s
                    if (playing) playTimer.delay = barReplayDelayMs(speed).toInt()
                    updateSpeedSelection()
                }
                speedButtons += btn
                add(btn)
            }
        }
        deck.add(speeds)

        val scrub = JPanel(BorderLayout(8, 0)).apply {
            background = Ui.bgToolbar
            border = BorderFactory.createEmptyBorder(0, 16, 10, 16)
            progressLabel.foreground = Ui.textSecondary
            progressLabel.preferredSize = Dimension(48, 20)
            add(progressLabel, BorderLayout.EAST)
            slider.addChangeListener {
                if (!slider.valueIsAdjusting) {
                    scrubbing = false
                    return@addChangeListener
                }
                scrubbing = true
                playing = false
                playTimer.stop()
                playButton.text = "▶  Play"
                val frac = slider.value / 1000f
                val minC = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(engine.lastIndex)
                val span = (engine.lastIndex - minC).coerceAtLeast(0)
                engine.seekTo((minC + frac * span).roundToInt())
                refreshUi()
            }
            add(slider, BorderLayout.CENTER)
        }
        deck.add(scrub)
        return deck
    }

    private fun transportButton(text: String, action: () -> Unit) = JButton(text).apply {
        preferredSize = Dimension(110, 34)
        styleSecondary(this)
        addActionListener { action() }
    }

    private fun chipButton(text: String, selected: Boolean, action: () -> Unit) = JButton(text).apply {
        styleChip(this, selected)
        addActionListener {
            action()
            updatePeriodSelection()
            updateSpeedSelection()
        }
    }

    private fun updatePeriodSelection() {
        ReplayChartPeriod.entries.forEachIndexed { i, p ->
            if (i < periodButtons.size) styleChip(periodButtons[i], p == chartPeriod)
        }
    }

    private fun updateSpeedSelection() {
        BAR_REPLAY_SPEEDS.forEachIndexed { i, s ->
            if (i < speedButtons.size) {
                styleChip(speedButtons[i], kotlin.math.abs(speed - s) < 0.01f)
            }
        }
    }

    private fun rebuildEngine() {
        engine = BarReplayEngine(
            ReplayConfig(
                points = allPoints,
                thresholds = DynamicThresholds(entryThreshold, exitThreshold),
                startIndex = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(allPoints.lastIndex),
            ),
        )
    }

    private fun refreshUi() {
        val frame = engine.frameAtCursor()
        val range = barReplayVisibleIndexRange(allPoints, frame.cursorIndex, chartPeriod.visibleDays)
        val windowPoints = if (range.isEmpty()) emptyList()
        else allPoints.subList(range.first, range.last + 1)
        val candles = barReplayWindowCandles(allPoints, frame.cursorIndex, chartPeriod.visibleDays)
        val markers = barReplaySignalMarkers(frame.signalEdgesSoFar, allPoints)
        val trades = buildTradeSegmentsFromEdges(frame.signalEdgesSoFar)
        val payload = buildTradingViewReplayCursorJson(
            candles = candles,
            displayPoints = windowPoints,
            referenceLines = buildZReferenceLines(DynamicThresholds(entryThreshold, exitThreshold)),
            pointMarkers = markers,
            tradeSegments = trades,
            playing = playing,
        )
        chartPanel.pushReplayPayload(payload, playing)

        val z = frame.visiblePoints.lastOrNull()?.zScore
        val zText = z?.let { "%+.2f".format(it) } ?: "—"
        val pos = when (frame.position) {
            ZStrategyPosition.Flat -> "Flat"
            ZStrategyPosition.Long -> "Long"
            ZStrategyPosition.Short -> "Short"
        }
        statusLabel.text = "${frame.barLabel}   ·   Z $zText   ·   $pos   ·   пороги ±$entryThreshold / ±$exitThreshold"
        progressLabel.text = "${(engine.progressFraction * 100).roundToInt()}%"
        slider.value = (engine.progressFraction * 1000).roundToInt()

        tradesModel.rowCount = 0
        for (row in buildTradeTableRows(frame.signalEdgesSoFar)) {
            tradesModel.addRow(
                arrayOf(row.id, row.side, row.entryTime, row.entryZ, row.exitTime, row.exitZ, row.status),
            )
        }
    }

    private fun togglePlay() {
        if (playing) {
            playing = false
            playTimer.stop()
            engine.pause()
            playButton.text = "▶  Play"
        } else {
            engine.speed = speed
            engine.play()
            playing = true
            playTimer.delay = barReplayDelayMs(speed).toInt()
            playTimer.start()
            playButton.text = "⏸  Pause"
        }
        refreshUi()
    }

    private fun onTimerTick() {
        if (scrubbing) return
        val next = engine.stepForward()
        if (next == null) {
            playing = false
            playTimer.stop()
            playButton.text = "▶  Play"
        }
        refreshUi()
    }

    private fun stepBack() {
        playing = false
        playTimer.stop()
        playButton.text = "▶  Play"
        engine.stepBackward()
        refreshUi()
    }

    private fun stepForward() {
        playing = false
        playTimer.stop()
        playButton.text = "▶  Play"
        engine.pause()
        engine.stepForward()
        refreshUi()
    }

    private fun seekStart() {
        playing = false
        playTimer.stop()
        playButton.text = "▶  Play"
        engine.seekToStart()
        refreshUi()
    }

    private fun seekEnd() {
        playing = false
        playTimer.stop()
        playButton.text = "▶  Play"
        engine.seekToEnd()
        refreshUi()
    }
}

/** Тёмная тема в духе TradingView. */
private object Ui {
    val bgToolbar = Color(0x1E, 0x22, 0x2D)
    val bgPanel = Color(0x16, 0x1A, 0x25)
    val bgChart = Color(0x13, 0x17, 0x22)
    val border = Color(0x2A, 0x2E, 0x39)
    val textPrimary = Color(0xD1, 0xD4, 0xDC)
    val textSecondary = Color(0x9E, 0xA4, 0xB0)
    val textMuted = Color(0x6B, 0x72, 0x80)
    val accent = Color(0x29, 0x62, 0xFF)
    val accentBg = Color(0x1E, 0x3A, 0x8A)
}

private fun stylePrimary(button: JButton) {
    button.background = Ui.accent
    button.foreground = Color.WHITE
    button.isFocusPainted = false
    button.border = BorderFactory.createEmptyBorder(6, 14, 6, 14)
}

private fun styleSecondary(button: JButton) {
    button.background = Color(0x2A, 0x2E, 0x39)
    button.foreground = Ui.textPrimary
    button.isFocusPainted = false
    button.border = BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(Ui.border),
        BorderFactory.createEmptyBorder(5, 10, 5, 10),
    )
}

private fun styleChip(button: JButton, selected: Boolean) {
    if (selected) {
        button.background = Ui.accentBg
        button.foreground = Color(0x93, 0xC5, 0xFD)
        button.border = BorderFactory.createLineBorder(Ui.accent)
    } else {
        button.background = Color(0x2A, 0x2E, 0x39)
        button.foreground = Ui.textSecondary
        button.border = BorderFactory.createLineBorder(Ui.border)
    }
    button.isFocusPainted = false
    button.preferredSize = Dimension(52, 28)
}
