package io.github.patschgoo.castapp.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import java.io.File

/**
 * Finds what a person would trip over in a picture, so not every problem has to be spotted by
 * eye: cut-off text (ellipsis or clipped), words broken in the middle, and tap targets smaller
 * than 48 dp. Writes `<picture>.txt` next to the picture when it finds something.
 */
object Audit {
    fun write(rule: ComposeTestRule, picture: File) {
        val findings = findings(rule)
        // Pictures written into the repository (store, README) keep their reports in the build folder.
        val report = if (picture.absolutePath.startsWith(Shots.outDir.absolutePath)) File(picture.path.removeSuffix(".png") + ".txt")
        else File(Shots.outDir, "audit/" + picture.toRelativeString(Shots.repoFile("")).replace('/', '_').removeSuffix(".png") + ".txt")
        report.parentFile?.mkdirs()
        if (findings.isEmpty()) report.delete() else report.writeText(findings.joinToString("\n", postfix = "\n"))
    }

    fun findings(rule: ComposeTestRule): List<String> {
        val out = linkedSetOf<String>()
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (node in texts) {
            val results = ArrayList<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            for (r in results) {
                val text = r.layoutInput.text.text
                if (text.isBlank()) continue
                val ellipsized = (0 until r.lineCount).any { r.isLineEllipsized(it) }
                // More lines than allowed, or taller than the space it got (a pixel of rounding is fine).
                val clipped = r.multiParagraph.didExceedMaxLines || r.multiParagraph.height > r.size.height + 1.5f
                when {
                    ellipsized -> out += "cut off (…): \"${text.oneLine()}\""
                    clipped -> out += "clipped: \"${text.oneLine()}\""
                }
                for (line in 0 until r.lineCount - 1) {
                    val end = r.getLineEnd(line)
                    if (end in 1 until text.length && text[end - 1].isLetter() && text[end].isLetter()) {
                        out += "word broken: \"${text.oneLine()}\" after \"${text.substring(0, end).takeLast(12)}\""
                    }
                }
            }
        }
        val density = rule.density.density
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (node in clickable) {
            if (node.config.contains(SemanticsProperties.Disabled)) continue
            // Material's buttons take touches in 48 dp around a smaller visible shape.
            val touch = node.touchBoundsInRoot
            val w = touch.width / density
            val h = touch.height / density
            // Zero: scrolled out of sight.
            if (w > 0f && h > 0f && (w < 47.5f || h < 47.5f)) out += "small target %.0fx%.0f dp: %s".format(w, h, label(node))
        }
        return out.toList()
    }

    private fun label(node: SemanticsNode): String {
        val c = node.config
        return (c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: node.children.firstNotNullOfOrNull { ch -> ch.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
            ?: "(no label)").oneLine()
    }

    private fun String.oneLine() = replace('\n', ' ').let { if (it.length > 70) it.take(70) + "…" else it }
}
