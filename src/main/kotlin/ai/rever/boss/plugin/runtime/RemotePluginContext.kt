package ai.rever.boss.plugin.runtime

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SettingsProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.ipc.ActiveTabsProviderProxy
import ai.rever.boss.plugin.ipc.AuthDataProviderProxy
import ai.rever.boss.plugin.ipc.ContextMenuProviderProxy
import ai.rever.boss.plugin.ipc.DirectoryPickerProviderProxy
import ai.rever.boss.plugin.ipc.DownloadDataProviderProxy
import ai.rever.boss.plugin.ipc.FileSystemDataProviderProxy
import ai.rever.boss.plugin.ipc.GitDataProviderProxy
import ai.rever.boss.plugin.ipc.LogDataProviderProxy
import ai.rever.boss.plugin.ipc.NotificationProviderProxy
import ai.rever.boss.plugin.ipc.PanelEventProviderProxy
import ai.rever.boss.plugin.ipc.PerformanceDataProviderProxy
import ai.rever.boss.plugin.ipc.ProjectDataProviderProxy
import ai.rever.boss.plugin.ipc.RoleManagementProviderProxy
import ai.rever.boss.plugin.ipc.RunConfigDataProviderProxy
import ai.rever.boss.plugin.ipc.SecretDataProviderProxy
import ai.rever.boss.plugin.ipc.SettingsProviderProxy
import ai.rever.boss.plugin.ipc.SplitViewOperationsProxy
import ai.rever.boss.plugin.ipc.SupabaseDataProviderProxy
import ai.rever.boss.plugin.ipc.WorkspaceDataProviderProxy
import ai.rever.boss.ipc.BossIpcServer
import io.grpc.BindableService
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Plugin context for out-of-process plugins running in a child JVM.
 *
 * Out-of-process plugins cannot access Compose-dependent host services directly.
 * This context provides:
 * - A dedicated coroutine scope tied to the plugin process lifecycle
 * - IPC proxy implementations for data providers that communicate with the kernel via gRPC
 * - Panel/tab registration forwarded to the kernel via [uiService]
 *
 * This class mirrors the shape of `ai.rever.boss.plugin.api.PluginContext` but does not
 * formally implement it, since PanelRegistry/TabRegistry require Compose + Decompose
 * which are not available in a pure-JVM plugin runtime process.
 *
 * Data providers are wired via IPC proxies that forward calls to kernel-side gRPC services.
 * Providers not yet wired return null — plugins should null-check before use.
 */
