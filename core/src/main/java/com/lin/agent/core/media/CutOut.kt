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

package com.lin.agent.core.media

/**
 * The pixel work behind "cut out what I tapped": turning the segmenter's mask into soft edges,
 * cropping to the object, and putting it back over a blurred copy of its own photo.
 *
 * Plain arrays rather than bitmaps, so all of it runs and is tested on the JVM. Pixels are
 * packed ARGB ints, as `Bitmap.getPixels` gives them; alpha values are 0..255.
 */
object CutOut {

  /**
   * How opaque each pixel of the object is, from the segmenter's confidence (0..1 per pixel).
   *
   * A hard 0.5 threshold gives a jagged, stair-stepped edge, especially around hair and fur. The
   * segmenter's own uncertainty is a good edge: fully in above [high], fully out below [low],
   * and a smooth ramp between.
   */
  fun alphaFrom(confidence: FloatArray, low: Float = 0.35f, high: Float = 0.65f): IntArray =
    IntArray(confidence.size) { i ->
      val t = ((confidence[i] - low) / (high - low)).coerceIn(0f, 1f)
      val smooth = t * t * (3 - 2 * t)
      (smooth * 255 + 0.5f).toInt()
    }

  /** Whether anything was selected at all, so an empty tap does not produce an empty file. */
  fun anythingSelected(alpha: IntArray, atLeast: Int = 16): Boolean = alpha.count { it >= 128 } >= atLeast

  /** The photo with everything outside the object made transparent. */
  fun cutOut(pixels: IntArray, alpha: IntArray): IntArray {
    require(pixels.size == alpha.size) { "The mask and the photo are different sizes." }
    return IntArray(pixels.size) { i ->
      val a = (pixels[i] ushr 24) * alpha[i] / 255
      (a shl 24) or (pixels[i] and 0x00FFFFFF)
    }
  }

  /** The smallest box around the object, padded, as left, top, right, bottom (exclusive). */
  fun bounds(alpha: IntArray, width: Int, height: Int, padding: Int = 8): Box? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until width) {
        if (alpha[row + x] >= 128) {
          if (x < left) left = x
          if (x > right) right = x
          if (y < top) top = y
          if (y > bottom) bottom = y
        }
      }
    }
    if (right < 0) return null
    return Box(
      (left - padding).coerceAtLeast(0),
      (top - padding).coerceAtLeast(0),
      (right + 1 + padding).coerceAtMost(width),
      (bottom + 1 + padding).coerceAtMost(height),
    )
  }

  data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
  }

  /** The part of [pixels] inside [box]. */
  fun crop(pixels: IntArray, width: Int, box: Box): IntArray {
    val out = IntArray(box.width * box.height)
    for (y in 0 until box.height) {
      System.arraycopy(pixels, (box.top + y) * width + box.left, out, y * box.width, box.width)
    }
    return out
  }

  /**
   * A blurred copy, by three passes of a box blur in each direction - which is close to a
   * Gaussian and needs nothing from the GPU. Run it on a downscaled photo; it is linear in size.
   */
  fun blur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    if (radius <= 0) return pixels.copyOf()
    var a = pixels.copyOf()
    var b = IntArray(pixels.size)
    repeat(3) {
      boxPass(a, b, width, height, radius, horizontal = true)
      boxPass(b, a, width, height, radius, horizontal = false)
    }
    return a
  }

  private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = 2 * r + 1
    for (line in 0 until lines) {
      fun at(i: Int): Int {
        val c = i.coerceIn(0, length - 1)
        return if (horizontal) line * w + c else c * w + line
      }
      var sa = 0; var sr = 0; var sg = 0; var sb = 0
      for (i in -r..r) {
        val p = src[at(i)]
        sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
      }
      for (i in 0 until length) {
        dst[at(i)] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
        val out = src[at(i - r)]
        val inn = src[at(i + r + 1)]
        sa += (inn ushr 24) - (out ushr 24)
        sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
        sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
        sb += (inn and 0xFF) - (out and 0xFF)
      }
    }
  }

  /** [front] where [alpha] says the object is, [back] elsewhere, mixed along the soft edge. */
  fun blend(front: IntArray, back: IntArray, alpha: IntArray): IntArray =
    IntArray(front.size) { i ->
      val a = alpha[i]
      if (a == 255) front[i]
      else if (a == 0) back[i]
      else {
        val f = front[i]
        val b = back[i]
        fun mix(shift: Int) = ((((f shr shift) and 0xFF) * a + ((b shr shift) and 0xFF) * (255 - a)) / 255) shl shift
        (0xFF shl 24) or mix(16) or mix(8) or mix(0)
      }
    }

  /** The cut-out flattened onto a plain colour, for sending to a model that cannot take alpha. */
  fun onPlain(cut: IntArray, color: Int = 0xFFFFFFFF.toInt()): IntArray =
    blend(cut.map { it or (0xFF shl 24) }.toIntArray(), IntArray(cut.size) { color }, IntArray(cut.size) { cut[it] ushr 24 })
}
