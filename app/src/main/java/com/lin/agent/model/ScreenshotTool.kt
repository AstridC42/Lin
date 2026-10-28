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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.lin.agent.core.web.Kitesurf
import com.lin.agent.engine.AgentRole
import com.lin.agent.engine.AgentTool
import com.lin.agent.engine.Attachment
import com.lin.agent.engine.ParamType
import com.lin.agent.engine.ToolParameter
import com.lin.agent.engine.ToolResult
import com.lin.agent.web.BrowserResult
import com.lin.agent.web.KitesurfClient
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Shows what a web page looks like, taken in Cloudflare's Kitesurf browser. The picture goes on
 * screen for the person, and to the model too when it can see.
 */
class ScreenshotTool(private val context: Context, private val browser: KitesurfClient) : AgentTool {

  override val name = "screenshot_page"

  override val description =
    "Take a picture of what a web page looks like and show it to the user. Use it when they ask " +
      "to see a site, a layout or a page, not to read its text."

  override val parameters =
    listOf(
      ToolParameter(
        name = "url",
        type = ParamType.STRING,
        description = "The full address, starting with http:// or https://",
      )
    )

  override val roles = setOf(AgentRole.ORCHESTRATOR)

  override val guidance =
    "screenshot_page needs Cloudflare's browser to be set up, and says so when it is not. Use " +
      "read_page to get what a page says; use this only to show what it looks like."

  override suspend fun run(arguments: Map<String, Any?>): ToolResult {
    val asked = (arguments["url"] as? String).orEmpty()
    val url = Kitesurf.pageAddress(asked) ?: return ToolResult.Failed("\"$asked\" is not a web address. Ask the user for the link.")
    if (!browser.available()) {
      return ToolResult.Failed(
        "Taking a picture of a page needs Cloudflare's browser, which is not set up. Tell the " +
          "user to add their Cloudflare account id and token in Settings, under Tools."
      )
    }
    val bytes =
      when (val shot = browser.screenshot(url)) {
        is BrowserResult.Got -> shot.value
        is BrowserResult.Failed -> return ToolResult.Failed(shot.reason)
      }
    val jpeg = scaledJpeg(bytes) ?: return ToolResult.Failed("Cloudflare sent a picture Lin could not open.")
    val file = File(context.cacheDir, "page-${System.currentTimeMillis()}.jpg")
    val shows = if (runCatching { file.writeBytes(jpeg) }.isSuccess) listOf("file://${file.absolutePath}") else emptyList()
    return ToolResult.Ok(
      text = "Done - the picture of $url is on screen for the user.",
      shows = shows,
      looksAt = listOf(Attachment.Picture("page.jpg", jpeg)),
    )
  }

  /** The screenshot as a JPEG no wider than [MOST_WIDE], so it is quick to show and to send on. */
  private fun scaledJpeg(bytes: ByteArray): ByteArray? =
    runCatching {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled =
          if (decoded.width > MOST_WIDE) {
            val height = (decoded.height * (MOST_WIDE.toFloat() / decoded.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, MOST_WIDE, height, true)
          } else decoded
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
      }
      .onFailure { Log.w(TAG, "Could not re-encode the screenshot", it) }
      .getOrNull()

  private companion object {
    const val TAG = "LinScreenshot"
    const val MOST_WIDE = 800
  }
}
