package dev.promptbundler.plugin.actions

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.plugin.context.ContextBundleService

class AddFilesToBundleActionTest : BasePlatformTestCase() {
    private val action = AddFilesToBundleAction()
    private lateinit var service: ContextBundleService

    override fun setUp() {
        super.setUp()
        service = ContextBundleService.getInstance(project)
        service.clear()
    }

    private fun perform(vararg files: VirtualFile) {
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
                .build()
        action.actionPerformed(TestActionEvent.createTestEvent(action, context))
        // actionPerformed is now async: wait for the background ReadAction to finish,
        // then flush the EDT queue to process the invokeLater dispatched by onSuccess.
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun testAddsSingleFile() {
        val file = myFixture.addFileToProject("src/App.kt", "fun main() {}\n").virtualFile

        perform(file)

        assertEquals(listOf("src/App.kt"), service.items.map { it.relativePath })
        assertEquals("fun main() {}\n", service.items.single().content)
    }

    fun testAddsFolderRecursively() {
        myFixture.addFileToProject("pkg/A.kt", "A\n")
        myFixture.addFileToProject("pkg/sub/B.kt", "B\n")
        val dir = myFixture.findFileInTempDir("pkg")

        perform(dir)

        assertEquals(setOf("pkg/A.kt", "pkg/sub/B.kt"), service.items.map { it.relativePath }.toSet())
    }

    fun testSkipsHiddenFilesUnderAFolder() {
        val text = myFixture.addFileToProject("bundle/notes.txt", "plain\n").virtualFile
        // A dotfile is filtered out by the eligibility rules.
        myFixture.addFileToProject("bundle/.secret", "hidden\n")

        perform(text.parent)

        assertEquals(listOf("bundle/notes.txt"), service.items.map { it.relativePath })
    }
}
