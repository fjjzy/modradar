package com.modradar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.modradar.app.ui.theme.MonoStyle

/**
 * 轻量 Markdown 渲染（AI 回复、协议文档、报告说明共用）。
 *
 * 支持：标题、段落、粗体/斜体/删除线、行内代码、链接、有序/无序列表（含两级缩进）、
 * 引用、围栏代码块、表格、分隔线。不支持 HTML 与图片——AI 输出里用不到，也避免引入渲染面。
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { MdParser.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b -> MdBlockView(b, style, color) }
    }
}

@Composable
private fun MdBlockView(b: MdBlock, style: TextStyle, color: Color) {
    val scheme = MaterialTheme.colorScheme
    when (b) {
        is MdBlock.Heading -> {
            val s = when (b.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                inline(b.text, scheme.primary, scheme.surfaceContainerHighest),
                style = s.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                modifier = Modifier.padding(top = if (b.level <= 2) 4.dp else 2.dp),
            )
        }
        is MdBlock.Paragraph -> Text(inline(b.text, scheme.primary, scheme.surfaceContainerHighest), style = style, color = color)
        is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            Text(
                b.marker,
                style = style,
                color = if (b.ordered) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 18.dp),
            )
            Text(inline(b.text, scheme.primary, scheme.surfaceContainerHighest), style = style, color = color, modifier = Modifier.weight(1f))
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(scheme.primary.copy(alpha = 0.6f)))
            Spacer(Modifier.width(10.dp))
            Text(inline(b.text, scheme.primary, scheme.surfaceContainerHighest), style = style, color = scheme.onSurfaceVariant)
        }
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHighest)
                .horizontalScroll(rememberScrollState()).padding(10.dp)
        ) { Text(b.code, style = MonoStyle, color = scheme.onSurface) }
        is MdBlock.Table -> MdTable(b, style, color)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, style: TextStyle, color: Color) {
    val scheme = MaterialTheme.colorScheme
    val cols = t.header.size
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        (listOf(t.header) + t.rows).forEachIndexed { r, row ->
            Row(Modifier.background(if (r == 0) scheme.surfaceContainerHighest else Color.Transparent)) {
                for (c in 0 until cols) {
                    Text(
                        inline(row.getOrElse(c) { "" }, scheme.primary, scheme.surfaceContainerHighest),
                        style = style.copy(fontWeight = if (r == 0) FontWeight.SemiBold else style.fontWeight),
                        color = color,
                        modifier = Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (r < t.rows.size) HorizontalDivider(color = scheme.outlineVariant)
        }
    }
}

private val INLINE = Regex(
    "(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(__[^_]+__)|(~~[^~]+~~)|(\\[[^\\]]+]\\([^)\\s]+\\))|(\\*[^*\\s][^*]*\\*)|(_[^_\\s][^_]*_)"
)

/** 行内语法 → AnnotatedString；不认识的符号原样保留。 */
fun inline(src: String, link: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in INLINE.findAll(src)) {
        if (m.range.first < i) continue
        append(src.substring(i, m.range.first))
        val t = m.value
        when {
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                append(" " + t.trim('`') + " ")
            }
            t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(inline(t.substring(2, t.length - 2), link, codeBg))
            }
            t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("[") -> {
                val label = t.substring(1, t.indexOf("]("))
                val url = t.substring(t.indexOf("](") + 2, t.length - 1)
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(label) }
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        i = m.range.last + 1
    }
    if (i < src.length) append(src.substring(i))
}

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int, val ordered: Boolean) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** 逐行解析为块；纯函数，便于单测。 */
object MdParser {

    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val BULLET = Regex("^(\\s*)[-*+•·]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)、]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()

        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val lang = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i]); i++
                    }
                    out += MdBlock.Code(lang, code.toString())
                }
                trimmed.isEmpty() -> flush()
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                }
                RULE.matches(line) && para.isEmpty() -> { flush(); out += MdBlock.Rule }
                trimmed.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    out += MdBlock.Table(header, rows)
                    continue
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val q = StringBuilder(trimmed.removePrefix(">").trim())
                    while (i + 1 < lines.size && lines[i + 1].trim().startsWith(">")) {
                        i++; q.append('\n').append(lines[i].trim().removePrefix(">").trim())
                    }
                    out += MdBlock.Quote(q.toString())
                }
                ORDERED.matches(line) -> {
                    flush()
                    val m = ORDERED.find(line)!!
                    out += MdBlock.ListItem(m.groupValues[2] + ".", m.groupValues[3], indentOf(m.groupValues[1]), true)
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!
                    out += MdBlock.ListItem("•", m.groupValues[2], indentOf(m.groupValues[1]), false)
                }
                else -> {
                    // 列表项的续行（缩进的普通文本）并入上一项
                    val last = out.lastOrNull()
                    if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith("  ")) {
                        out[out.lastIndex] = last.copy(text = last.text + " " + trimmed)
                    } else {
                        if (para.isNotEmpty()) para.append('\n')
                        para.append(trimmed)
                    }
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun indentOf(ws: String): Int = (ws.replace("\t", "    ").length / 2).coerceAtMost(3)

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}
