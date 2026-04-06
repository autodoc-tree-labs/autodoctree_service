package com.autodoctree.api.domain.document

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

private const val MAX_MARKDOWN_HEADING_LEVEL = 3

data class ResolvedDocumentContent(
    val blocksJson: String,
    val bodyMarkdown: String,
    val bodyText: String
)

@Component
class DocumentContentMapper(
    private val objectMapper: ObjectMapper
) {
    fun resolveForPersist(blocksJson: JsonNode?, bodyMarkdown: String?): ResolvedDocumentContent {
        val normalizedBlocks = normalizeBlocks(blocksJson, bodyMarkdown)
        val markdown = blocksToMarkdown(normalizedBlocks).ifBlank { bodyMarkdown?.trim().orEmpty() }
        val plainText = blocksToPlainText(normalizedBlocks).ifBlank { markdownToPlainText(markdown) }

        return ResolvedDocumentContent(
            blocksJson = objectMapper.writeValueAsString(normalizedBlocks),
            bodyMarkdown = markdown,
            bodyText = plainText
        )
    }

    fun toResponseBlocks(storedBlocksJson: String?, bodyMarkdown: String?): JsonNode {
        if (!storedBlocksJson.isNullOrBlank()) {
            val parsed = runCatching { objectMapper.readTree(storedBlocksJson) }.getOrNull()
            if (parsed != null && !parsed.isNull) {
                return normalizeDocumentNode(parsed)
            }
        }
        return markdownToBlocks(bodyMarkdown.orEmpty())
    }

    fun markdownToBlocks(markdown: String): ObjectNode {
        val doc = objectMapper.createObjectNode()
        val content = objectMapper.createArrayNode()
        doc.put("type", "doc")
        doc.set<ArrayNode>("content", content)

        val normalizedMarkdown = markdown.replace("\r\n", "\n")
        val lines = normalizedMarkdown.split('\n')
        var index = 0

        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()

            if (line.isBlank()) {
                index += 1
                continue
            }

            if (line.startsWith("```") || line.startsWith("~~~")) {
                val fence = if (line.startsWith("~~~")) "~~~" else "```"
                val language = line.removePrefix(fence).trim().takeIf { it.isNotBlank() }
                val codeLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trimStart().startsWith(fence)) {
                    codeLines += lines[index]
                    index += 1
                }
                if (index < lines.size) {
                    index += 1
                }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "codeBlock")
                        if (!language.isNullOrBlank()) {
                            set<ObjectNode>("attrs", objectMapper.createObjectNode().put("language", language))
                        }
                        set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(codeLines.joinToString("\n"))))
                    }
                )
                continue
            }

            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length.coerceAtMost(MAX_MARKDOWN_HEADING_LEVEL)
                val headingText = headingMatch.groupValues[2].trim()
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "heading")
                        set<ObjectNode>("attrs", objectMapper.createObjectNode().put("level", level))
                        set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(headingText)))
                    }
                )
                index += 1
                continue
            }

            if (line.matches(Regex("^([-*_])\\1\\1+$"))) {
                content.add(objectMapper.createObjectNode().put("type", "horizontalRule"))
                index += 1
                continue
            }

            if (line.equals("[toc]", ignoreCase = true)) {
                content.add(objectMapper.createObjectNode().put("type", "tocBlock"))
                index += 1
                continue
            }

            if (line.startsWith("> [!", ignoreCase = true)) {
                val calloutText = line.substringAfter(']').trim().ifBlank { "Callout" }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "callout")
                        set<ObjectNode>("attrs", objectMapper.createObjectNode().put("icon", "💡").put("tone", "default"))
                        set<ArrayNode>(
                            "content",
                            objectMapper.createArrayNode().add(
                                objectMapper.createObjectNode().apply {
                                    put("type", "paragraph")
                                    set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(calloutText)))
                                }
                            )
                        )
                    }
                )
                index += 1
                continue
            }

            if (line.startsWith(">")) {
                val quoteText = line.removePrefix(">").trim().ifBlank { " " }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "blockquote")
                        set<ArrayNode>(
                            "content",
                            objectMapper.createArrayNode().add(
                                objectMapper.createObjectNode().apply {
                                    put("type", "paragraph")
                                    set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(quoteText)))
                                }
                            )
                        )
                    }
                )
                index += 1
                continue
            }

            val taskMatch = Regex("^-\\s*\\[( |x|X)]\\s+(.+)$").find(line)
            if (taskMatch != null) {
                val taskItems = objectMapper.createArrayNode()
                while (index < lines.size) {
                    val candidate = lines[index].trimEnd()
                    val taskCandidate = Regex("^-\\s*\\[( |x|X)]\\s+(.+)$").find(candidate) ?: break
                    val checked = taskCandidate.groupValues[1].equals("x", ignoreCase = true)
                    taskItems.add(
                        objectMapper.createObjectNode().apply {
                            put("type", "taskItem")
                            set<ObjectNode>("attrs", objectMapper.createObjectNode().put("checked", checked))
                            set<ArrayNode>(
                                "content",
                                objectMapper.createArrayNode().add(
                                    objectMapper.createObjectNode().apply {
                                        put("type", "paragraph")
                                        set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(taskCandidate.groupValues[2].trim())))
                                    }
                                )
                            )
                        }
                    )
                    index += 1
                }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "taskList")
                        set<ArrayNode>("content", taskItems)
                    }
                )
                continue
            }

            val bulletMatch = Regex("^[-*+]\\s+(.+)$").find(line)
            if (bulletMatch != null) {
                val listItems = objectMapper.createArrayNode()
                while (index < lines.size) {
                    val candidate = lines[index].trimEnd()
                    val itemMatch = Regex("^[-*+]\\s+(.+)$").find(candidate) ?: break
                    listItems.add(listItemNode(itemMatch.groupValues[1].trim()))
                    index += 1
                }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "bulletList")
                        set<ArrayNode>("content", listItems)
                    }
                )
                continue
            }

            val orderedMatch = Regex("^\\d+\\.\\s+(.+)$").find(line)
            if (orderedMatch != null) {
                val listItems = objectMapper.createArrayNode()
                while (index < lines.size) {
                    val candidate = lines[index].trimEnd()
                    val itemMatch = Regex("^\\d+\\.\\s+(.+)$").find(candidate) ?: break
                    listItems.add(listItemNode(itemMatch.groupValues[1].trim()))
                    index += 1
                }
                content.add(
                    objectMapper.createObjectNode().apply {
                        put("type", "orderedList")
                        set<ArrayNode>("content", listItems)
                    }
                )
                continue
            }

            val paragraphLines = mutableListOf(line.trim())
            index += 1
            while (index < lines.size) {
                val candidate = lines[index].trimEnd()
                if (candidate.isBlank()) {
                    break
                }
                val startsBlock = candidate.startsWith("#") ||
                    candidate.startsWith(">") ||
                    candidate.startsWith("```") ||
                    candidate.startsWith("~~~") ||
                    Regex("^[-*+]\\s+.+").matches(candidate) ||
                    Regex("^\\d+\\.\\s+.+").matches(candidate) ||
                    Regex("^-\\s*\\[( |x|X)]\\s+.+").matches(candidate)
                if (startsBlock) {
                    break
                }
                paragraphLines += candidate.trim()
                index += 1
            }

            content.add(
                objectMapper.createObjectNode().apply {
                    put("type", "paragraph")
                    set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(paragraphLines.joinToString(" "))))
                }
            )
        }

        if (content.isEmpty) {
            content.add(objectMapper.createObjectNode().put("type", "paragraph"))
        }
        return doc
    }

    fun blocksToMarkdown(doc: JsonNode): String {
        val chunks = mutableListOf<String>()
        val root = normalizeDocumentNode(doc)
        root.path("content").forEach { node ->
            val rendered = renderBlockNode(node).trimEnd()
            if (rendered.isNotBlank()) {
                chunks += rendered
            }
        }
        return chunks.joinToString("\n\n").trim()
    }

    fun blocksToPlainText(doc: JsonNode): String {
        val segments = mutableListOf<String>()

        fun walk(node: JsonNode?) {
            if (node == null || node.isMissingNode || node.isNull) {
                return
            }
            if (node.has("text")) {
                node.path("text").asText("").trim().takeIf { it.isNotEmpty() }?.let(segments::add)
            }
            when (node.path("type").asText("")) {
                "image" -> node.path("attrs").path("alt").asText("").trim().takeIf { it.isNotEmpty() }?.let(segments::add)
                "fileBlock" -> node.path("attrs").path("filename").asText("").trim().takeIf { it.isNotEmpty() }?.let(segments::add)
            }
            node.path("content").forEach(::walk)
        }

        walk(normalizeDocumentNode(doc))
        return segments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    fun markdownToPlainText(markdown: String): String {
        if (markdown.isBlank()) {
            return ""
        }
        return markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]\\([^)]*\\)"), " ")
            .replace(Regex("[#>*_\\-]{1,}"), " ")
            .replace(Regex("\\|"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeBlocks(blocksJson: JsonNode?, bodyMarkdown: String?): JsonNode {
        if (blocksJson != null && !blocksJson.isNull) {
            return normalizeDocumentNode(blocksJson)
        }
        return markdownToBlocks(bodyMarkdown.orEmpty())
    }

    private fun normalizeDocumentNode(raw: JsonNode): JsonNode {
        if (raw.isObject && raw.path("type").asText("") == "doc") {
            return raw
        }
        if (raw.isArray) {
            return objectMapper.createObjectNode().apply {
                put("type", "doc")
                set<ArrayNode>("content", raw as ArrayNode)
            }
        }
        return markdownToBlocks(raw.path("text").asText(""))
    }

    private fun renderBlockNode(node: JsonNode): String {
        return when (node.path("type").asText("")) {
            "paragraph" -> renderInline(node.path("content"))
            "heading" -> {
                val level = node.path("attrs").path("level").asInt(1).coerceIn(1, MAX_MARKDOWN_HEADING_LEVEL)
                "${"#".repeat(level)} ${renderInline(node.path("content"))}".trimEnd()
            }
            "bulletList" -> renderList(node.path("content"), ordered = false)
            "orderedList" -> renderList(node.path("content"), ordered = true)
            "taskList" -> renderTaskList(node.path("content"))
            "blockquote" -> renderQuote(node)
            "horizontalRule" -> "---"
            "codeBlock" -> renderCodeBlock(node)
            "callout" -> {
                val text = renderInlineOrParagraphs(node)
                if (text.isBlank()) "> [!NOTE]" else "> [!NOTE] $text"
            }
            "toggleBlock" -> {
                val title = node.path("attrs").path("title").asText("Toggle")
                val body = node.path("content").map { renderBlockNode(it) }.filter { it.isNotBlank() }.joinToString("\n")
                if (body.isBlank()) {
                    "<details><summary>$title</summary></details>"
                } else {
                    "<details><summary>$title</summary>\n$body\n</details>"
                }
            }
            "image" -> {
                val alt = node.path("attrs").path("alt").asText("")
                val src = node.path("attrs").path("src").asText("")
                if (src.isBlank()) "![${alt.ifBlank { "image" }}]()" else "![${alt.ifBlank { "image" }}]($src)"
            }
            "fileBlock" -> {
                val filename = node.path("attrs").path("filename").asText("file")
                val url = node.path("attrs").path("url").asText("")
                if (url.isBlank()) "[$filename](attachment:${node.path("attrs").path("attachmentId").asText("")})" else "[$filename]($url)"
            }
            "tocBlock" -> "[TOC]"
            "table" -> renderTable(node)
            else -> {
                val nested = node.path("content").map { renderBlockNode(it) }.filter { it.isNotBlank() }
                nested.joinToString("\n")
            }
        }
    }

    private fun renderInline(content: JsonNode): String {
        if (!content.isArray) {
            return ""
        }
        val parts = mutableListOf<String>()
        content.forEach { child ->
            when (child.path("type").asText("")) {
                "text" -> parts += applyMarks(child.path("text").asText(""), child.path("marks"))
                "hardBreak" -> parts += "\\n"
                else -> {
                    val nested = renderInline(child.path("content"))
                    if (nested.isNotBlank()) {
                        parts += nested
                    }
                }
            }
        }
        return parts.joinToString("").trimEnd()
    }

    private fun applyMarks(rawText: String, marks: JsonNode): String {
        var rendered = rawText
        if (!marks.isArray) {
            return rendered
        }
        marks.forEach { mark ->
            rendered = when (mark.path("type").asText("")) {
                "bold" -> "**$rendered**"
                "italic" -> "*$rendered*"
                "code" -> "`$rendered`"
                "strike" -> "~~$rendered~~"
                "link" -> {
                    val href = mark.path("attrs").path("href").asText("")
                    if (href.isBlank()) rendered else "[$rendered]($href)"
                }
                else -> rendered
            }
        }
        return rendered
    }

    private fun renderList(items: JsonNode, ordered: Boolean): String {
        if (!items.isArray) {
            return ""
        }
        val renderedItems = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            val text = renderInlineOrParagraphs(item)
            val prefix = if (ordered) "${index + 1}. " else "- "
            renderedItems += "$prefix${text.ifBlank { " " }}"
        }
        return renderedItems.joinToString("\n")
    }

    private fun renderTaskList(items: JsonNode): String {
        if (!items.isArray) {
            return ""
        }
        return items.joinToString("\n") { item ->
            val checked = item.path("attrs").path("checked").asBoolean(false)
            val checkbox = if (checked) "x" else " "
            val text = renderInlineOrParagraphs(item).ifBlank { " " }
            "- [$checkbox] $text"
        }
    }

    private fun renderQuote(node: JsonNode): String {
        val text = renderInlineOrParagraphs(node)
        return text.lineSequence().joinToString("\n") { line ->
            if (line.isBlank()) ">" else "> $line"
        }
    }

    private fun renderCodeBlock(node: JsonNode): String {
        val language = node.path("attrs").path("language").asText("")
        val code = node.path("content").firstOrNull()?.path("text")?.asText("") ?: ""
        val header = if (language.isBlank()) "```" else "```$language"
        return "$header\n$code\n```"
    }

    private fun renderInlineOrParagraphs(node: JsonNode): String {
        val direct = renderInline(node.path("content"))
        if (direct.isNotBlank()) {
            return direct
        }
        return node.path("content").map { child ->
            when (child.path("type").asText("")) {
                "paragraph" -> renderInline(child.path("content"))
                else -> renderBlockNode(child)
            }
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun renderTable(node: JsonNode): String {
        val rows = node.path("content")
        if (!rows.isArray || rows.isEmpty) {
            return "| |\n| --- |"
        }
        val matrix = rows.map { row ->
            row.path("content").map { cell ->
                val text = cell.path("content").map { paragraph -> renderInline(paragraph.path("content")) }.joinToString(" ").trim()
                text.ifBlank { " " }
            }
        }
        val maxCols = matrix.maxOfOrNull { it.size } ?: 1
        val header = matrix.firstOrNull().orEmpty().padTo(maxCols)
        val separator = List(maxCols) { "---" }
        val body = matrix.drop(1).ifEmpty { listOf(List(maxCols) { " " }) }.map { it.padTo(maxCols) }
        val lines = mutableListOf<String>()
        lines += "| ${header.joinToString(" | ")} |"
        lines += "| ${separator.joinToString(" | ")} |"
        body.forEach { lines += "| ${it.joinToString(" | ")} |" }
        return lines.joinToString("\n")
    }

    private fun List<String>.padTo(size: Int): List<String> {
        if (this.size >= size) {
            return this
        }
        return this + List(size - this.size) { " " }
    }

    private fun listItemNode(text: String): ObjectNode {
        return objectMapper.createObjectNode().apply {
            put("type", "listItem")
            set<ArrayNode>(
                "content",
                objectMapper.createArrayNode().add(
                    objectMapper.createObjectNode().apply {
                        put("type", "paragraph")
                        set<ArrayNode>("content", objectMapper.createArrayNode().add(textNode(text)))
                    }
                )
            )
        }
    }

    private fun textNode(text: String): ObjectNode {
        return objectMapper.createObjectNode().apply {
            put("type", "text")
            put("text", text)
        }
    }
}
