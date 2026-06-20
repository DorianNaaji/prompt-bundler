package dev.promptbundler.plugin.session

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

class SessionHistoryServiceTest : BasePlatformTestCase() {
    private lateinit var service: SessionHistoryService

    override fun setUp() {
        super.setUp()
        service = SessionHistoryService.getInstance(project)
        service.loadState(SessionHistoryState())
    }

    fun testCreateSessionStoresOneTurnAndDerivesTitle() {
        val session = service.createSession("Refactor the auth module", "META\nbody")

        assertEquals("Refactor the auth module", session.title)
        assertEquals(1, session.turns.size)
        assertEquals("Refactor the auth module", session.turns.single().userRequest)
        assertEquals("META\nbody", session.turns.single().metaPrompt)
        assertEquals(listOf(session.id), service.sessions().map { it.id })
    }

    fun testAppendTurnExtendsTheSession() {
        val session = service.createSession("first", "META-1")

        service.appendTurn(session.id, "second", "META-2")

        val turns = service.session(session.id)!!.turns
        assertEquals(2, turns.size)
        assertEquals(listOf("first", "second"), turns.map { it.userRequest })
    }

    fun testAppendTurnToUnknownSessionIsNoOp() {
        service.createSession("first", "META-1")

        service.appendTurn("does-not-exist", "ghost", "META")

        assertEquals(
            1,
            service
                .sessions()
                .single()
                .turns.size,
        )
    }

    fun testSessionsAreOrderedMostRecentFirst() {
        val older = service.createSession("older", "META")
        val newer = service.createSession("newer", "META")
        older.updatedAt = 1_000
        newer.updatedAt = 2_000

        assertEquals(listOf(newer.id, older.id), service.sessions().map { it.id })
    }

    fun testDeleteRemovesTheSession() {
        val a = service.createSession("a", "META")
        val b = service.createSession("b", "META")

        service.deleteSession(a.id)

        assertEquals(listOf(b.id), service.sessions().map { it.id })
    }

    fun testStateSurvivesXmlSerialization() {
        service.createSession("Improve the export pipeline", "META\nfull body\n")

        val element = XmlSerializer.serialize(service.state)
        val restored = XmlSerializer.deserialize(element, SessionHistoryState::class.java)

        assertEquals(1, restored.sessions.size)
        val session = restored.sessions.single()
        assertEquals("Improve the export pipeline", session.title)
        assertEquals(1, session.turns.size)
        assertEquals("Improve the export pipeline", session.turns.single().userRequest)
        assertEquals("META\nfull body\n", session.turns.single().metaPrompt)
    }

    fun testListenerFiresOnMutations() {
        var fired = 0
        project.messageBus
            .connect(testRootDisposable)
            .subscribe(SessionHistoryService.TOPIC, SessionHistoryListener { fired++ })

        val session = service.createSession("first", "META")
        service.appendTurn(session.id, "second", "META")
        service.deleteSession(session.id)

        assertEquals(3, fired)
    }
}
