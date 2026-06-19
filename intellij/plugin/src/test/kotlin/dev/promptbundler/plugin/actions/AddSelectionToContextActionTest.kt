package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.plugin.context.ContextBundleService

class AddSelectionToContextActionTest : BasePlatformTestCase() {
    private val action = AddSelectionToContextAction()
    private lateinit var service: ContextBundleService

    override fun setUp() {
        super.setUp()
        service = ContextBundleService.getInstance(project)
        service.clear()
    }

    private fun event(): AnActionEvent {
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
                .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    fun testSelectionBecomesSnippetWithLineRange() {
        myFixture.configureByText("App.kt", "line1\nline2\nline3\n")
        // Select the whole second line (offsets 6..11).
        myFixture.editor.selectionModel.setSelection(6, 11)

        action.actionPerformed(event())

        val item = service.items.single()
        assertEquals("App.kt", item.relativePath)
        assertEquals("line2", item.content)
        assertEquals(2..2, item.lines)
    }

    fun testActionDisabledWithoutSelection() {
        myFixture.configureByText("App.kt", "line1\nline2\n")

        val e = event()
        action.update(e)

        assertFalse("action must be disabled when nothing is selected", e.presentation.isEnabledAndVisible)
    }
}
