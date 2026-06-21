package dev.promptbundler.plugin.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.TextComponentEmptyText
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.promptbundler.plugin.context.Attachment
import dev.promptbundler.plugin.context.AttachmentNotifications
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.CollectResult
import dev.promptbundler.plugin.context.ContextBundleListener
import dev.promptbundler.plugin.context.ContextBundleService
import dev.promptbundler.plugin.session.PersistedSession
import dev.promptbundler.plugin.session.SessionHistoryListener
import dev.promptbundler.plugin.session.SessionHistoryService
import dev.promptbundler.plugin.support.DonationReminder
import dev.promptbundler.plugin.support.PromptBundlerIcons
import dev.promptbundler.plugin.support.PropertiesDonationStore
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
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
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

private const val COMPOSER_PLACEHOLDER =
    "Write your prompt and add context (files, snippets, console outputs...) for your web agent"

// Header that opens the user request section of the assembled prompt. The collapsed reply
// keeps the request text (the lines after this header) visible without the header itself.
private const val REQUEST_MARKER = "### USER REQUEST"
private const val ARC = 16

// Tallest the chips area may get before it scrolls instead of growing the composer (~5 rows).
private const val CHIPS_MAX_HEIGHT = 140

// Floor for the chips area so at least one row stays visible (and scrollable) even when squeezed.
private const val CHIP_ROW_HEIGHT = 30

// Composer pane vertical chrome: RoundedPanel insets (top 10 + bottom 8) plus the two 6px vgaps
// between the chips, the input and the controls.
private const val COMPOSER_CHROME_HEIGHT = 30
private val ACCENT = JBColor.namedColor("Component.focusedBorderColor", JBColor(0x3574F0, 0x548AF7))

// Card names for the two views (sessions list and the active conversation) and the title shown
// for a fresh, not-yet-persisted session.
private const val CARD_CHAT = "chat"
private const val CARD_LIST = "list"
private const val NEW_SESSION_TITLE = "New session"

