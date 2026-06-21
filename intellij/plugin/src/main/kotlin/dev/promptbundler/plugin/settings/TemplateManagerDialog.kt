package dev.promptbundler.plugin.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.promptbundler.engine.DefaultTemplate
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val DEFAULT_ID = "__default__"

class TemplateManagerDialog(
    project: Project,
) : DialogWrapper(project) {
    private val listModel = DefaultListModel<PromptTemplate>()
    private val templateList = JBList(listModel)
    private val nameField = JBTextField()
    private val contentArea = JBTextArea()
    private val deleteBtn = JButton("Delete")
    private val setActiveBtn = JButton("Set as Active")
    private val resetBtn = JButton("Reset content to default")

    // null means built-in default is active
    private var activeId: String? = TemplateStore.activeTemplateId
    private var suppressSync = false

    init {
        title = "Prompt Templates"

        // Default is always first and never persisted
        listModel.addElement(PromptTemplate(DEFAULT_ID, "Default", DefaultTemplate.text))
        TemplateStore.templates.forEach { listModel.addElement(it) }

        templateList.cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    val t = value as? PromptTemplate
                    val isActive = if (t?.id == DEFAULT_ID) activeId == null else t?.id == activeId
                    text = if (isActive) "✓  ${t?.name}" else "    ${t?.name}"
                    return this
                }
            }

        templateList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        templateList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) loadSelected()
        }

        nameField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = syncName()

                override fun removeUpdate(e: DocumentEvent) = syncName()

                override fun changedUpdate(e: DocumentEvent) = syncName()
            },
        )

        contentArea.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = syncContent()

                override fun removeUpdate(e: DocumentEvent) = syncContent()

                override fun changedUpdate(e: DocumentEvent) = syncContent()
            },
        )

        nameField.isEnabled = false
        contentArea.isEnabled = false
        setActiveBtn.isEnabled = false
        deleteBtn.isEnabled = false
        resetBtn.isEnabled = false

        templateList.selectedIndex = 0
        init()
    }

    private fun isDefaultSelected(): Boolean = templateList.selectedValue?.id == DEFAULT_ID

    private fun loadSelected() {
        val t = templateList.selectedValue
        suppressSync = true
        nameField.text = t?.name ?: ""
        contentArea.text = t?.content ?: ""
        contentArea.caretPosition = 0
        suppressSync = false

        val isDefault = isDefaultSelected()
        nameField.isEditable = !isDefault
        nameField.isEnabled = t != null && !isDefault
        contentArea.isEditable = !isDefault
        contentArea.isEnabled = t != null
        deleteBtn.isEnabled = t != null && !isDefault
        setActiveBtn.isEnabled = t != null
        resetBtn.isEnabled = t != null && !isDefault
    }

    private fun syncName() {
        if (suppressSync || isDefaultSelected()) return
        val idx = templateList.selectedIndex.takeIf { it >= 0 } ?: return
        val old = listModel.getElementAt(idx)
        listModel.setElementAt(old.copy(name = nameField.text), idx)
        templateList.repaint()
    }

    private fun syncContent() {
        if (suppressSync || isDefaultSelected()) return
        val idx = templateList.selectedIndex.takeIf { it >= 0 } ?: return
        val old = listModel.getElementAt(idx)
        listModel.setElementAt(old.copy(content = contentArea.text), idx)
    }

    override fun createCenterPanel(): JComponent {
        val splitter =
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), buildRight()).apply {
                dividerLocation = 200
                isContinuousLayout = true
            }
        splitter.preferredSize = Dimension(760, 460)
        return splitter
    }

    private fun buildLeft(): JComponent {
        val addBtn =
            JButton("+ New").apply {
                addActionListener { addTemplate() }
            }
        deleteBtn.addActionListener { deleteSelected() }

        val btnRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                isOpaque = false
                add(addBtn)
                add(deleteBtn)
            }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 4, 4, 0)
            add(JBScrollPane(templateList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(btnRow, BorderLayout.SOUTH)
        }
    }

    private fun buildRight(): JComponent {
        val placeholders =
            JBLabel("Placeholders:  {query}   {files}   {tree}").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
            }

        setActiveBtn.addActionListener {
            activeId = templateList.selectedValue?.id?.takeUnless { it == DEFAULT_ID }
            templateList.repaint()
        }

        resetBtn.addActionListener {
            contentArea.text = DefaultTemplate.text
            contentArea.caretPosition = 0
        }

        val nameRow =
            JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)
                add(JBLabel("Name:"), BorderLayout.WEST)
                add(nameField, BorderLayout.CENTER)
            }

        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, JBUI.Fonts.smallFont().size)

        val bottomRow =
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                add(setActiveBtn)
                add(resetBtn)
                add(placeholders)
            }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 4, 4)
            add(nameRow, BorderLayout.NORTH)
            add(JBScrollPane(contentArea), BorderLayout.CENTER)
            add(bottomRow, BorderLayout.SOUTH)
        }
    }

    private fun addTemplate() {
        val t = PromptTemplate(UUID.randomUUID().toString(), "Custom ${listModel.size}", DefaultTemplate.text)
        listModel.addElement(t)
        templateList.selectedIndex = listModel.size - 1
        nameField.requestFocus()
        nameField.selectAll()
    }

    private fun deleteSelected() {
        val idx = templateList.selectedIndex.takeIf { it >= 0 } ?: return
        if (isDefaultSelected()) return
        val t = listModel.getElementAt(idx)
        listModel.removeElementAt(idx)
        if (t.id == activeId) activeId = null
        templateList.selectedIndex = minOf(idx, listModel.size - 1)
    }

    override fun doOKAction() {
        // Skip the Default sentinel (index 0), save only custom templates
        val customs = (1 until listModel.size).map { listModel.getElementAt(it) }
        TemplateStore.save(customs)
        TemplateStore.activeTemplateId = activeId
        super.doOKAction()
    }
}
