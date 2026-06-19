package dev.promptbundler.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.DefaultChooseByNameItemProvider
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.TextComponentEmptyText
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.promptbundler.plugin.context.Attachment
import dev.promptbundler.plugin.context.AttachmentNotifications
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.CollectResult
import dev.promptbundler.plugin.context.ContextBundleListener
import dev.promptbundler.plugin.context.ContextBundleService
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Predicate
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

private const val COMPOSER_PLACEHOLDER = "Ask your question for your web copilot agent and add context"

// Header that opens the user request section of the assembled prompt. The collapsed reply
// keeps everything from this line to the end visible (the request in full).
private const val REQUEST_MARKER = "### USER REQUEST"
private const val ARC = 16

// Tallest the chips area may get before it scrolls instead of growing the composer (~4 rows).
private const val CHIPS_MAX_HEIGHT = 104
private val ACCENT = JBColor.namedColor("Component.focusedBorderColor", JBColor(0x3574F0, 0x548AF7))

/**
 * The chat panel hosted by the PromptBundler tool window: a scrollable conversation on top
 * and a rounded composer at the bottom, styled after a modern chat assistant. Sending a
 * question turns it, via [PromptChatController], into a copyable meta-prompt reply.
 *
 * The composer also carries the attached context: removable chips fed by the project-level
 * [ContextBundleService], a `+` picker (recent files and search by name) and file drag and
 * drop. The panel observes [ContextBundleService.TOPIC] to keep its chips in sync with
 * attachments made from anywhere (project view, editor selection, drop).
 */
