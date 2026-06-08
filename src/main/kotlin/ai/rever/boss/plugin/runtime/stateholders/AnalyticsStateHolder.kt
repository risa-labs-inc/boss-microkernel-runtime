package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.ipc.proto.EventBusServiceGrpcKt
import ai.rever.boss.ipc.proto.EventEnvelope
import ai.rever.boss.ipc.proto.SubscribeRequest
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

// region State / Intent
//
// Mirrors the panel's view: the kernel renders the consent UI from this state and sends
// SetConsent back. State is also persisted to the shared config file so the headless
// pipeline and the panel agree regardless of which process renders the UI.

@Serializable
data class AnalyticsState(
    val consentEnabled: Boolean = true,
    val posthogConfigured: Boolean = false,
    val eventsSent: Long = 0,
)

sealed class AnalyticsIntent {
    data class SetConsent(val enabled: Boolean) : AnalyticsIntent()
    data class SetPostHogApiKey(val key: String) : AnalyticsIntent()
    data class SetPostHogHost(val host: String) : AnalyticsIntent()
}

// endregion

/**
 * Out-of-process analytics brain.
 *
 * Loaded by `PluginProcessMain` (via `plugin.json#stateHolderClass`) when the analytics
 * plugin runs out-of-process. It owns the full pipeline for the OOP path:
 *
 * 1. Subscribes to the kernel's [EventBusServiceGrpcKt] cross-process event stream over
 *    [RemotePluginContext.kernelChannel].
 * 2. Maps each [EventEnvelope] into a canonical analytics event name.
 * 3. Applies a mandatory PII scrub.
 * 4. Batches and POSTs to PostHog via the JDK HTTP client.
 *
 * Config (consent, PostHog key/host, anonymous id) is read from and written to the same
 * `~/.boss/analytics/config.json` the in-process plugin and the consent panel use, so
 * the two pipelines are interchangeable and the UI works in either process.
 *
 * NOTE: the scrubbing/dispatch logic here intentionally mirrors the canonical, unit-tested
 * implementation in the analytics plugin's `core/` package
 * (`ai.rever.boss.plugin.dynamic.analytics.core`). They are duplicated because the runtime
 * and the plugin jar are loaded by different classloaders; the clean follow-up is to
 * extract an `analytics-core` artifact both depend on. Keep the two in sync until then.
 */
