package com.dshui.idea.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 新会话启动过场：dsh-tui 画出第一帧前，终端标签只有一根闪烁的光标，
 * 观感差。这里用不透明面板盖住终端：一张预渲染的静态鲸鱼喷水 PNG
 * （icons/whale-spout.png）居中贴图，加载状态由文案的动态省略号表达——
 * 画面无任何矢量/坐标动画。终端输出模型出现文本（TUI 首帧）后淡出，
 * 45 秒兜底强制放行（启动失败时用户能直接看到终端报错）。
 */
class DshWhaleLoadingPanel(wrapped: JComponent) : JPanel(BorderLayout()) {

    private var overlayAlpha = 1f

    @Volatile
    private var fading = false

    private val startedAt = System.currentTimeMillis()
    private var animator: Timer? = null

    init {
        isOpaque = false
        add(wrapped, BorderLayout.CENTER)
    }

    fun start(disposable: Disposable) {
        animator = Timer(33) {
            if (fading) {
                if (overlayAlpha <= FADE_STEP) {
                    overlayAlpha = 0f
                    animator?.stop()
                } else {
                    overlayAlpha -= FADE_STEP
                }
            }
            repaint()
        }
        animator?.start()
        Disposer.register(disposable) {
            animator?.stop()
            animator = null
        }
    }

    /** TUI 已就绪：交给动画计时器淡出。 */
    fun fadeOut() {
        fading = true
    }

    fun isExpired(timeoutMs: Long): Boolean = System.currentTimeMillis() - startedAt > timeoutMs

    fun isGone(): Boolean = overlayAlpha <= 0f

    public override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        if (overlayAlpha <= 0f) return
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.SrcOver.derive(overlayAlpha)
            paintOverlay(g2, width, height, System.currentTimeMillis())
        } finally {
            g2.dispose()
        }
    }

    private fun paintOverlay(g: Graphics2D, w: Int, h: Int, now: Long) {
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, w, h)

        val art = artImage ?: return
        val box = minOf(JBUI.scale(180), (w * 0.62f).toInt(), (h * 0.55f).toInt()).coerceAtLeast(96)
        val ox = (w - box) / 2
        val oy = (h - box) / 2
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(art, ox, oy, box, box, null)

        // 加载状态：动态省略号
        val dots = ".".repeat(((now / 380) % 4).toInt())
        g.color = INK
        g.font = Font(Font.DIALOG, Font.PLAIN, JBUI.scaleFontSize(13f).toInt())
        val text = "正在启动 dsh-tui$dots"
        val tw = g.fontMetrics.stringWidth(text)
        g.drawString(text, ((w - tw) / 2f), (oy + box + JBUI.scale(16)).toFloat())
    }

    companion object {
        private val INK = JBColor.namedColor("Label.disabledForeground", JBColor(Color(0x6C707E), Color(0xA8ADBD)))
        private const val FADE_STEP = 0.09f

        private val artImage: Image? by lazy {
            runCatching {
                ImageIO.read(DshWhaleLoadingPanel::class.java.getResourceAsStream("/icons/whale-spout.png"))
            }.getOrNull()
        }

        /**
         * 给刚创建的会话标签挂上过场：包一层 [DshWhaleLoadingPanel]，轮询终端
         * 输出模型，TUI 首帧（文本长度非 0）后淡出；45 秒兜底。
         */
        fun attach(tab: TerminalToolWindowTab) {
            val overlay = DshWhaleLoadingPanel(tab.content.component)
            tab.content.component = overlay
            overlay.start(tab.content)
            val poll = Timer(100) {
                if (overlay.isGone()) {
                    (it.source as Timer).stop()
                } else if (overlay.isExpired(45_000) || hasTerminalOutput(tab)) {
                    overlay.fadeOut()
                }
            }
            poll.start()
            Disposer.register(tab.content as Disposable) { poll.stop() }
        }

        private fun hasTerminalOutput(tab: TerminalToolWindowTab): Boolean = runCatching {
            val models = tab.view.outputModels
            models.regular.textLength > 0 || models.alternative.textLength > 0
        }.getOrDefault(false)
    }
}
