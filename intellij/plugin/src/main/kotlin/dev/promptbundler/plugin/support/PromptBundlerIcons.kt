package dev.promptbundler.plugin.support

import com.intellij.openapi.util.IconLoader

/** Bundled plugin icons. The dark variants are resolved automatically from the `_dark` suffix. */
object PromptBundlerIcons {
    /** Coffee cup for the "buy me a coffee" support action. */
    val Donate = IconLoader.getIcon("/icons/donate.svg", PromptBundlerIcons::class.java)
}
