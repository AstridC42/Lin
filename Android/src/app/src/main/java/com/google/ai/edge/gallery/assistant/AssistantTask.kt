/*
 * Copyright 2026 The Star Storyteller Authors
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

package com.google.ai.edge.gallery.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.PromptExpander
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.customtasks.agentchat.SkillManagerViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.search.SearxngClient
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.skills.formatSelectedSkills
import com.google.ai.edge.litertlm.Contents
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The unified assistant capability: one chat screen that covers everything Edge Gallery previously
 * split across separate screens, plus web search and device actions.
 *
 * The task keeps the same shape as the other built-in tasks so the rest of the app - the model
 * manager, chat history, benchmarks, the capability gallery - works with it unchanged. What is
 * different is that it is also the app's start destination (see
 * [com.google.ai.edge.gallery.ui.navigation.GalleryNavHost]) and that every general-purpose LLM in
 * the allowlist is attached to it, so any model can be loaded straight from the chat screen.
 */
class AssistantTask(
  private val context: Context,
  private val skillsProvider: SkillsProvider,
  private val agentTools: AgentTools,
  private val executor: AgentRuntimeExecutor,
  private val dataStoreRepository: DataStoreRepository,
  private val searxngClient: SearxngClient,
) : CustomTask {

  override val task: Task by lazy {
    Task(
      id = BuiltInTaskId.ASSISTANT,
      label = context.getString(R.string.task_label_assistant),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.gemini_star,
      models = mutableListOf(),
      description = context.getString(R.string.task_desc_assistant),
      shortDescription = context.getString(R.string.task_short_desc_assistant),
      sourceCodeUrl =
        "https://github.com/AstridC42/Star-Storyteller-/tree/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/assistant",
      textInputPlaceHolderRes = R.string.assistant_input_placeholder,
      defaultSystemPrompt = "",
      useThemeColor = true,
    )
  }

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.Default) {
      // Skills and MCP servers have to be loaded before the prompt can list them.
      val skillsJob = launch {
        agentTools.skillsProvider.loadSkills(SkillManagerViewModel.DEFAULT_DISABLED_SKILLS)
      }
      val mcpJob = launch { agentTools.mcpManagerViewModel.loadMcpServers() }
      skillsJob.join()
      mcpJob.join()

      val toolsPrompt = agentTools.mcpManagerViewModel.getToolsPrompt()
      val finalSystemPrompt =
        buildSystemPrompt(
          currentPrompt = systemInstruction?.toString().orEmpty(),
          toolsPrompt = toolsPrompt,
          availableSkills = formatSelectedSkills(skillsProvider.getAvailableSkills()),
        )

      val config =
        AgentRuntimeConfig(
          model = model,
          taskId = task.id,
          actionChannel = agentTools.sendActionChannel,
          // The assistant always accepts every kind of attachment the model can handle, whatever
          // mode it is in - that is the whole point of merging the screens.
          supportImage = model.llmSupportImage,
          supportAudio = model.llmSupportAudio,
          enableConversationConstrainedDecoding = true,
          systemInstruction = finalSystemPrompt,
        )

      executor.initialize(context = context, config = config, onDone = onDone)
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    executor.cleanUp(onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AssistantScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      dataStoreRepository = dataStoreRepository,
      searxngClient = searxngClient,
      initialQuery = myData.initialQuery,
      initialImageUris = myData.initialImageUris,
      onOpenGallery = myData.onOpenGallery,
    )
  }

  /**
   * Expands the stored prompt into the instruction actually handed to the model.
   *
   * @param currentPrompt what the system prompt repository has for this task; empty on a fresh
   *   install and replaced wholesale whenever it is still the generated one.
   */
  private fun buildSystemPrompt(
    currentPrompt: String,
    toolsPrompt: String,
    availableSkills: String,
  ): String {
    val settings = dataStoreRepository.readAssistantSettings()
    val template =
      AssistantPrompts.resolve(
        currentPrompt = currentPrompt,
        mode = AssistantMode.fromId(settings.modeId),
        hasMcpTools = toolsPrompt.isNotEmpty(),
        webSearchEnabled = searxngClient.isConfigured(),
        webSearchInstance = searxngClient.instanceUrlOrNull()?.let { hostOf(it) }.orEmpty(),
      )
    return PromptExpander()
      .formatSystemInstructions(
        template = template,
        substitutions =
          mapOf(
            AssistantPrompts.SKILLS_PLACEHOLDER to availableSkills,
            AssistantPrompts.TOOLS_PLACEHOLDER to toolsPrompt,
          ),
      )
  }

  companion object {
    /** The host of [url], or the url itself when it cannot be parsed. */
    fun hostOf(url: String): String =
      try {
        URL(url).host.ifEmpty { url }
      } catch (e: Exception) {
        url
      }
  }
}
