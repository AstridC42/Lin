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

package com.lin.agent.model

import android.net.Uri
import com.lin.agent.core.documents.DocRead
import com.lin.agent.core.documents.DocumentLibrary
import com.lin.agent.core.documents.Documents
import com.lin.agent.core.documents.PdfPages
import com.lin.agent.core.documents.KnownDoc
import com.lin.agent.core.documents.fileNameFor
import com.lin.agent.core.documents.formatFor
import com.lin.agent.core.documents.unusedFile
import com.lin.agent.core.documents.write
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult

/** Lists what Lin may read, so it answers "what documents do I have" from fact. */
class ListDocumentsTool(private val library: DocumentLibrary) : AgentTool {

  override val name = "list_documents"

  override val description = "List the documents the user has given Lin access to."

  override val parameters = emptyList<ToolParameter>()

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val documents = library.all()
    if (documents.isEmpty()) {
      return ToolResult.Ok(
        "There are no documents yet. The user adds one with the Documents button in settings, " +
          "or by sharing a file to Lin from any app. Lin cannot browse their storage."
      )
    }
    return ToolResult.Ok(
      buildString {
        append("Documents Lin can read:")
        documents.forEach { append("\n- ").append(it.name) }
      }
    )
  }
}

/**
 * Reads a document's text.
 *
 * The name is matched loosely because it arrives as something somebody said - see
 * [com.lin.agent.core.documents.matching]. A miss is reported as a miss: a wrong document read
 * confidently is worse than "I cannot find that", because the user has no way to tell the answer
 * came from the wrong file.
 */
class ReadDocumentTool(
  private val library: DocumentLibrary,
  private val documents: Documents,
) : AgentTool {

  override val name = "read_document"

  override val description =
    "Read the text of one of the user's documents. Use list_documents first if unsure of the name."

  override val parameters =
    listOf(
      ToolParameter(
        name = "name",
        type = ParamType.STRING,
        description = "The document's name, as the user refers to it.",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["name"] as? String)?.trim().orEmpty()
    if (asked.isEmpty()) return ToolResult.Failed("No document was named.")

    val found =
      library.find(asked)
        ?: return ToolResult.Failed(
          "There is no document called \"$asked\". Use list_documents to see what there is, " +
            "and tell the user they can add one in settings or by sharing a file to Lin."
        )

    return when (val read = documents.read(Uri.parse(found.uri))) {
      is DocRead.Ok ->
        ToolResult.Ok("\"${found.name}\" says:\n\n${read.text}")
      is DocRead.Failed -> ToolResult.Failed(read.why)
    }
  }
}

/**
 * Writes a document, into Lin's own storage and nowhere else.
 *
 * This is the staged-action rule applied to files. Lin holds no storage permission for
 * documents: it can read the few a person handed it and it writes only inside its own private
 * directory. A new document appears on the Documents screen with a button to save or send it
 * where the user wants - so the file exists the moment Lin says it does, and the step that puts
 * it among somebody's own files is theirs.
 *
 * Revising works the same way and never writes over the original. The model is given the
 * existing text, produces new text, and that goes to a new file beside it. Non-destructive by
 * construction beats a confirmation dialog, because there is no wording a 2B model can produce
 * that should be able to replace somebody's contract.
 */
class WriteDocumentTool(
  private val library: DocumentLibrary,
  private val documents: Documents,
  private val pdf: PdfPages,
) : AgentTool {

  override val name = "write_document"

  override val description =
    "Write a new document. It is saved in Lin's own files and shown to the user, who can then " +
      "save or send it. This never changes a document the user already had."

  override val parameters =
    listOf(
      ToolParameter(
        name = "name",
        type = ParamType.STRING,
        description = "What to call it, without a folder.",
      ),
      ToolParameter(
        name = "text",
        type = ParamType.STRING,
        description = "The whole document, as plain paragraphs separated by blank lines.",
      ),
      ToolParameter(
        name = "format",
        type = ParamType.STRING,
        description = "One of docx, pdf, txt or md. Defaults to docx.",
      ),
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["name"] as? String)?.trim().orEmpty()
    val text = (arguments["text"] as? String).orEmpty()
    if (text.isBlank()) {
      return ToolResult.Failed("There was nothing to write. Ask the user what it should say.")
    }

    val format = formatFor(arguments["format"] as? String, asked)
    val fileName = fileNameFor(asked.ifEmpty { "Document" }, format)
    val target = unusedFile(documents.writtenDirectory(), fileName)

    return try {
      target.outputStream().use { out -> write(text, format, out, pdf) }
      library.add(
        KnownDoc(
          uri = Uri.fromFile(target).toString(),
          name = target.name,
          addedAt = System.currentTimeMillis(),
          written = true,
        )
      )
      ToolResult.Ok(
        "Written as \"${target.name}\". It is in Lin's documents, not saved among the user's " +
          "own files yet - tell them it is on the Documents screen with a button to save or " +
          "send it."
      )
    } catch (e: Throwable) {
      target.delete()
      ToolResult.Failed("That document could not be written: ${e.message ?: "unknown error"}")
    }
  }
}
