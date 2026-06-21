package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.plugin.context.Attachments
import dev.promptbundler.plugin.context.ContextBundleService

class AddConsoleOutputToContextActionTest : BasePlatformTestCase() {
    private val action = AddConsoleOutputToContextAction()
    private lateinit var service: ContextBundleService

    override fun setUp() {
        super.setUp()
        service = ContextBundleService.getInstance(project)
        service.clear()
    }

    // The fixture editor stands in for the console editor: the action only relies on EDITOR.
    private fun event(): AnActionEvent {
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, myFixture.editor)
                .build()
        return TestActionEvent.createTestEvent(action, context)
    }

    fun testWholeConsoleCapturedAsPathlessSnippet() {
        myFixture.configureByText("console.txt", "BUILD FAILED\nexpected 1 but was 2\n")

        action.actionPerformed(event())

        val item = service.items.single()
        assertNull("console snippet has no path", item.relativePath)
        assertEquals("Console output", item.label)
        assertEquals("BUILD FAILED\nexpected 1 but was 2\n", item.content)
    }

    fun testSelectionTakesPriorityOverWholeConsole() {
        myFixture.configureByText("console.txt", "noise\nexpected 1 but was 2\nmore noise\n")
        // Select the second line only (offsets 6..26).
        myFixture.editor.selectionModel.setSelection(6, 26)

        action.actionPerformed(event())

        assertEquals("expected 1 but was 2", service.items.single().content)
    }

    fun testHugeConsoleIsTruncatedWithMarker() {
        val huge = "x".repeat(Attachments.MAX_CONSOLE_CHARS + 5000)
        myFixture.configureByText("console.txt", huge)

        action.actionPerformed(event())

        val content = service.items.single().content
        assertTrue("oversized console must be truncated", content.length < huge.length)
        assertTrue("truncation marker must be explicit", content.startsWith("[... truncated 5000 characters ...]"))
    }

    fun testActionHiddenWithoutConsoleEditor() {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        val e = TestActionEvent.createTestEvent(action, context)

        action.update(e)

        assertFalse("action must be hidden when no console editor is present", e.presentation.isEnabledAndVisible)
    }
}
