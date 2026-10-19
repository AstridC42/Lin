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

package com.lin.agent.watch

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * A Tile with one big button: swipe to it from the watch face, tap, and Lin is already
 * listening. The quickest way to ask anything from the wrist.
 */
class AskTileService : TileService() {

  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
    val launch =
      ActionBuilders.LaunchAction.Builder()
        .setAndroidActivity(
          ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName)
            .setClassName(MainActivity::class.java.name)
            .addKeyToExtraMapping(MainActivity.EXTRA_LISTEN, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build())
            .build()
        )
        .build()
    val button =
      LayoutElementBuilders.Box.Builder()
        .setWidth(dp(132f))
        .setHeight(dp(132f))
        .setModifiers(
          ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId("ask").setOnClick(launch).build())
            .setBackground(
              ModifiersBuilders.Background.Builder()
                .setColor(argb(0xFF2A1F3D.toInt()))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(66f)).build())
                .build()
            )
            .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(getString(R.string.tile_ask)).build())
            .build()
        )
        .addContent(
          LayoutElementBuilders.Text.Builder()
            .setText(getString(R.string.tile_ask))
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setColor(argb(0xFFF2C14E.toInt())).setSize(sp(18f)).build())
            .build()
        )
        .build()
    val root =
      LayoutElementBuilders.Box.Builder()
        .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
        .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
        .addContent(button)
        .build()
    val tile =
      TileBuilders.Tile.Builder()
        .setResourcesVersion(RESOURCES)
        .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
        .build()
    return ResolvableFuture.create<TileBuilders.Tile>().apply { set(tile) }
  }

  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
    ResolvableFuture.create<ResourceBuilders.Resources>().apply { set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build()) }

  private companion object {
    const val RESOURCES = "1"
  }
}
