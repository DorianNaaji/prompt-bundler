package dev.promptbundler.plugin.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID

/** A single exchange inside a session: the user request and the meta-prompt it produced. */
class PersistedTurn {
    var userRequest: String = ""
    var metaPrompt: String = ""
    var timestamp: Long = 0
}

/**
 * A persisted work session: an ordered list of turns plus a title derived once from the first
 * request. Only the rendered meta-prompt is stored (the live context is baked into it), so the
 * ephemeral attachments are never persisted.
 */
class PersistedSession {
    var id: String = ""
    var title: String = ""
    var createdAt: Long = 0
    var updatedAt: Long = 0

    @get:XCollection(style = XCollection.Style.v2)
    var turns: MutableList<PersistedTurn> = mutableListOf()
}

/** Serializable root of the session store. */
class SessionHistoryState {
    @get:XCollection(style = XCollection.Style.v2)
    var sessions: MutableList<PersistedSession> = mutableListOf()
}

/** Listener fired whenever the set of sessions or their turns changes. */
fun interface SessionHistoryListener {
    fun sessionsChanged()
}

/**
 * Project-level store of work sessions, persisted across IDE restarts. A session is created
 * lazily on the first turn (no empty sessions are stored) and can be reopened and continued.
 */
@Service(Service.Level.PROJECT)
@State(name = "PromptBundlerSessions", storages = [Storage("promptBundlerSessions.xml")])
class SessionHistoryService(
    private val project: Project,
) : PersistentStateComponent<SessionHistoryState> {
    private var state = SessionHistoryState()

    override fun getState(): SessionHistoryState = state

    override fun loadState(state: SessionHistoryState) {
        this.state = state
    }

    /** Sessions, most recently updated first. */
    fun sessions(): List<PersistedSession> = state.sessions.sortedByDescending { it.updatedAt }

    fun session(id: String): PersistedSession? = state.sessions.firstOrNull { it.id == id }

    /**
     * Creates a session from its first turn and returns it. The title is derived once from
     * [firstRequest] and never changes afterwards.
     */
    fun createSession(
        firstRequest: String,
        firstMetaPrompt: String,
    ): PersistedSession {
        val now = System.currentTimeMillis()
        val session =
            PersistedSession().apply {
                id = UUID.randomUUID().toString()
                title = SessionTitle.fromUserRequest(firstRequest)
                createdAt = now
                updatedAt = now
                turns.add(turn(firstRequest, firstMetaPrompt, now))
            }
        state.sessions.add(session)
        notifyChanged()
        return session
    }

    /** Appends a turn to an existing session and bumps its update time. No-op if id is unknown. */
    fun appendTurn(
        sessionId: String,
        request: String,
        metaPrompt: String,
    ) {
        val session = session(sessionId) ?: return
        val now = System.currentTimeMillis()
        session.turns.add(turn(request, metaPrompt, now))
        session.updatedAt = now
        notifyChanged()
    }

    fun deleteSession(sessionId: String) {
        if (state.sessions.removeAll { it.id == sessionId }) notifyChanged()
    }

    private fun turn(
        request: String,
        metaPrompt: String,
        at: Long,
    ): PersistedTurn =
        PersistedTurn().apply {
            userRequest = request
            this.metaPrompt = metaPrompt
            timestamp = at
        }

    private fun notifyChanged() {
        project.messageBus.syncPublisher(TOPIC).sessionsChanged()
    }

    companion object {
        val TOPIC: Topic<SessionHistoryListener> =
            Topic.create("PromptBundler session history", SessionHistoryListener::class.java)

        fun getInstance(project: Project): SessionHistoryService = project.service()
    }
}
