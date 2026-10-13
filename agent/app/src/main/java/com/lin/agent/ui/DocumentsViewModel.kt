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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.agent.core.documents.DocKind
import com.lin.agent.core.documents.DocumentLibrary
import com.lin.agent.core.documents.Documents
import com.lin.agent.core.documents.KnownDoc
import com.lin.agent.core.documents.kindOf
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentsState(
  val documents: List<KnownDoc> = emptyList(),
  val loading: Boolean = true,
  val error: String = "",
)

/**
 * The documents Lin may read, and the two ways one arrives.
 *
 * Worth stating what this does not do: there is no browsing. The app holds no storage permission
 * for documents, so the only way in is the system picker or a share from another app, and each
 * one grants exactly one file. That is not a limitation worked around - it is the access model,
 * and it means "what can Lin read" is answerable by looking at this list.
 */
@HiltViewModel
class DocumentsViewModel
@Inject
constructor(private val library: DocumentLibrary, private val documents: Documents) : ViewModel() {

  private val _state = MutableStateFlow(DocumentsState())
  val state: StateFlow<DocumentsState> = _state.asStateFlow()

  init {
    reload()
  }

  fun reload() {
    viewModelScope.launch {
      _state.update { it.copy(documents = library.all(), loading = false) }
    }
  }

  /**
   * Takes on a document the user picked or shared.
   *
   * The persistable permission is what makes this last past the current process - without it the
   * URI works until the app is next started and then quietly does not. It is requested inside a
   * runCatching because a share (as opposed to a pick) often grants only a one-off read, and
   * failing to persist is not a reason to refuse the document: it will work now, and the user
   * can add it again later.
   */
  fun add(uri: Uri, context: Context) {
    viewModelScope.launch {
      runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        .onFailure { Log.i(TAG, "Could not persist access to $uri; it may be a one-off share") }

      val name = documents.nameOf(uri)
      if (kindOf(name, context.contentResolver.getType(uri)) == DocKind.UNKNOWN) {
        _state.update {
          it.copy(
            error =
              "Lin cannot read \"$name\". It reads PDF, Word (.docx) and plain text. An older " +
                ".doc has to be saved as .docx first."
          )
        }
        return@launch
      }

      library.add(KnownDoc(uri = uri.toString(), name = name, addedAt = System.currentTimeMillis()))
      _state.update { it.copy(error = "") }
      reload()
    }
  }

  /**
   * Stops Lin reading one.
   *
   * The URI permission is released as well as the row removed. Leaving it would mean the app
   * still held access to a file the user had just told it to forget, which is exactly the kind
   * of gap that makes a list like this untrustworthy.
   */
  fun forget(document: KnownDoc, context: Context) {
    _state.update { current -> current.copy(documents = current.documents - document) }
    viewModelScope.launch {
      val uri = Uri.parse(document.uri)
      runCatching {
          context.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        .onFailure { Log.i(TAG, "No persisted permission to release for ${document.name}") }
      // One Lin wrote is Lin's own file, so forgetting it deletes it - there is nowhere else it
      // exists. One of the user's own is only forgotten; deleting somebody's document because
      // they asked Lin to stop reading it would be indefensible.
      if (document.written) {
        runCatching { uri.path?.let { File(it).delete() } }
      }
      reload()
    }
  }

  /** A share sheet for a document Lin wrote, which is how it leaves Lin's own storage. */
  fun shareIntent(document: KnownDoc, context: Context): Intent? {
    val path = Uri.parse(document.uri).path ?: return null
    val shared =
      runCatching {
          FileProvider.getUriForFile(context, "${context.packageName}.documents", File(path))
        }
        .onFailure { Log.w(TAG, "Could not share ${document.name}", it) }
        .getOrNull() ?: return null

    return Intent(Intent.ACTION_SEND).apply {
      type = context.contentResolver.getType(shared) ?: "application/octet-stream"
      putExtra(Intent.EXTRA_STREAM, shared)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** What to ask the picker for: everything this app can actually read, and nothing else. */
  fun pickerTypes(): Array<String> =
    arrayOf(
      "application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "text/*",
    )

  fun dismissError() = _state.update { it.copy(error = "") }

  private companion object {
    const val TAG = "LinDocumentsViewModel"
  }
}