class RemotePluginContext(
    val processId: String,
    /**
     * The plugin's client of the kernel's `PluginUIService`.
     *
     * Named `uiService` still, because plugin code reads `ctx.uiService.uiEvents` and pushes trees
     * through it exactly as before — only the direction of the connection underneath changed.
     */
    val uiService: PluginUiClient,
    /**
     * Channel to the kernel's gRPC server. Exposed (not private) so OOP state holders
     * that talk to kernel services directly — e.g. the analytics holder subscribing to
     * [ai.rever.boss.ipc.proto.EventBusServiceGrpcKt] — can build their own stubs.
     */
    val kernelChannel: ManagedChannel,
    private val eventBusChannel: ManagedChannel? = null,
    /**
     * The gRPC server hosted by this plugin's child JVM. Plugins that
     * need to expose their own services (e.g. a terminal plugin hosting
     * `TerminalService`) call [addProcessService] to register them
     * before the server starts. Nullable so existing call sites that
     * construct [RemotePluginContext] without a server reference keep
     * compiling — those plugins simply can't add services.
     */
    private val processServer: BossIpcServer? = null,
) {
    private val logger = LoggerFactory.getLogger(RemotePluginContext::class.java)

    private val job: Job = SupervisorJob()

    /** Coroutine scope tied to this plugin process's lifecycle. Cancel via [dispose]. */
    val pluginScope: CoroutineScope = CoroutineScope(Dispatchers.Default + job)

    /** Current window ID — supplied via BOSS_WINDOW_ID environment variable. */
    val windowId: String? = System.getenv("BOSS_WINDOW_ID")

    /** Current project path — supplied via BOSS_PROJECT_PATH environment variable. */
    val projectPath: String? = System.getenv("BOSS_PROJECT_PATH")

    // ═══════════════════════════════════════════════════════════════════════════
    // Wired IPC Proxy Providers (connected to kernel gRPC services)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Authentication state provider — watches kernel auth state via gRPC stream. */
    val authDataProvider: AuthDataProvider = AuthDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** File system operations — data access via gRPC, UI triggers via EventBus. */
    val fileSystemDataProvider: FileSystemDataProvider? = eventBusChannel?.let { evtCh ->
        FileSystemDataProviderProxy(
            fsChannel = kernelChannel,
            eventChannel = evtCh,
            scope = pluginScope,
        )
    }

    /** Settings provider — opens settings dialog via EventBus. */
    val settingsProvider: SettingsProvider? = eventBusChannel?.let { evtCh ->
        SettingsProviderProxy(
            channel = evtCh,
            scope = pluginScope,
        )
    }

    /** Workspace data provider — watches workspace state via gRPC stream. */
    val workspaceDataProvider: WorkspaceDataProvider = WorkspaceDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 1 IPC Proxy Providers (connected to kernel gRPC services)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Performance metrics provider — watches JVM snapshots via gRPC stream. */
    val performanceDataProvider: PerformanceDataProvider = PerformanceDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Download management — tracks download status via gRPC stream. */
    val downloadDataProvider: DownloadDataProvider = DownloadDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Git operations — status, log, staging via gRPC. */
    val gitDataProvider: GitDataProvider = GitDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Log capture — streams captured application logs. */
    val logDataProvider: LogDataProvider = LogDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Active tabs — watches open tabs across windows. */
    val activeTabsProvider: ActiveTabsProvider = ActiveTabsProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Secret management — CRUD for stored credentials. */
    val secretDataProvider: SecretDataProvider = SecretDataProviderProxy(
        channel = kernelChannel,
    )

    /** Supabase proxy — delegated Postgrest and RPC calls. */
    val supabaseDataProvider: SupabaseDataProvider = SupabaseDataProviderProxy(
        channel = kernelChannel,
    )

    /** Split view operations — open tabs and workspaces in split panes. */
    val splitViewOperations: SplitViewOperations = SplitViewOperationsProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Context menu — register and display context menus. */
    val contextMenuProvider: ContextMenuProvider = ContextMenuProviderProxy(
        channel = kernelChannel,
    )

    /** Run configurations — detected main classes, tests, scripts. */
    val runConfigurationDataProvider: RunConfigurationDataProvider = RunConfigDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Panel events — close panel and lifecycle events. */
    val panelEventProvider: PanelEventProvider = PanelEventProviderProxy(
        channel = kernelChannel,
    )

    /** Role management — RBAC roles and permissions. */
    val roleManagementProvider: RoleManagementProvider = RoleManagementProviderProxy(
        channel = kernelChannel,
    )

    /** Directory picker — native OS directory selection dialog. */
    val directoryPickerProvider: DirectoryPickerProvider = DirectoryPickerProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Project data — recent projects management. */
    val projectDataProvider: ProjectDataProvider = ProjectDataProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    /** Notifications — toast messages to the user. */
    val notificationProvider: NotificationProvider = NotificationProviderProxy(
        channel = kernelChannel,
        scope = pluginScope,
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Not yet proxied — require Compose/host-specific capabilities
    // ═══════════════════════════════════════════════════════════════════════════

    val bookmarkDataProvider: Nothing? = null
    val userManagementProvider: Nothing? = null
    val pluginStoreApiKeyProvider: Nothing? = null
    val applicationEventBus: Nothing? = null
    val pluginStorageFactory: Nothing? = null
    val genericDialogProvider: Nothing? = null
    val clipboardProvider: Nothing? = null
    val filePickerProvider: Nothing? = null
    val backgroundTaskProvider: Nothing? = null
    val diagnosticProvider: Nothing? = null
    val browserService: Nothing? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // Panel / tab registration (forwarded to kernel via IPC)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Register a panel UI surface with the kernel.
     *
     * The plugin sends its widget tree via [uiService]; the kernel renders it in the
     * appropriate panel slot. Call this before sending any WidgetUpdates for the surface.
     *
     * Returns as soon as the claim is queued — registration is a round trip to the kernel now, so
     * an update pushed immediately after is buffered until the stream for this surface opens.
     * Supplying [initialTree] means the surface renders from the moment the kernel accepts it,
     * instead of showing empty until the plugin's first push.
     */
    fun registerPanel(
        surfaceId: String,
        displayName: String,
        iconName: String = "",
        defaultSlot: String = "",
        initialTree: ai.rever.boss.ipc.proto.WidgetTree? = null,
    ) {
        logger.info("Registering panel: id={}, name={}, slot={}", surfaceId, displayName, defaultSlot)
        val registration = ai.rever.boss.ipc.proto.UIRegistration.newBuilder()
            .setSurfaceId(surfaceId)
            .setSurfaceType("panel")
            .setDisplayName(displayName)
            .setIconName(iconName)
            .setProcessId(processId)
            .setDefaultSlot(defaultSlot)
            .apply { initialTree?.let { setInitialTree(it) } }
            .build()
        uiService.registerSurface(registration)
    }

    /**
     * Register a tab type with the kernel via IPC.
     */
    fun registerTabType(
        surfaceId: String,
        displayName: String,
        initialTree: ai.rever.boss.ipc.proto.WidgetTree? = null,
    ) {
        logger.info("Registering tab type: id={}, name={}", surfaceId, displayName)
        val registration = ai.rever.boss.ipc.proto.UIRegistration.newBuilder()
            .setSurfaceId(surfaceId)
            .setSurfaceType("tab")
            .setDisplayName(displayName)
            .setProcessId(processId)
            .apply { initialTree?.let { setInitialTree(it) } }
            .build()
        uiService.registerSurface(registration)
    }

    /**
     * Register a custom gRPC service on this plugin's child-process server.
     *
     * Plugins that expose their own contract (e.g. a terminal plugin
     * hosting `TerminalService`, an editor plugin hosting an LSP-bridge
     * service, etc.) call this from `registerRemote(ctx)` before
     * [PluginProcessMain] starts the server. The service becomes
     * reachable by the host on this process's IPC address.
     *
     * Throws [IllegalStateException] if this context was constructed
     * without a server reference (e.g. unit-test fixtures).
     */
    fun addProcessService(service: BindableService) {
        val server = processServer
            ?: throw IllegalStateException(
                "addProcessService called on a RemotePluginContext without a process server. " +
                    "This usually means the context was built outside PluginProcessMain — " +
                    "custom services can only be registered in OOP plugin child JVMs."
            )
        logger.info(
            "Registering custom process service: {}",
            service.bindService().serviceDescriptor.name,
        )
        server.addService(service)
    }

    /**
     * Dispose this context and cancel all coroutines in [pluginScope].
     * Call when the plugin process is shutting down.
     */
    fun dispose() {
        logger.info("RemotePluginContext disposed for process: {}", processId)
        // Before cancelling the scope: the UI streams run on it, and ending them is what tells the
        // kernel these surfaces are gone.
        uiService.close()
        job.cancel()
    }
}
