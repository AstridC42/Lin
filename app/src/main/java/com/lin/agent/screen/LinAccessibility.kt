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

package com.lin.agent.screen

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lin.agent.core.screen.ScreenNode

/**
 * Lin's hands, and its eyes for what is on screen.
 *
 * This is the heaviest privilege on Android and it is worth saying exactly what it is. An
 * accessibility service can read the contents of every screen the person looks at and act on
 * their behalf - which is how a screen reader works, and also how Tasker and AutoInput drive
 * other apps. It is what makes the difference between Lin replying to a notification and Lin
 * writing a message in WhatsApp's own composer. It is granted by hand in the phone's settings,
 * shown there permanently, and revocable in two taps.
 *
 * Two things keep that privilege from being more than it needs to be:
 *
 *  - **Nothing is remembered.** The tree is read when something asks, turned into plain data,
 *    and dropped. There is no index of what crossed the screen, nothing written to disk, and
 *    no event handler that collects: [onAccessibilityEvent] does nothing at all, which is
 *    unusual for such a service and is the point.
 *  - **Nothing here decides what to press.** Which box is the composer and which button sends
 *    is [com.lin.agent.core.screen.composerField] and
 *    [com.lin.agent.core.screen.sendButton], in `core`, with tests - including the one that
 *    matters, which is that a microphone button is never mistaken for a send button.
 */
class LinAccessibility : AccessibilityService() {

  override fun onServiceConnected() {
    super.onServiceConnected()
    live = this
  }

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    live = null
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    live = null
    super.onDestroy()
  }

  /**
   * Deliberately empty.
   *
   * A service like this usually collects events. This one is asked questions instead, so an
   * event handler would only be building a record of somebody's screen that nothing reads.
   * The events are still subscribed to in the config, because a service with no event types
   * is not bound at all on some versions.
   */
  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit

  /** The package of the app in front, or empty. */
  fun frontApp(): String =
    runCatching { rootInActiveWindow?.packageName?.toString().orEmpty() }.getOrDefault("")

  /**
   * Everything on the screen in front, as plain data.
   *
   * Bounded twice. [MOST_NODES] stops a list view with a thousand rows turning one question
   * into a megabyte, and [DEEPEST] stops a tree that refers to itself - which happens, and
   * which would otherwise recurse until the stack goes.
   */
  fun nodes(): List<ScreenNode> {
    val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
    val found = mutableListOf<ScreenNode>()
    runCatching { walk(root, 0, found) }.onFailure { Log.w(TAG, "Could not read the screen", it) }
    return found
  }

  /**
   * Types [text] into the node that [matching] names, replacing what is there.
   *
   * Nodes are looked up again rather than held: an `AccessibilityNodeInfo` goes stale the
   * moment the screen changes, and acting on a stale one either does nothing or acts on
   * whatever took its place. So a caller decides *which* node from the plain data, and this
   * finds it again right now.
   */
  fun type(matching: ScreenNode, text: String): Boolean {
    val node = find(matching) ?: return false
    return runCatching {
        val arguments =
          Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
          }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
      }
      .getOrDefault(false)
  }

  /**
   * Presses the node that [matching] names.
   *
   * Walks up to a clickable parent when the node itself is not clickable, which is the common
   * shape: the thing with the label is an ImageView inside the button that handles the tap.
   * Bounded, because an unbounded walk up ends at the window and taps something enormous.
   */
  fun press(matching: ScreenNode): Boolean {
    var node = find(matching) ?: return false
    var steps = 0
    while (steps++ < UP_TO_PARENTS) {
      if (node.isClickable && node.isEnabled) {
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
          .getOrDefault(false)
      }
      node = runCatching { node.parent }.getOrNull() ?: return false
    }
    return false
  }

  /**
   * The live node that [wanted] was read from, or null if it is no longer there.
   *
   * Matched on the id when there is one and on the label when there is not, which is the same
   * information the decision was made from. Not matched on position: a composer that has
   * moved by a few pixels because the keyboard opened is the same composer.
   */
  private fun find(wanted: ScreenNode): AccessibilityNodeInfo? {
    val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
    val live = mutableListOf<AccessibilityNodeInfo>()
    runCatching { collect(root, 0, live) }
    return live.firstOrNull { same(it, wanted) }
  }

  private fun same(node: AccessibilityNodeInfo, wanted: ScreenNode): Boolean {
    val id = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
    if (wanted.id.isNotEmpty()) return id == wanted.id
    if (id.isNotEmpty()) return false
    val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
    val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
    return description == wanted.description && text == wanted.text
  }

  private fun walk(node: AccessibilityNodeInfo, depth: Int, into: MutableList<ScreenNode>) {
    if (depth > DEEPEST || into.size >= MOST_NODES) return
    into.add(
      ScreenNode(
        id = node.viewIdResourceName.orEmpty(),
        text = node.text?.toString().orEmpty(),
        description = node.contentDescription?.toString().orEmpty(),
        className = node.className?.toString().orEmpty(),
        editable = node.isEditable,
        clickable = node.isClickable,
        enabled = node.isEnabled,
      )
    )
    for (index in 0 until node.childCount) {
      val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
      walk(child, depth + 1, into)
    }
  }

  private fun collect(node: AccessibilityNodeInfo, depth: Int, into: MutableList<AccessibilityNodeInfo>) {
    if (depth > DEEPEST || into.size >= MOST_NODES) return
    into.add(node)
    for (index in 0 until node.childCount) {
      val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
      collect(child, depth + 1, into)
    }
  }

  companion object {
    private const val TAG = "LinAccessibility"
    private const val DEEPEST = 60
    private const val MOST_NODES = 1_500
    private const val UP_TO_PARENTS = 6

    /**
     * The running service, or null when the user has not turned it on.
     *
     * A static reference because an AccessibilityService is created by the system and cannot
     * be injected or bound to. Cleared on unbind and on destroy, so a revoked permission
     * leaves nothing behind that a tool could call.
     */
    @Volatile
    var live: LinAccessibility? = null
      private set
  }
}
