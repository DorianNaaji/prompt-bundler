package dev.promptbundler.plugin.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.promptbundler.engine.ContextItem

class ContextBundleServiceTest : BasePlatformTestCase() {
    private lateinit var service: ContextBundleService

    override fun setUp() {
        super.setUp()
        service = ContextBundleService.getInstance(project)
        service.clear()
    }

    fun testAddReturnsCountAndExposesItems() {
        val added = service.add(listOf(ContextItem("a.txt", "A\n"), ContextItem("b.txt", "B\n")))

        assertEquals(2, added)
        assertEquals(listOf("a.txt", "b.txt"), service.items.map { it.relativePath })
    }

    fun testAddDeduplicatesByPathAndLineRange() {
        service.add(listOf(ContextItem("a.txt", "A\n")))
        val again = service.add(listOf(ContextItem("a.txt", "A changed\n")))
        // A snippet of the same file with a distinct range is a different attachment.
        val snippet = service.add(listOf(ContextItem("a.txt", "line\n", 3..5)))

        assertEquals("duplicate path is ignored", 0, again)
        assertEquals("distinct line range is a new item", 1, snippet)
        assertEquals(2, service.snapshot().size)
    }

    fun testRemoveDropsTheMatchingAttachment() {
        service.add(listOf(ContextItem("a.txt", "A\n"), ContextItem("b.txt", "B\n")))
        val first = service.snapshot().first()

        service.remove(first.id)

        assertEquals(listOf("b.txt"), service.items.map { it.relativePath })
    }

    fun testListenerFiresOnChange() {
        var fired = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(ContextBundleService.TOPIC, ContextBundleListener { fired++ })

        service.add(listOf(ContextItem("a.txt", "A\n")))
        service.add(emptyList()) // no change, must not fire
        service.remove(service.snapshot().first().id)

        assertEquals(2, fired)
    }

    fun testSnippetLabelCarriesLineRange() {
        service.add(listOf(ContextItem("src/App.kt", "x\n", 10..12)))

        assertEquals("App.kt:10-12", service.snapshot().single().label)
    }
}
