package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class TopOfMindState(
    val tabs: List<TopOfMindTab> = emptyList(),
    val groupByWorkspace: Boolean = true,
)

@Serializable
data class TopOfMindTab(
    val tabId: String,
    val typeId: String,
    val title: String,
    val workspaceId: String,
    val workspaceName: String,
    val panelId: String,
    val windowId: String,
    val url: String? = null,
)

// endregion

// region Intent

sealed class TopOfMindIntent {
    data class TabsUpdated(val tabs: List<TopOfMindTab>) : TopOfMindIntent()
    data class SelectTab(val tabId: String, val panelId: String) : TopOfMindIntent()
    data class CloseTab(val tabId: String) : TopOfMindIntent()
    object ToggleGrouping : TopOfMindIntent()
    object RefreshTabs : TopOfMindIntent()
}

// endregion

/**
 * StateHolder for the Top-of-Mind panel plugin.
 *
 * Shows open tabs organized by workspace, based on [ActiveTabsProvider].
 * Data update intents ([TopOfMindIntent.TabsUpdated]) replace the full tab list.
 * Action intents (SelectTab, CloseTab, RefreshTabs) are forwarded by the proxy bridge
 * to the kernel via gRPC.
 */
class TopOfMindStateHolder :
    PluginStateHolder<TopOfMindState, TopOfMindIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(TopOfMindStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(TopOfMindState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(TopOfMindState(), scope) {
        val provider = context.activeTabsProvider
        logger.info("TopOfMindStateHolder wiring ActiveTabsProvider")

        scope.launch {
            provider.activeTabs.collect { tabs ->
                val converted = tabs.map { it.toTab() }
                onIntent(TopOfMindIntent.TabsUpdated(converted))
            }
        }
    }

    private fun ActiveTabData.toTab() = TopOfMindTab(
        tabId = tabId,
        typeId = typeId,
        title = title,
        workspaceId = workspaceId,
        workspaceName = workspaceName,
        panelId = panelId,
        windowId = windowId,
        url = url,
    )

    override fun onIntent(intent: TopOfMindIntent) {
        when (intent) {
            is TopOfMindIntent.TabsUpdated -> {
                updateState { copy(tabs = intent.tabs) }
            }

            is TopOfMindIntent.ToggleGrouping -> {
                updateState { copy(groupByWorkspace = !groupByWorkspace) }
            }

            is TopOfMindIntent.CloseTab -> {
                updateState { copy(tabs = tabs.filter { it.tabId != intent.tabId }) }
                // Action intent — proxy bridge layer closes the tab via the kernel.
            }

            is TopOfMindIntent.SelectTab -> {
                // Action intent — proxy bridge layer activates the tab in the kernel.
            }

            is TopOfMindIntent.RefreshTabs -> {
                // Action intent — proxy bridge layer requests a tab refresh from the kernel.
            }
        }
    }
}