// The conversation and the composer are the two halves of a draggable OnePixelSplitter, so the
// resize is the platform's own divider drag (reliable, and the grab zone sits exactly on the
// visible boundary) rather than a hand-rolled grip. We persist the TOP (conversation) fraction;
// the composer is the bottom half and takes the rest. The composer also auto-grows with its typed
// content up to COMPOSER_MAX_FRACTION of the panel, after which its input scrolls. The two minimum
// heights are honored by the splitter so neither half can be crushed away.
private const val COMPOSER_PROPORTION_KEY = "promptbundler.composer.topProportion.v3"
private const val COMPOSER_DEFAULT_TOP_PROPORTION = 0.8f
private const val COMPOSER_MAX_FRACTION = 0.72f
private const val COMPOSER_MIN_HEIGHT = 96
private const val TOP_MIN_HEIGHT = 72

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

    // Lazy on purpose: the first access loads the persisted state, which touches the project store
    // (and a path-macro contributor that may block). That first touch is forced off the EDT in
    // init; afterwards every access is on an already-initialized service and is cheap.
    private val sessionService by lazy { SessionHistoryService.getInstance(project) }

    private val donationReminder = DonationReminder(PropertiesDonationStore())

    // Id of the session currently shown, or null for a fresh draft that is not persisted until
    // its first turn is sent (so no empty sessions are ever stored).
    private var currentSessionId: String? = null

    private val cards = JPanel(CardLayout())
    private val sessionList = JPanel(VerticalLayout(JBUI.scale(6)))
    private val sessionTitleLabel = JBLabel(NEW_SESSION_TITLE)
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
                var cap = JBUI.scale(CHIPS_MAX_HEIGHT)

                // Keep the chips to at most half the composer's inner height so the question area
                // always keeps the other half (the 50/50 the user asked for): extra attachments
                // scroll within their half instead of squeezing the input. Never below one row.
                val boxHeight = composerBox.height
                if (boxHeight > 0) {
                    val inner = boxHeight - JBUI.scale(COMPOSER_CHROME_HEIGHT) - controlsRow.preferredSize.height
                    val half = inner / 2
                    if (half in 1 until cap) cap = half
                }
                cap = cap.coerceAtLeast(JBUI.scale(CHIP_ROW_HEIGHT))

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

    // The attach + send buttons row, kept as a field so the chips cap can reserve room for it
    // (and for the input) when sizing itself.
    private val controlsRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))

    // The conversation/list (top) and the composer (bottom) are the two halves of this splitter.
    // We persist the top fraction; dragging the divider resizes the composer, and [adjustComposerHeight]
    // also auto-grows it with typed content. The composer never rests below 1 - manualTopProportion.
    private var manualTopProportion =
        PropertiesComponent.getInstance().getFloat(COMPOSER_PROPORTION_KEY, COMPOSER_DEFAULT_TOP_PROPORTION)

    // Guards the splitter's proportion listener while we set the proportion ourselves (auto-grow),
    // so a programmatic change is not mistaken for a user drag and persisted as the resting size.
    private var adjustingSplitter = false

    private lateinit var splitter: OnePixelSplitter

    // The bottom half of the splitter: the composer (chips + scrollable input + controls).
    private lateinit var composerPane: JComponent

    init {
        conversation.isOpaque = false
        conversation.border = JBUI.Borders.empty(12, 12, 4, 12)
        conversationScroll.border = JBUI.Borders.empty()
        conversationScroll.viewport.isOpaque = false
        conversationScroll.isOpaque = false

        setContent(buildRoot())

        val connection = project.messageBus.connect(this)
        connection.subscribe(ContextBundleService.TOPIC, ContextBundleListener { rebuildChips() })
        connection.subscribe(SessionHistoryService.TOPIC, SessionHistoryListener { rebuildSessionList() })

        rebuildChips()
        invokeLater { adjustComposerHeight() }

        // The sessions list is the home view: land there, with the composer ready to start a
        // brand new session on send (currentSessionId stays null until the first turn is sent).
        currentSessionId = null
        sessionTitleLabel.text = NEW_SESSION_TITLE
        (cards.layout as CardLayout).show(cards, CARD_LIST)

        // Warm the session service off the EDT: its first access loads the persisted state, which
        // touches the project store and may block (e.g. a Maven path-macro contributor). Once
        // warmed, populate the list back on the EDT.
        AppExecutorUtil.getAppExecutorService().execute {
            sessionService // first touch off EDT triggers state load
            invokeLater { rebuildSessionList() }
        }
    }

    // --- Views ---------------------------------------------------------------------------

    /**
     * Root layout: a draggable [OnePixelSplitter] with the two views (sessions list and conversation)
     * on top and the single shared composer on the bottom. The composer is shared so it is present on
     * both views (on the sessions home it starts a new session). The divider is the resize handle -
     * it sits exactly on the boundary, so dragging it grows or shrinks the composer reliably; the
     * composer also auto-grows with typed content (see [adjustComposerHeight]).
     */
    private fun buildRoot(): JComponent {
        cards.add(buildChatCard(), CARD_CHAT)
        cards.add(buildListCard(), CARD_LIST)
        cards.minimumSize = Dimension(0, JBUI.scale(TOP_MIN_HEIGHT))

        composerPane = buildComposer().apply { minimumSize = Dimension(0, JBUI.scale(COMPOSER_MIN_HEIGHT)) }

        splitter =
            OnePixelSplitter(true, manualTopProportion.coerceIn(1f - COMPOSER_MAX_FRACTION, 0.95f)).apply {
                firstComponent = cards
                secondComponent = composerPane
                setHonorComponentsMinimumSize(true)
                // The user drags the divider to set the composer's resting size; remember it (and
                // skip changes we triggered ourselves while auto-growing - see adjustingSplitter).
                addPropertyChangeListener(Splitter.PROP_PROPORTION) {
                    if (adjustingSplitter) return@addPropertyChangeListener
                    manualTopProportion = proportion
                    PropertiesComponent.getInstance().setValue(
                        COMPOSER_PROPORTION_KEY,
                        proportion,
                        COMPOSER_DEFAULT_TOP_PROPORTION,
                    )
                }
                // Auto-grow needs the real divider span, which only exists after the first layout
                // and changes when the tool window is resized.
                addComponentListener(
                    object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent?) = adjustComposerHeight()
                    },
                )
            }
        return splitter
    }

    /**
     * Auto-grows the composer to fit its content (chips up to half, plus the typed text), bounded
     * below by the user's resting size (their last divider drag) and above by [COMPOSER_MAX_FRACTION]
     * of the panel; past that the input scrolls. Runs on content changes, chip changes and resizes.
     * A short-circuit on a tiny delta avoids fighting the divider and any feedback loop.
     */
    private fun adjustComposerHeight() {
        if (!::splitter.isInitialized) return
        val total = splitter.height
        if (total <= 0) return

        val baseComposerFraction = (1f - manualTopProportion).coerceIn(0f, COMPOSER_MAX_FRACTION)
        val neededFraction = desiredComposerHeight().toFloat() / total
        val composerFraction = neededFraction.coerceIn(baseComposerFraction, COMPOSER_MAX_FRACTION)

        val topProportion = 1f - composerFraction
        if (kotlin.math.abs(topProportion - splitter.proportion) < 0.005f) return
        adjustingSplitter = true
        try {
            splitter.proportion = topProportion
        } finally {
            adjustingSplitter = false
        }
    }

    /**
     * Pixel height the composer would like: its chips (their natural height, capped at
     * [CHIPS_MAX_HEIGHT]), the full typed text, the controls row and all the surrounding insets and
     * gaps. Independent of the composer's current height, so [adjustComposerHeight] does not chase
     * its own tail.
     */
    private fun desiredComposerHeight(): Int {
        val chips = if (chipsScroll.isVisible) chipsRow.preferredSize.height.coerceAtMost(JBUI.scale(CHIPS_MAX_HEIGHT)) else 0
        val input = composer.preferredSize.height
        val controls = controlsRow.preferredSize.height
        val vgaps = JBUI.scale(6) * 2 // the two vgaps of composerBox's BorderLayout(0, 6)
        val boxInsets = JBUI.scale(10) + JBUI.scale(8) // composerBox border empty(10, 12, 8, 10)
        val outerInsets = JBUI.scale(8) + JBUI.scale(12) // composer panel border empty(8, 12, 12, 12)
        return chips + input + controls + vgaps + boxInsets + outerInsets
    }

    private fun buildChatCard(): JComponent =
        JPanel(BorderLayout()).apply {
            add(chatTopBar(), BorderLayout.NORTH)
            add(conversationScroll, BorderLayout.CENTER)
        }

    /** Top bar of the conversation: a back arrow to the sessions list, the title, and a new-session +. */
    private fun chatTopBar(): JComponent {
        sessionTitleLabel.font = JBFont.label().asBold()

        val left =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(IconActionButton(AllIcons.Actions.Back, "Back to sessions") { showList() })
                add(sessionTitleLabel)
            }
        val right =
            JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                isOpaque = false
                add(donateButton())
                add(Box.createHorizontalStrut(JBUI.scale(4)))
                add(helpButton())
                add(Box.createHorizontalStrut(JBUI.scale(6)))
                add(IconActionButton(AllIcons.General.Add, "New session") { newSession() })
            }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 8, 0, 8)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun buildListCard(): JComponent {
        sessionList.isOpaque = false
        sessionList.border = JBUI.Borders.empty(8)

        val scroll =
            JBScrollPane(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(sessionList, BorderLayout.NORTH)
                },
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }

        val top =
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 8, 0, 8)
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                        isOpaque = false
                        add(JBLabel("Sessions").apply { font = JBFont.label().asBold() })
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
                        isOpaque = false
                        add(donateButton())
                        add(helpButton())
                        add(Box.createHorizontalStrut(JBUI.scale(6)))
                        add(IconActionButton(AllIcons.General.Add, "New session") { newSession() })
                    },
                    BorderLayout.EAST,
                )
            }

        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun showChat() {
        (cards.layout as CardLayout).show(cards, CARD_CHAT)
    }

    private fun showList() {
        rebuildSessionList()
        (cards.layout as CardLayout).show(cards, CARD_LIST)
    }

    // --- Sessions ------------------------------------------------------------------------

    /** Clears the view to a fresh draft session; it is persisted only once a turn is sent. */
    private fun newSession() {
        currentSessionId = null
        sessionTitleLabel.text = NEW_SESSION_TITLE
        clearConversation()
        showChat()
        composer.requestFocusInWindow()
    }

    private fun openSession(id: String) {
        val session = sessionService.session(id) ?: return
        currentSessionId = id
        sessionTitleLabel.text = session.title
        clearConversation()
        session.turns.forEach { turn ->
            conversation.add(userRow(turn.userRequest))
            conversation.add(assistantRow(turn.metaPrompt))
        }
        conversation.revalidate()
        conversation.repaint()
        invokeLater { conversationScroll.verticalScrollBar.value = 0 }
        showChat()
    }

    private fun deleteSession(id: String) {
        val wasCurrent = id == currentSessionId
        sessionService.deleteSession(id)
        if (wasCurrent) {
            currentSessionId = null
            sessionTitleLabel.text = NEW_SESSION_TITLE
            clearConversation()
        }
    }

    private fun clearConversation() {
        conversation.removeAll()
        conversation.revalidate()
        conversation.repaint()
    }

    private fun rebuildSessionList() {
        invokeLater {
            sessionList.removeAll()
            val sessions = sessionService.sessions()
            if (sessions.isEmpty()) {
                sessionList.add(
                    JBLabel("No sessions yet").apply {
                        foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                        border = JBUI.Borders.empty(8, 4)
                    },
                )
            } else {
                sessions.forEach { sessionList.add(sessionRow(it)) }
            }
            sessionList.revalidate()
            sessionList.repaint()
        }
    }

    /** One row of the sessions list: title over a relative timestamp, with a delete action. */
    private fun sessionRow(session: PersistedSession): JComponent {
        val texts =
            JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = false
                add(JBLabel(session.title).apply { font = JBFont.label() })
                add(
                    JBLabel(DateFormatUtil.formatPrettyDateTime(session.updatedAt)).apply {
                        foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                        font = JBFont.small()
                    },
                )
            }

        val row =
            RoundedPanel(JBColor(0xF2F3F5, 0x3C3F41), borderColor = null).apply {
                layout = BorderLayout(JBUI.scale(8), 0)
                border = JBUI.Borders.empty(8, 10)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(texts, BorderLayout.CENTER)
                add(
                    IconActionButton(AllIcons.General.Remove, "Delete session") { deleteSession(session.id) },
                    BorderLayout.EAST,
                )
            }

        val open =
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) openSession(session.id)
                }
            }
        // Labels swallow clicks, so the whole row opens only if every child also forwards.
        listOf(row, texts).forEach { it.addMouseListener(open) }
        texts.components.forEach { it.addMouseListener(open) }
        return row
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
                override fun insertUpdate(e: DocumentEvent) = onComposerChanged()

                override fun removeUpdate(e: DocumentEvent) = onComposerChanged()

                override fun changedUpdate(e: DocumentEvent) = onComposerChanged()
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

        controlsRow.isOpaque = false
        controlsRow.add(attachButton)
        controlsRow.add(sendButton)

        // Scroll the input rather than clip it: once the typed text outgrows the (auto-grown)
        // composer the user can still scroll through it instead of losing track of earlier lines.
        val inputScroll =
            JBScrollPane(
                composer,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            ).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }

        composerBox.layout = BorderLayout(0, JBUI.scale(6))
        composerBox.border = JBUI.Borders.empty(10, 12, 8, 10)
        composerBox.add(chipsScroll, BorderLayout.NORTH)
        composerBox.add(inputScroll, BorderLayout.CENTER)
        composerBox.add(controlsRow, BorderLayout.SOUTH)

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 12, 12)
            add(composerBox, BorderLayout.CENTER)
        }
    }

    private fun refreshSendState() {
        sendButton.isEnabled = composer.text.isNotBlank()
    }

    /** Typed-text change: update the send button and let the composer auto-grow to fit the content. */
    private fun onComposerChanged() {
        refreshSendState()
        adjustComposerHeight()
    }

    /**
     * Sends the current composer content: appends the question, asks the controller for the
     * meta-prompt (which folds in the attached context) and appends the assistant reply. The
     * attached context is then cleared so the next question starts from a clean slate. No-op
     * when blank.
     */
    fun send() {
        val query = composer.text
        if (query.isBlank()) return

        val userComponent = userRow(query)
        addRow(userComponent)
        val metaPrompt = controller.buildMetaPrompt(query)
        addRow(assistantRow(metaPrompt))

        // Persist the turn. The first turn of a draft materializes the session (and its title);
        // later turns extend the open one.
        val id = currentSessionId
        if (id == null) {
            val created = sessionService.createSession(query, metaPrompt)
            currentSessionId = created.id
            sessionTitleLabel.text = created.title
        } else {
            sessionService.appendTurn(id, query, metaPrompt)
        }

        composer.text = ""
        // Clear the attached context so the next question starts fresh (the turn already captured it).
        service.clear()
        // Sending from the sessions home creates the session, so reveal the conversation view.
        showChat()
        composer.requestFocusInWindow()
        scrollRowToTop(userComponent)

        if (donationReminder.onPromptSent()) showDonationDialog()
    }

    private fun showDonationDialog() {
        // "Yes" sits first so it is the default-focused option; the returned index maps back here.
        val options = arrayOf("Yes ❤", "Maybe Later", "Don't remind me")
        val choice =
            Messages.showDialog(
                project,
                "Support the project and buy me a coffee.",
                "Do you like Prompt Bundler?",
                options,
                0,
                Messages.getQuestionIcon(),
            )
        when (choice) {
            0 -> {
                donationReminder.onYes()
                BrowserUtil.browse(DonationReminder.DONATION_URL)
            }
            1 -> donationReminder.onMaybeLater()
            2 -> donationReminder.onDontRemind()
            // Closing the dialog (Esc) returns -1: treat it as "Maybe Later", re-ask later.
            else -> donationReminder.onMaybeLater()
        }
    }

    private fun addRow(row: JComponent) {
        conversation.add(row)
        conversation.revalidate()
        conversation.repaint()
    }

    /**
     * Pins the top of [row] to the top of the conversation viewport. On send this puts the freshly
     * sent question at the top of the panel with the assistant header (and its Copy action) right
     * below it, instead of jumping to the very bottom of a tall reply, which previously left the
     * Copy button out of view and sometimes landed mid-response. Clamps to the maximum scroll
     * offset so a short reply still shows the whole exchange.
     */
    private fun scrollRowToTop(row: JComponent) {
        invokeLater {
            val bar = conversationScroll.verticalScrollBar
            val maxValue = (conversation.height - conversationScroll.viewport.height).coerceAtLeast(0)
            bar.value = row.y.coerceIn(0, maxValue)
        }
    }

    // --- Attached context ---------------------------------------------------------------

    /**
     * An always-visible (i) affordance in the top bars. Clicking it opens a small popup that spells
     * out every way to attach context, since most paths (editor/project/console right-click) live
     * outside this tool window and are easy to miss. Anchored on the button itself.
     */
    private fun donateButton(): JComponent =
        IconActionButton(PromptBundlerIcons.Donate, "Support Prompt Bundler - buy me a coffee") {
            BrowserUtil.browse(DonationReminder.DONATION_URL)
        }

    private fun helpButton(): JComponent {
        val anchor = arrayOfNulls<JComponent>(1)
        val button =
            IconActionButton(AllIcons.General.Information, "How to attach context") {
                anchor[0]?.let { showAttachHelp(it) }
            }
        anchor[0] = button
        return button
    }

    private fun showAttachHelp(anchor: JComponent) {
        val html =
            """
            <html><body style='width:300px;'>
            <b>Attach context, 4 ways</b>
            <ul style='margin:4px 0 0 14px; padding:0;'>
            <li><b>Editor</b>: select code or text, right-click, <i>Add to Prompt Bundler Context</i>.</li>
            <li><b>Project view</b>: right-click file(s) or whole folder(s), <i>Add to Prompt Bundler Context</i>.</li>
            <li><b>Console</b>: right-click a run/console output, <i>Add Console Output to Prompt Bundler</i>.</li>
            <li><b>Drag and drop</b>: drop files or whole folders straight onto the composer.</li>
            <li><b>Picker</b>: use <b>+</b> in the composer to pick a recent file or search by name.</li>
            </ul>
            </body></html>
            """.trimIndent()
        val content =
            JBLabel(html).apply {
                border = JBUI.Borders.empty(10, 12)
                font = JBFont.label()
            }
        JBPopupFactory
            .getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
            .showUnderneathOf(anchor)
    }

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

            // Chip wrapping needs the row's real width, which is only assigned by the first layout
            // pass. On the very first drop that width is still 0, so WrapLayout reports a single
            // (clipped) line and the capped scroll area collapses to one row. Recompute once the
            // width is known so many files wrap to several rows and then scroll, every time.
            invokeLater {
                chipsRow.revalidate()
                chipsScroll.revalidate()
                composerBox.revalidate()
                composerBox.repaint()
                adjustComposerHeight()
            }
        }
    }

    private fun buildChip(attachment: Attachment): JComponent {
        val chip = RoundedPanel(JBColor(0xE0E2E6, 0x4E5157), borderColor = null)
        chip.layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)
        chip.border = JBUI.Borders.empty(2, 6, 2, 4)
        val path = attachment.item.relativePath
        val label =
            JBLabel(attachment.label).apply {
                // Path-less snippets (console output) read like an editor tab with a console icon.
                icon = path?.let { fileIconFor(it) } ?: AllIcons.Debugger.Console
                iconTextGap = JBUI.scale(4)
                toolTipText = path ?: attachment.label
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
     * Assistant reply: full width, role header with a collapse toggle and a Copy action, then the
     * meta-prompt. The body is expanded by default so a developer sees exactly what will be copied
     * at a glance (anything less reads as hiding what you send); the chevron folds it into a short
     * three-beat preview (see [collapsedPreview]).
     */
    private fun assistantRow(metaPrompt: String): JComponent {
        val collapsed = collapsedPreview(metaPrompt)
        val body = bodyArea(metaPrompt, JBColor.foreground())

        val toggle =
            JLabel(AllIcons.General.ChevronUp).apply {
                toolTipText = "Collapse"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2)
            }
        var expanded = true
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
                add(CopyButton { controller.copyToClipboard(metaPrompt) })
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
     * request text (the lines after the marker, without the header itself). Mirrors the prompt's
     * begin / middle / end shape without making the reader scroll through the whole context. Falls
     * back gracefully when a beat is absent (for example no files attached).
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
        if (requestIndex in 0 until lines.lastIndex) {
            builder.append('\n')
            builder.append(lines.subList(requestIndex + 1, lines.size).joinToString("\n"))
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
 * The reply's Copy action. On click it copies, flips to "Copied" in a confirmation green, holds
 * briefly, then fades that green back to the normal label color and restores the label, so the
 * user gets an unmistakable yet quiet acknowledgement of what just landed on the clipboard.
 *
 * Both timers run on the EDT (javax.swing.Timer), so the text and color mutations are safe. A
 * fresh click restarts the cycle: any in-flight timers are stopped first.
 */
private class CopyButton(
    private val onCopy: () -> Unit,
) : JButton("Copy") {
    private val doneColor = JBColor(0x1A7F37, 0x57A65A)
    private var holdTimer: Timer? = null
    private var fadeTimer: Timer? = null

    init {
        isOpaque = false
        addActionListener { trigger() }
    }

    private fun trigger() {
        onCopy()
        holdTimer?.stop()
        fadeTimer?.stop()
        text = "Copied ✓"
        foreground = doneColor
        holdTimer =
            Timer(HOLD_MS) { startFade() }.apply {
                isRepeats = false
                start()
            }
    }

    private fun startFade() {
        var step = 0
        fadeTimer =
            Timer(FADE_TICK_MS) {
                step++
                val t = (step.toFloat() / FADE_STEPS).coerceAtMost(1f)
                foreground = blend(doneColor, JBColor.foreground(), t)
                if (step >= FADE_STEPS) {
                    fadeTimer?.stop()
                    text = "Copy"
                    // Restore the theme-reactive default so it re-resolves on a light/dark switch.
                    foreground = JBColor.foreground()
                }
            }.apply { start() }
    }

    private fun blend(
        from: Color,
        to: Color,
        t: Float,
    ): Color =
        Color(
            (from.red + (to.red - from.red) * t).toInt(),
            (from.green + (to.green - from.green) * t).toInt(),
            (from.blue + (to.blue - from.blue) * t).toInt(),
        )

    private companion object {
        const val HOLD_MS = 900
        const val FADE_TICK_MS = 16
        const val FADE_STEPS = 24
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
