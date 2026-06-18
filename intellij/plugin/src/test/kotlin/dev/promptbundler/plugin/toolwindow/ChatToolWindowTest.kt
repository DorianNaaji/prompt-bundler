package dev.promptbundler.plugin.toolwindow

import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ChatToolWindowTest : BasePlatformTestCase() {
    fun testToolWindowExtensionIsRegistered() {
        val ep =
            ToolWindowEP.EP_NAME.extensionList.singleOrNull { it.id == "Prompt Bundler" }

        assertNotNull("Prompt Bundler tool window extension should be registered", ep)
        assertEquals("right", ep!!.anchor)
        assertEquals(
            "dev.promptbundler.plugin.toolwindow.ChatToolWindowFactory",
            ep.factoryClass,
        )
    }
}
