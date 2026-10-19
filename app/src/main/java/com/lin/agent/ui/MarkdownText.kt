/*
 * Copyright 2026 The Lin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lin.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin.agent.core.text.Block
import com.lin.agent.core.text.Span
import com.lin.agent.core.text.markdown

/**
 * A model's reply, laid out as what it meant rather than as what it typed.
 *
 * Models write Markdown whether or not anyone asked, and until this the screen showed the
 * asterisks. The parsing is in core and tested; this only decides what each block looks like,
 * so a mistake here is a visual one and cannot lose text.
 *
 * Parsing is remembered on the text, because a streaming reply recomposes on every token and
 * re-parsing the whole answer each time is work that grows with the length of the answer.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
  val blocks = remember(text) { markdown(text) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (block in blocks) {
      when (block) {
        is Block.Paragraph ->
          Text(block.spans.annotated(), style = MaterialTheme.typography.bodyLarge)
        is Block.Heading ->
          Text(
            block.spans.annotated(),
            style =
              when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
              },
            modifier = Modifier.padding(top = 4.dp),
          )
        is Block.Bullet ->
          Row(
            modifier = Modifier.padding(start = (block.indent * 14).dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(block.spans.annotated(), style = MaterialTheme.typography.bodyLarge)
          }
        is Block.Numbered ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              "${block.number}.",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(block.spans.annotated(), style = MaterialTheme.typography.bodyLarge)
          }
        is Block.Quote ->
          Text(
            block.spans.annotated(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
          )
        // Scrolls sideways rather than wrapping: a wrapped command line is a command line
        // somebody cannot copy and run.
        is Block.Code ->
          Text(
            block.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            maxLines = Int.MAX_VALUE,
            softWrap = false,
            modifier =
              Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .horizontalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 12.dp, vertical = 10.dp)),
          )
      }
    }
  }
}

/** The marked runs of one block, as one styled string. */
@Composable
private fun List<Span>.annotated(): AnnotatedString {
  val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
  val linkColour = MaterialTheme.colorScheme.primary
  return buildAnnotatedString {
    for (span in this@annotated) {
      val style =
        SpanStyle(
          fontWeight = if (span.bold) FontWeight.SemiBold else null,
          fontStyle = if (span.italic) FontStyle.Italic else null,
          fontFamily = if (span.code) FontFamily.Monospace else null,
          background = if (span.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
      if (span.url.isEmpty()) {
        withStyle(style) { append(span.text) }
      } else {
        // A real link, opened by the system, so it goes to whatever the person uses for the
        // web. Coloured and underlined, because a source list that does not look tappable
        // will not be tapped.
        withLink(
          androidx.compose.ui.text.LinkAnnotation.Url(
            span.url,
            androidx.compose.ui.text.TextLinkStyles(
              style.copy(
                color = linkColour,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
              )
            ),
          )
        ) {
          append(span.text)
        }
      }
    }
  }
}