class AnalyticsStateHolder :
    PluginStateHolder<AnalyticsState, AnalyticsIntent, Nothing> {

    private val logger = LoggerFactory.getLogger(AnalyticsStateHolder::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val configFile = File(System.getProperty("user.home") ?: ".", ".boss/analytics/config.json")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private var context: RemotePluginContext? = null
    @Volatile private var sent: Long = 0

    constructor(scope: CoroutineScope) : super(AnalyticsState(), scope) {
        bootstrap()
    }

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(AnalyticsState(), scope) {
        this.context = context
        bootstrap()
        startCollector(context)
    }

    override fun onIntent(intent: AnalyticsIntent) {
        when (intent) {
            is AnalyticsIntent.SetConsent -> mutateConfig { it.copy(consentEnabled = intent.enabled) }
            is AnalyticsIntent.SetPostHogApiKey -> mutateConfig { it.copy(posthogApiKey = intent.key.trim()) }
            is AnalyticsIntent.SetPostHogHost -> mutateConfig { it.copy(posthogHost = intent.host.trim()) }
        }
    }

    private fun bootstrap() {
        val cfg = loadConfig()
        updateState {
            copy(consentEnabled = effectiveConsent(cfg), posthogConfigured = effectiveApiKey(cfg).isNotBlank())
        }
    }

    private fun startCollector(ctx: RemotePluginContext) {
        val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(ctx.kernelChannel)
        val incoming = Channel<CanonicalEvent>(CAPACITY)

        // Producer: subscribe to the kernel event stream and feed mapped events into the
        // channel, reconnecting with backoff. Consent is gated at ingestion so a disabled
        // session buffers nothing.
        scope.launch {
            var delayMs = 1_000L
            while (isActive) {
                try {
                    val request = SubscribeRequest.newBuilder().setSubscriberId("analytics").build()
                    stub.subscribe(request).collect { envelope ->
                        if (!effectiveConsent(loadConfig())) return@collect
                        val event = mapEnvelope(envelope) ?: return@collect
                        incoming.trySend(event)
                    }
                    delayMs = 1_000L
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("EventBus subscription lost, reconnecting: {}", e.message)
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }
        }

        // Consumer: flush when BATCH_SIZE accumulates OR BATCH_INTERVAL_MS elapses (mirrors the
        // in-process AnalyticsDispatcher) so low-traffic sessions don't strand < BATCH_SIZE events.
        scope.launch {
            val buffer = ArrayList<CanonicalEvent>(BATCH_SIZE)
            while (isActive) {
                val event = if (buffer.isEmpty()) {
                    incoming.receiveCatching().getOrNull() ?: break
                } else {
                    select<CanonicalEvent?> {
                        incoming.onReceiveCatching { it.getOrNull() }
                        onTimeout(BATCH_INTERVAL_MS) { null }
                    }
                }
                if (event != null) {
                    buffer.add(event)
                    if (buffer.size < BATCH_SIZE) continue
                }
                if (buffer.isNotEmpty()) {
                    val cfg = loadConfig()
                    if (effectiveConsent(cfg)) flush(ArrayList(buffer), cfg, ctx)
                    buffer.clear()
                }
            }
            // Drain whatever is buffered on shutdown.
            if (buffer.isNotEmpty()) {
                val cfg = loadConfig()
                if (cfg.consentEnabled) flush(ArrayList(buffer), cfg, ctx)
            }
        }
    }

    // region Mapping + scrubbing (mirror of the plugin core)

    private data class CanonicalEvent(val name: String, val properties: Map<String, Any?>, val timestampMs: Long)

    /** Map a cross-process [EventEnvelope] to a canonical analytics event. */
    private fun mapEnvelope(envelope: EventEnvelope): CanonicalEvent? {
        val type = envelope.eventType.ifBlank { return null }
        // The bridged events are host component events (FileOpenEvent, URLOpenEvent,
        // PanelOpenEvent, …). We record only the event kind, never the JSON payload,
        // which can contain paths/URLs/titles.
        val name = "host." + type.removeSuffix("Event").replaceFirstChar { it.lowercase() }
        val ts = envelope.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        return CanonicalEvent(name = name, properties = emptyMap(), timestampMs = ts)
    }

    // endregion

    private suspend fun flush(batch: List<CanonicalEvent>, cfg: AnalyticsConfigDto, ctx: RemotePluginContext) {
        val apiKey = effectiveApiKey(cfg)
        if (apiKey.isBlank() || batch.isEmpty()) return
        val distinctId = resolveDistinctId(cfg, ctx)
        val payload = buildPayload(apiKey, batch, distinctId, anonymous = distinctId == cfg.anonymousId)
        val url = effectiveHost(cfg).trimEnd('/') + "/batch/"

        var attempt = 0
        var backoff = 1_000L
        while (true) {
            val ok = runCatching { post(url, payload) }.getOrDefault(false)
            if (ok) {
                sent += batch.size
                updateState { copy(eventsSent = sent) }
                return
            }
            if (++attempt > MAX_RETRIES) {
                logger.warn("Dropping analytics batch of {} after {} attempts", batch.size, attempt)
                return
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun post(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        when (response.statusCode()) {
            in 200..299 -> true
            in 400..499 -> { logger.warn("PostHog rejected batch ({})", response.statusCode()); true } // non-retryable
            else -> false
        }
    }

    private fun buildPayload(apiKey: String, batch: List<CanonicalEvent>, distinctId: String, anonymous: Boolean): String {
        val events = JsonArray(batch.map { e ->
            JsonObject(buildMap {
                put("event", JsonPrimitive(e.name))
                put("distinct_id", JsonPrimitive(distinctId))
                put("timestamp", JsonPrimitive(Instant.ofEpochMilli(e.timestampMs).toString()))
                put("properties", JsonObject(buildMap {
                    put("\$lib", JsonPrimitive("boss-analytics"))
                    put("boss_source", JsonPrimitive("HOST_EVENT"))
                    if (anonymous) put("\$process_person_profile", JsonPrimitive(false))
                }))
            })
        })
        return JsonObject(mapOf("api_key" to JsonPrimitive(apiKey), "batch" to events)).toString()
    }

    private fun resolveDistinctId(cfg: AnalyticsConfigDto, ctx: RemotePluginContext): String {
        val user = ctx.authDataProvider.currentUser.value
        return if (user != null && user.id.isNotBlank()) user.id else cfg.anonymousId
    }

    // region Config file (shared with the plugin/panel)

    @Serializable
    data class AnalyticsConfigDto(
        val consentEnabled: Boolean = true,
        val posthogApiKey: String = "",
        val posthogHost: String = "https://us.i.posthog.com",
        val anonymousId: String = "",
    )

    private fun loadConfig(): AnalyticsConfigDto {
        val base = runCatching {
            if (configFile.exists()) json.decodeFromString(AnalyticsConfigDto.serializer(), configFile.readText())
            else AnalyticsConfigDto()
        }.getOrDefault(AnalyticsConfigDto())
        return if (base.anonymousId.isBlank()) {
            base.copy(anonymousId = "anon-" + UUID.randomUUID()).also { persist(it) }
        } else base
    }

    private fun mutateConfig(transform: (AnalyticsConfigDto) -> AnalyticsConfigDto) {
        val next = transform(loadConfig())
        persist(next)
        updateState {
            copy(consentEnabled = effectiveConsent(next), posthogConfigured = effectiveApiKey(next).isNotBlank())
        }
    }

    private fun persist(cfg: AnalyticsConfigDto) {
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(json.encodeToString(AnalyticsConfigDto.serializer(), cfg))
        }
    }

    /**
     * Effective consent: the persisted toggle AND the env/system-property kill switch
     * (`BOSS_ANALYTICS_DISABLED` / `boss.analytics.disabled`). Mirrors the in-process
     * [ai.rever.boss.plugin.dynamic.analytics.config.AnalyticsConfig.withOverrides] so an
     * ops-level disable works identically in either process.
     */
    private fun effectiveConsent(cfg: AnalyticsConfigDto): Boolean = cfg.consentEnabled && !analyticsDisabled()

    private fun analyticsDisabled(): Boolean =
        (System.getenv("BOSS_ANALYTICS_DISABLED") ?: System.getProperty("boss.analytics.disabled"))
            ?.equals("true", ignoreCase = true) == true

    private fun effectiveApiKey(cfg: AnalyticsConfigDto): String =
        System.getenv("BOSS_ANALYTICS_POSTHOG_KEY")
            ?: System.getProperty("boss.analytics.posthog.key")
            ?: cfg.posthogApiKey

    private fun effectiveHost(cfg: AnalyticsConfigDto): String =
        System.getenv("BOSS_ANALYTICS_POSTHOG_HOST")
            ?: System.getProperty("boss.analytics.posthog.host")
            ?: cfg.posthogHost

    // endregion

    companion object {
        private const val BATCH_SIZE = 20
        private const val BATCH_INTERVAL_MS = 10_000L
        private const val MAX_RETRIES = 3
        private const val CAPACITY = 1_000
    }
}
