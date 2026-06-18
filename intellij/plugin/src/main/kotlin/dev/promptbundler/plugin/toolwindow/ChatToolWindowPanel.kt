package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val COMPOSER_PLACEHOLDER = "Ask your question for your web copilot agent and add context"
private const val ARC = 16
private val ACCENT = JBColor.namedColor("Component.focusedBorderColor", JBColor(0x3574F0, 0x548AF7))

/**
 * The chat panel hosted by the PromptBundler tool window: a scrollable conversation on top
 * and a rounded composer at the bottom, styled after a modern chat assistant. Sending a
 * question turns it, via [PromptChatController], into a copyable meta-prompt reply. Context
 * attachment is out of scope for M2.
 */
class ChatToolWindowPanel(
    private val controller: PromptChatController = PromptChatController(),
) : SimpleToolWindowPanel(true, true) {
    private val conversation = JPanel(VerticalLayout(JBUI.scale(14)))
    private val conversationScroll =
        JBScrollPane(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(conversation, BorderLayout.NORTH)
            },
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        )
    private val composer = JBTextArea()
    private val sendButton = SendButton { send() }

    // Subtle input fill, distinct from the panel background in both themes so the composer
    // reads as a raised field. A JBColor (not UIUtil.getTextFieldBackground(), which is
    // resolved once) so it re-resolves when the user switches light <-> dark at runtime.
    private val composerBox = RoundedPanel(JBColor(0xF2F3F5, 0x393B40))

    init {
        conversation.isOpaque = false
        conversation.border = JBUI.Borders.empty(12, 12, 4, 12)
        conversationScroll.border = JBUI.Borders.empty()
        conversationScroll.viewport.isOpaque = false
        conversationScroll.isOpaque = false

        val root = JPanel(BorderLayout())
        root.add(conversationScroll, BorderLayout.CENTER)
        root.add(buildComposer(), BorderLayout.SOUTH)
        setContent(root)
    }

    private fun buildComposer(): JComponent {
        composer.rows = 3
        composer.lineWrap = true
        composer.wrapStyleWord = true
        composer.isOpaque = false
        composer.border = JBUI.Borders.empty()
        composer.font = JBFont.label()
        composer.emptyText.text = COMPOSER_PLACEHOLDER

        // Enter sends; Shift+Enter keeps a normal newline (insert-break is already wired in
        // the text area's action map, we just bind the Shift+Enter keystroke to it).
        composer.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pb-send")
        composer.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
            "insert-break",
        )
        composer.actionMap.put(
            "pb-send",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = send()
            },
        )

        composer.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refreshSendState()

                override fun removeUpdate(e: DocumentEvent) = refreshSendState()

                override fun changedUpdate(e: DocumentEvent) = refreshSendState()
            },
        )

        // Accent the rounded composer border while the input has focus.
        composer.addFocusListener(
            object : FocusListener {
                override fun focusGained(e: FocusEvent?) {
                    composerBox.focused = true
                }

                override fun focusLost(e: FocusEvent?) {
                    composerBox.focused = false
                }
            },
        )

        refreshSendState()

        val controls =
            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(sendButton)
            }

        composerBox.layout = BorderLayout(0, JBUI.scale(6))
        composerBox.border = JBUI.Borders.empty(10, 12, 8, 10)
        composerBox.add(composer, BorderLayout.CENTER)
        composerBox.add(controls, BorderLayout.SOUTH)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 12, 12)
            add(composerBox, BorderLayout.CENTER)
        }
    }

    private fun refreshSendState() {
        sendButton.isEnabled = composer.text.isNotBlank()
    }

    /**
     * Sends the current composer content: appends the question, asks the controller for the
     * meta-prompt and appends the assistant reply. No-op when the question is blank.
     */
    fun send() {
        val query = composer.text
        if (query.isBlank()) return

        addRow(userRow(query))
        val metaPrompt = controller.buildMetaPrompt(query)
        addRow(assistantRow(metaPrompt))

        composer.text = ""
        composer.requestFocusInWindow()
    }

    private fun addRow(row: JComponent) {
        conversation.add(row)
        conversation.revalidate()
        conversation.repaint()
        invokeLater {
            val bar = conversationScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    /** User message: a compact accent bubble pushed to the right edge. */
    private fun userRow(text: String): JComponent {
        val bubble = RoundedPanel(userBubbleBackground(), borderColor = null)
        bubble.layout = BorderLayout()
        bubble.border = JBUI.Borders.empty(7, 12)
        bubble.add(bodyArea(text, userBubbleForeground()), BorderLayout.CENTER)
        bubble.maximumSize = Dimension(JBUI.scale(300), Int.MAX_VALUE)

        return rowWrapper().apply {
            add(Box.createHorizontalGlue())
            add(bubble)
        }
    }

    /** Assistant reply: full width, role header with a Copy action, then the meta-prompt. */
    private fun assistantRow(metaPrompt: String): JComponent {
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.add(
            JBLabel("Prompt Bundler").apply {
                font = JBFont.label().asBold()
                foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            },
            BorderLayout.WEST,
        )
        header.add(
            JButton("Copy").apply {
                isOpaque = false
                addActionListener { controller.copyToClipboard(metaPrompt) }
            },
            BorderLayout.EAST,
        )

        val body = bodyArea(metaPrompt, JBColor.foreground())

        return object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                header.alignmentX = Component.LEFT_ALIGNMENT
                body.alignmentX = Component.LEFT_ALIGNMENT
                body.border = JBUI.Borders.emptyTop(4)
                add(header)
                add(body)
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun rowWrapper(): JPanel =
        object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
            }

            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun bodyArea(
        text: String,
        fg: Color,
    ): JBTextArea =
        JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = fg
            font = JBFont.label()
            border = JBUI.Borders.empty()
        }

    private fun userBubbleBackground(): Color = JBColor(0x3574F0, 0x3574F0)

    private fun userBubbleForeground(): Color = JBColor(0xFFFFFF, 0xFFFFFF)
}

