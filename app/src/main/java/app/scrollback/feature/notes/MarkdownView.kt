package app.scrollback.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scrollback.notes.Markdown
import app.scrollback.notes.MdBlock
import app.scrollback.ui.theme.SbColors
import app.scrollback.ui.theme.SbSpace

private val CodeBg = Color(0xFF16161A)

fun inlineAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    for (s in Markdown.inline(text)) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) CodeBg else Color.Unspecified,
            textDecoration = if (s.strike) TextDecoration.LineThrough else if (s.wiki) TextDecoration.Underline else null,
            color = when { s.tag -> SbColors.TextSecondary; s.wiki || s.link != null -> SbColors.Accent; else -> Color.Unspecified },
        )
        val url = s.link?.takeIf { !s.wiki && (it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:")) }
        if (url != null) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = SbColors.Accent, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(s.text) } }
        } else withStyle(style) { append(s.text) }
    }
}

/** Read-only Markdown preview. Plain-text notes render as one monospace-free paragraph per line. */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val body = MaterialTheme.typography.bodyLarge.copy(color = SbColors.TextPrimary, lineHeight = 24.sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SbSpace.s)) {
        for (b in Markdown.parse(text)) when (b) {
            is MdBlock.FrontMatter -> Text(
                b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = SbColors.TextTertiary,
                modifier = Modifier.fillMaxWidth().background(CodeBg, RoundedCornerShape(10.dp)).padding(SbSpace.m),
            )
            is MdBlock.Heading -> Text(
                inlineAnnotated(b.text),
                style = when (b.level) {
                    1 -> MaterialTheme.typography.headlineMedium
                    2 -> MaterialTheme.typography.headlineSmall
                    3 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }.copy(color = SbColors.TextPrimary, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = if (b.level <= 2) SbSpace.m else SbSpace.s),
            )
            is MdBlock.Paragraph -> Text(inlineAnnotated(b.text), style = body)
            is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 18).dp)) {
                val mark = when { b.checked == true -> "☑"; b.checked == false -> "☐"; b.ordered != null -> "${b.ordered}."; else -> "•" }
                Text(mark, style = body, color = SbColors.TextSecondary, modifier = Modifier.widthIn(min = 22.dp))
                Text(
                    inlineAnnotated(b.text),
                    style = if (b.checked == true) body.copy(color = SbColors.TextSecondary, textDecoration = TextDecoration.LineThrough) else body,
                )
            }
            is MdBlock.Quote -> Row {
                Box(Modifier.width(3.dp).padding(vertical = 2.dp).background(SbColors.TextTertiary).padding(vertical = 10.dp))
                Text(inlineAnnotated(b.text), style = body.copy(color = SbColors.TextSecondary), modifier = Modifier.padding(start = SbSpace.m))
            }
            is MdBlock.Code -> Text(
                b.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = SbColors.TextPrimary),
                softWrap = false,
                modifier = Modifier.fillMaxWidth().background(CodeBg, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState()).padding(SbSpace.m),
            )
            is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState()).background(CodeBg, RoundedCornerShape(10.dp)).padding(SbSpace.s)) {
                val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
                (listOf(b.header) + b.rows).forEachIndexed { i, row ->
                    Row {
                        for (c in 0 until cols) Text(
                            inlineAnnotated(row.getOrElse(c) { "" }),
                            style = MaterialTheme.typography.bodyMedium.copy(color = SbColors.TextPrimary, fontWeight = if (i == 0) FontWeight.SemiBold else null),
                            modifier = Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = SbSpace.s, vertical = 6.dp),
                        )
                    }
                    if (i == 0) HorizontalDivider(color = SbColors.TextTertiary)
                }
            }
            MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = SbSpace.s), color = SbColors.TextTertiary)
        }
    }
}