class ChatToolWindowPanel(
    private val project: Project,
    private val controller: PromptChatController = PromptChatController(project),
) : SimpleToolWindowPanel(true, true),
    Disposable {
    private val service = ContextBundleService.getInstance(project)
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
    private val chipsRow = ChipsPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))

    // The chips area must not grow unbounded: dropping a folder with many files would otherwise
    // make the composer eat the whole panel and bury the conversation. Above a few rows the chips
    // scroll inside this capped viewport instead of pushing the composer up.
    private val chipsScroll =
        object : JBScrollPane(
            chipsRow,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ) {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                val cap = JBUI.scale(CHIPS_MAX_HEIGHT)
                if (size.height > cap) size.height = cap
                return size
            }
        }
    private val attachButton = IconActionButton(AllIcons.General.Add, "Add context") { openAttachPicker() }
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

        project.messageBus
            .connect(this)
            .subscribe(ContextBundleService.TOPIC, ContextBundleListener { rebuildChips() })
        rebuildChips()
    }

    private fun buildComposer(): JComponent {
        composer.rows = 3
        composer.lineWrap = true
        composer.wrapStyleWord = true
        composer.isOpaque = false
        composer.border = JBUI.Borders.empty()
        composer.font = JBFont.label()
        composer.emptyText.text = COMPOSER_PLACEHOLDER
        // Keep the placeholder visible even while the composer has focus. The platform hides it
        // on focus by default, which reads as the hint vanishing the moment you click in; this
        // visibility predicate (honored by the dynamic status text) ties it to emptiness alone.
        composer.putClientProperty(
            TextComponentEmptyText.STATUS_VISIBLE_FUNCTION,
            Predicate<JTextComponent> { it.text.isEmpty() },
        )
        installFileDrop(composer)

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

        chipsRow.isOpaque = false
        chipsScroll.isOpaque = false
        chipsScroll.viewport.isOpaque = false
        chipsScroll.border = JBUI.Borders.empty()
        chipsScroll.isVisible = false

        val controls =
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(attachButton)
                add(sendButton)
            }

        composerBox.layout = BorderLayout(0, JBUI.scale(6))
        composerBox.border = JBUI.Borders.empty(10, 12, 8, 10)
        composerBox.add(chipsScroll, BorderLayout.NORTH)
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
     * meta-prompt (which folds in the attached context) and appends the assistant reply. The
     * attached context is kept after sending so the user can iterate. No-op when blank.
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

    // --- Attached context ---------------------------------------------------------------

    private fun rebuildChips() {
        invokeLater {
            chipsRow.removeAll()
            val attachments = service.snapshot()
            chipsScroll.isVisible = attachments.isNotEmpty()
            attachments.forEach { chipsRow.add(buildChip(it)) }
            if (attachments.isNotEmpty()) chipsRow.add(buildClearAllButton())
            chipsRow.revalidate()
            chipsRow.repaint()
            chipsScroll.revalidate()
            chipsScroll.repaint()
        }
    }

    private fun buildChip(attachment: Attachment): JComponent {
        val chip = RoundedPanel(JBColor(0xE0E2E6, 0x4E5157), borderColor = null)
        chip.layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
        chip.border = JBUI.Borders.empty(2, 6, 2, 4)
        val label =
            JBLabel(attachment.label).apply {
                icon = fileIconFor(attachment.item.relativePath)
                iconTextGap = JBUI.scale(4)
                toolTipText = attachment.item.relativePath
                font = JBFont.label()
            }

        // Middle-click anywhere on the chip removes it, like middle-clicking an editor tab.
        val middleClickRemove =
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON2) service.remove(attachment.id)
                }
            }
        chip.addMouseListener(middleClickRemove)
        label.addMouseListener(middleClickRemove)

        chip.add(label)
        chip.add(IconActionButton(AllIcons.Actions.Close, "Remove") { service.remove(attachment.id) })
        return chip
    }

    /** A discreet text affordance, shown after the chips, that drops the whole context at once. */
    private fun buildClearAllButton(): JComponent =
        JBLabel("Clear all").apply {
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            font = JBFont.label()
            toolTipText = "Remove all attached context"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(2, 6)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = service.clear()
                },
            )
        }

    /** The IDE's file-type icon for a path, so a chip reads at a glance like an editor tab. */
    private fun fileIconFor(relativePath: String): Icon {
        val name = relativePath.substringAfterLast('/')
        return FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: EmptyIcon.ICON_16
    }

    private fun openAttachPicker() {
        val recents =
            EditorHistoryManager
                .getInstance(project)
                .fileList
                .reversed()
                .asSequence()
                .filter { it.isValid && !it.isDirectory }
                .distinct()
                .take(12)
                .map { PickerItem.File(it) }
                .toList()

        val items: List<PickerItem> = listOf(PickerItem.Search) + recents
        JBPopupFactory
            .getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Add context")
            .setRenderer(PickerRenderer())
            .setItemChosenCallback { chosen ->
                when (chosen) {
                    PickerItem.Search -> openSearchByName()
                    is PickerItem.File -> attach(arrayOf(chosen.file))
                }
            }.createPopup()
            .showUnderneathOf(attachButton)
    }

    private fun openSearchByName() {
        val model = GotoFileModel(project)
        val popup = ChooseByNamePopup.createPopup(project, model, DefaultChooseByNameItemProvider(null))
        popup.invoke(
            object : ChooseByNamePopupComponent.Callback() {
                override fun elementChosen(element: Any?) {
                    val file = (element as? PsiFileSystemItem)?.virtualFile
                    if (file != null && !file.isDirectory) attach(arrayOf(file))
                }
            },
            ModalityState.current(),
            false,
        )
    }

    private fun attach(files: Array<VirtualFile>) {
        // Attachments.collect reads file contents, forbidden on the EDT (it throws
        // "This method is forbidden on EDT..."). Collect under a background read action, then
        // publish to the service and notify back on the UI thread.
        ReadAction
            .nonBlocking<CollectResult> { Attachments.collect(project, files) }
            .expireWith(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
                service.add(result.items)
                AttachmentNotifications.notifySkipped(project, result.skipped)
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Accepts file drops onto the composer through the IDE drag and drop manager, which (unlike
     * a bare Swing [javax.swing.TransferHandler]) also covers drags started inside the IDE such
     * as the Project view. Dropped files are filtered and attached like any other source.
     */
    private fun installFileDrop(target: JComponent) {
        DnDSupport
            .createBuilder(target)
            .setTargetChecker { event ->
                event.isDropPossible = FileCopyPasteUtil.getFileListFromAttachedObject(event.attachedObject).isNotEmpty()
                true
            }.setDropHandler { event ->
                val files =
                    FileCopyPasteUtil
                        .getFileListFromAttachedObject(event.attachedObject)
                        .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
                        .toTypedArray()
                if (files.isNotEmpty()) attach(files)
            }.setDisposableParent(this)
            .install()
    }

    override fun dispose() = Unit

    // --- Conversation rows --------------------------------------------------------------

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

    /**
     * Assistant reply: full width, role header with an expand toggle and a Copy action, then the
     * meta-prompt. The body is collapsed by default (see [collapsedPreview]) so the eye lands on
     * the intro, a sample file block and the request in full; the chevron unfolds the whole prompt.
     */
    private fun assistantRow(metaPrompt: String): JComponent {
        val collapsed = collapsedPreview(metaPrompt)
        val body = bodyArea(collapsed, JBColor.foreground())

        val toggle =
            JLabel(AllIcons.General.ChevronDown).apply {
                toolTipText = "Expand all"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2)
            }
        var expanded = false
        toggle.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    expanded = !expanded
                    body.text = if (expanded) metaPrompt else collapsed
                    toggle.icon = if (expanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
                    toggle.toolTipText = if (expanded) "Collapse" else "Expand all"
                    body.revalidate()
                    conversation.revalidate()
                    conversation.repaint()
                }
            },
        )

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
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(toggle)
                add(
                    JButton("Copy").apply {
                        isOpaque = false
                        addActionListener { controller.copyToClipboard(metaPrompt) }
                    },
                )
            },
            BorderLayout.EAST,
        )

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

    /**
     * Folds the assembled prompt into a three-beat preview: the opening instruction line, an
     * ellipsis, the first injected `<file>` header (trimmed), another ellipsis, then the user
     * request section in full. Mirrors the prompt's begin / middle / end shape without making
     * the reader scroll through the whole context. Falls back gracefully when a beat is absent
     * (for example no files attached).
     */
    private fun collapsedPreview(metaPrompt: String): String {
        val lines = metaPrompt.lines()
        val builder = StringBuilder()
        builder.append(lines.firstOrNull().orEmpty())
        builder.append("\n(...)")

        val fileLine = lines.firstOrNull { it.startsWith("<file ") }
        if (fileLine != null) {
            builder.append('\n').append(ellipsizeRight(fileLine))
            builder.append("\n(...)")
        }

        // Match the LAST marker: an attached file (a template, this spec) can carry a literal
        // "### USER REQUEST" in its content, and the real request section is always the prompt's
        // trailing block. Matching the first marker would fold the preview from inside a file.
        val requestIndex = lines.indexOfLast { it.trim() == REQUEST_MARKER }
        if (requestIndex >= 0) {
            builder.append('\n')
            builder.append(lines.subList(requestIndex, lines.size).joinToString("\n"))
        }
        return builder.toString()
    }

    private fun ellipsizeRight(
        text: String,
        max: Int = 42,
    ): String = if (text.length <= max) text else text.take(max) + "..."

    private fun userBubbleBackground(): Color = JBColor(0x3574F0, 0x3574F0)

    private fun userBubbleForeground(): Color = JBColor(0xFFFFFF, 0xFFFFFF)

    /** An entry of the `+` picker: either the search affordance or a recent file. */
    private sealed interface PickerItem {
        object Search : PickerItem

        data class File(
            val file: VirtualFile,
        ) : PickerItem
    }

    /**
     * Renders picker rows like the IDE's own Go to File popup: a file-type icon, the file name
     * in the foreground color, then its folder path grayed out (and shortened from the left so
     * the name stays readable). The search row leads with a magnifier.
     */
    private inner class PickerRenderer : ColoredListCellRenderer<PickerItem>() {
        override fun customizeCellRenderer(
            list: JList<out PickerItem>,
            value: PickerItem,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            when (value) {
                PickerItem.Search -> {
                    icon = AllIcons.Actions.Search
                    append("Search by name...")
                }

                is PickerItem.File -> {
                    val file = value.file
                    icon = IconUtil.getIcon(file, 0, project)
                    append(file.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val parent = Attachments.relativePath(project, file).substringBeforeLast('/', "")
                    if (parent.isNotEmpty()) {
                        append("  " + shortenLeft(parent), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            }
        }

        private fun shortenLeft(
            path: String,
            max: Int = 48,
        ): String = if (path.length <= max) path else "..." + path.takeLast(max - 3)
    }
}

/**
 * A panel that paints a rounded background and, optionally, a rounded border that brightens
 * to the accent color while [focused]. Used for the composer, the user bubble and chips.
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

/**
 * A borderless, icon-sized clickable used for the composer `+` action and chip removal. Based
 * on a label rather than a JButton so it stays tight to the icon: the Darcula button UI forces
 * a wide minimum width that would otherwise blow up the chips.
 */
private class IconActionButton(
    icon: Icon,
    tooltip: String,
    private val onClick: () -> Unit,
) : JLabel(icon) {
    init {
        toolTipText = tooltip
        border = JBUI.Borders.empty(2)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            },
        )
    }
}

/**
 * Chip container that fills the scroll viewport's width (so [WrapLayout] wraps at the real
 * width) while keeping its own taller height, which lets the enclosing scroll pane reveal a
 * vertical scrollbar once the chips exceed the capped viewport height.
 */
private class ChipsPanel(
    layout: LayoutManager,
) : JPanel(layout),
    Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(48)

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/**
 * A [FlowLayout] that wraps to additional rows and reports the height those rows need, so a
 * row of chips placed in a fixed-width container lays out fully instead of being clipped.
 */
private class WrapLayout(
    align: Int,
    hgap: Int,
    vgap: Int,
) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: java.awt.Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: java.awt.Container): Dimension = layoutSize(target, false).also { it.width -= hgap + 1 }

    private fun layoutSize(
        target: java.awt.Container,
        preferred: Boolean,
    ): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = (if (target.width > 0) target.width else Int.MAX_VALUE)
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                rowWidth += d.width + hgap
                rowHeight = maxOf(rowHeight, d.height)
            }
            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight

            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}
