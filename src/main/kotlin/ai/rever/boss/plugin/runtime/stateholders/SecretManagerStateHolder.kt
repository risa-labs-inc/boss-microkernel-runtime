package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class SecretManagerState(
    val secrets: List<SecretEntry> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val selectedSecretId: String? = null,
    val error: String? = null,
)

@Serializable
data class SecretEntry(
    val id: String,
    val website: String,
    val username: String,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

// endregion

// region Intent

sealed class SecretManagerIntent {
    data class LoadSecrets(val limit: Int = 50, val offset: Int = 0) : SecretManagerIntent()
    data class SearchSecrets(val query: String) : SecretManagerIntent()
    data class SelectSecret(val secretId: String?) : SecretManagerIntent()
    data class DeleteSecret(val secretId: String) : SecretManagerIntent()
    data class SecretsLoaded(val secrets: List<SecretEntry>, val hasMore: Boolean) : SecretManagerIntent()
    data class SetLoading(val loading: Boolean) : SecretManagerIntent()
    data class SetError(val error: String?) : SecretManagerIntent()
}

// endregion

// region Effect

sealed class SecretManagerEffect {
    data class SecretDeleted(val secretId: String) : SecretManagerEffect()
    data class Error(val message: String) : SecretManagerEffect()
}

// endregion

/**
 * StateHolder for the Secret Manager plugin (covers secret-manager and user-secret-list panels).
 *
 * Based on [SecretDataProvider]. Manages a paginated, searchable list of secret entries.
 * Data update intents replace the secrets list or update loading/error flags.
 * Action intents (LoadSecrets, SearchSecrets, DeleteSecret) set local loading state and
 * are forwarded by the proxy bridge to the kernel via gRPC. Effects signal deletion
 * confirmations and errors back to the UI.
 */
class SecretManagerStateHolder :
    PluginStateHolder<SecretManagerState, SecretManagerIntent, SecretManagerEffect> {

    private val logger = LoggerFactory.getLogger(SecretManagerStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(SecretManagerState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(SecretManagerState(), scope) {
        val provider = context.secretDataProvider
        logger.info("SecretManagerStateHolder wiring SecretDataProvider and loading initial secrets")

        scope.launch {
            onIntent(SecretManagerIntent.SetLoading(true))
            provider.getUserSecrets().fold(
                onSuccess = { paginated ->
                    val converted = paginated.data.map { it.toEntry() }
                    onIntent(SecretManagerIntent.SecretsLoaded(converted, paginated.hasMore))
                },
                onFailure = { error ->
                    onIntent(SecretManagerIntent.SetError(error.message))
                }
            )
        }
    }

    private fun SecretEntryData.toEntry() = SecretEntry(
        id = id,
        website = website,
        username = username,
        notes = notes,
        tags = tags,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    override fun onIntent(intent: SecretManagerIntent) {
        when (intent) {
            is SecretManagerIntent.SecretsLoaded -> {
                updateState { copy(secrets = intent.secrets, hasMore = intent.hasMore, isLoading = false) }
            }

            is SecretManagerIntent.SetLoading -> {
                updateState { copy(isLoading = intent.loading) }
            }

            is SecretManagerIntent.SetError -> {
                updateState { copy(error = intent.error, isLoading = false) }
            }

            is SecretManagerIntent.SelectSecret -> {
                updateState { copy(selectedSecretId = intent.secretId) }
            }

            is SecretManagerIntent.SearchSecrets -> {
                updateState { copy(searchQuery = intent.query, isLoading = true) }
                // Action intent — proxy bridge layer performs search via gRPC.
            }

            is SecretManagerIntent.LoadSecrets -> {
                updateState { copy(isLoading = true, error = null) }
                // Action intent — proxy bridge layer loads secrets via gRPC.
            }

            is SecretManagerIntent.DeleteSecret -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer deletes the secret via gRPC.
            }
        }
    }
}
