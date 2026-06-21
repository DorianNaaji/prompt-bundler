package dev.promptbundler.plugin.settings

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.ide.util.PropertiesComponent
import dev.promptbundler.engine.DefaultTemplate

data class PromptTemplate(val id: String, val name: String, val content: String)

object TemplateStore {
    private const val TEMPLATES_KEY = "dev.promptbundler.templates"
    private const val ACTIVE_ID_KEY = "dev.promptbundler.activeTemplateId"
    private val gson = Gson()
    private val listType = object : TypeToken<List<PromptTemplate>>() {}.type

    val templates: List<PromptTemplate>
        get() {
            val json = PropertiesComponent.getInstance().getValue(TEMPLATES_KEY) ?: return emptyList()
            return try {
                gson.fromJson<List<PromptTemplate>>(json, listType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    fun save(templates: List<PromptTemplate>) {
        PropertiesComponent.getInstance().setValue(TEMPLATES_KEY, gson.toJson(templates))
    }

    var activeTemplateId: String?
        get() = PropertiesComponent.getInstance().getValue(ACTIVE_ID_KEY)
        set(value) {
            if (value == null) {
                PropertiesComponent.getInstance().unsetValue(ACTIVE_ID_KEY)
            } else {
                PropertiesComponent.getInstance().setValue(ACTIVE_ID_KEY, value)
            }
        }

    val activeTemplate: String
        get() {
            val id = activeTemplateId ?: return DefaultTemplate.text
            return templates.firstOrNull { it.id == id }?.content ?: DefaultTemplate.text
        }
}