/**
 * A panel that paints a rounded background and, optionally, a rounded border that brightens
 * to the accent color while [focused]. Used both for the composer and the user bubble.
 */
private class RoundedPanel(
    private val fill: Color,
    private val borderColor: Color? = JBColor.border(),
) : JPanel() {
    var focused: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(ARC)
            g2.color = fill
            g2.fillRoundRect(0, 0, width, height, arc, arc)

            val stroke = if (focused) ACCENT else borderColor
            if (stroke != null) {
                val w = if (focused) JBUI.scale(2).toFloat() else JBUI.scale(1).toFloat()
                val inset = w / 2f
                g2.color = stroke
                g2.stroke = BasicStroke(w)
                g2.drawRoundRect(
                    inset.toInt(),
                    inset.toInt(),
                    (width - w).toInt(),
                    (height - w).toInt(),
                    arc,
                    arc,
                )
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** A circular send button: filled accent with a white up-arrow, dimmed when disabled. */
private class SendButton(
    private val onSend: () -> Unit,
) : JButton() {
    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        border = JBUI.Borders.empty()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Send (Enter)"
        val d = JBUI.size(26, 26)
        preferredSize = d
        minimumSize = d
        maximumSize = d
        addActionListener { if (isEnabled) onSend() }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val circle = if (isEnabled) ACCENT else JBColor.namedColor("Button.disabledBorderColor", JBColor.GRAY)
            g2.color = circle
            g2.fillOval(0, 0, width, height)

            // Up arrow.
            g2.color = if (isEnabled) JBColor(0xFFFFFF, 0xFFFFFF) else JBColor.namedColor("Label.disabledForeground", JBColor.LIGHT_GRAY)
            g2.stroke = BasicStroke(JBUI.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val cx = width / 2
            val top = JBUI.scale(8)
            val bottom = height - JBUI.scale(8)
            val wing = JBUI.scale(4)
            g2.drawLine(cx, top, cx, bottom)
            g2.drawLine(cx, top, cx - wing, top + wing)
            g2.drawLine(cx, top, cx + wing, top + wing)
        } finally {
            g2.dispose()
        }
    }
}
