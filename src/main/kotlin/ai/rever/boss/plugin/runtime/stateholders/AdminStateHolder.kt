package ai.rever.boss.plugin.runtime.stateholders

import ai.rever.boss.plugin.api.PermissionInfoData
import ai.rever.boss.plugin.api.RoleInfoData
import ai.rever.boss.plugin.runtime.PluginStateHolder
import ai.rever.boss.plugin.runtime.RemotePluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// region State

@Serializable
data class AdminState(
    val roles: List<AdminRole> = emptyList(),
    val permissions: List<AdminPermission> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedRoleName: String? = null,
    val rolePermissions: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class AdminRole(
    val id: String,
    val name: String,
    val description: String? = null,
    val permissions: List<String> = emptyList(),
    val createdAt: Long = 0,
    val isSystem: Boolean = false,
)

@Serializable
data class AdminPermission(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long = 0,
    val isSystem: Boolean = false,
)

// endregion

// region Intent

sealed class AdminIntent {
    object LoadRoles : AdminIntent()
    object LoadPermissions : AdminIntent()
    data class CreateRole(val name: String, val description: String?) : AdminIntent()
    data class CreatePermission(val name: String, val description: String?) : AdminIntent()
    data class DeleteRole(val roleName: String) : AdminIntent()
    data class DeletePermission(val permissionName: String) : AdminIntent()
    data class AssignPermission(val roleName: String, val permissionName: String) : AdminIntent()
    data class RemovePermission(val roleName: String, val permissionName: String) : AdminIntent()
    data class SelectRole(val roleName: String?) : AdminIntent()
    data class RolesLoaded(val roles: List<AdminRole>) : AdminIntent()
    data class PermissionsLoaded(val permissions: List<AdminPermission>) : AdminIntent()
    data class RolePermissionsLoaded(val roleName: String, val permissions: List<String>) : AdminIntent()
    data class SetLoading(val loading: Boolean) : AdminIntent()
    data class SetError(val error: String?) : AdminIntent()
}

// endregion

// region Effect

sealed class AdminEffect {
    data class RoleCreated(val role: AdminRole) : AdminEffect()
    data class PermissionCreated(val permission: AdminPermission) : AdminEffect()
    data class Error(val message: String) : AdminEffect()
}

// endregion

/**
 * StateHolder for the Admin panel plugin (covers admin-role-management and role-creation).
 *
 * Manages RBAC roles and permissions with CRUD operations. Data update intents
 * ([AdminIntent.RolesLoaded], [AdminIntent.PermissionsLoaded], [AdminIntent.RolePermissionsLoaded])
 * replace their respective state slices. Action intents (LoadRoles, LoadPermissions,
 * CreateRole, CreatePermission, DeleteRole, DeletePermission, AssignPermission, RemovePermission)
 * set loading flags and are forwarded by the proxy bridge to the kernel via gRPC.
 * Effects signal creation confirmations and errors.
 */
class AdminStateHolder :
    PluginStateHolder<AdminState, AdminIntent, AdminEffect> {

    private val logger = LoggerFactory.getLogger(AdminStateHolder::class.java)

    constructor(scope: CoroutineScope) : super(AdminState(), scope)

    constructor(scope: CoroutineScope, context: RemotePluginContext) : super(AdminState(), scope) {
        val provider = context.roleManagementProvider
        logger.info("AdminStateHolder wiring RoleManagementProvider and loading initial roles/permissions")

        // Track both loads independently to avoid premature isLoading=false
        val pendingLoads = java.util.concurrent.atomic.AtomicInteger(2)
        onIntent(AdminIntent.SetLoading(true))

        scope.launch {
            provider.getAllRoles().fold(
                onSuccess = { roles ->
                    val converted = roles.map { it.toRole() }
                    onIntent(AdminIntent.RolesLoaded(converted))
                },
                onFailure = { error ->
                    onIntent(AdminIntent.SetError(error.message))
                }
            )
            if (pendingLoads.decrementAndGet() == 0) {
                onIntent(AdminIntent.SetLoading(false))
            }
        }

        scope.launch {
            provider.getAllPermissions().fold(
                onSuccess = { permissions ->
                    val converted = permissions.map { it.toPermission() }
                    onIntent(AdminIntent.PermissionsLoaded(converted))
                },
                onFailure = { error ->
                    onIntent(AdminIntent.SetError(error.message))
                }
            )
            if (pendingLoads.decrementAndGet() == 0) {
                onIntent(AdminIntent.SetLoading(false))
            }
        }
    }

    private fun RoleInfoData.toRole() = AdminRole(
        id = id,
        name = name,
        description = description,
        permissions = permissions,
        createdAt = createdAt,
        isSystem = isSystem,
    )

    private fun PermissionInfoData.toPermission() = AdminPermission(
        id = id,
        name = name,
        description = description,
        createdAt = createdAt,
        isSystem = isSystem,
    )

    override fun onIntent(intent: AdminIntent) {
        when (intent) {
            // Data update intents
            is AdminIntent.RolesLoaded -> {
                updateState { copy(roles = intent.roles) }
            }

            is AdminIntent.PermissionsLoaded -> {
                updateState { copy(permissions = intent.permissions) }
            }

            is AdminIntent.RolePermissionsLoaded -> {
                updateState {
                    copy(rolePermissions = rolePermissions + (intent.roleName to intent.permissions))
                }
            }

            is AdminIntent.SetLoading -> {
                updateState { copy(isLoading = intent.loading) }
            }

            is AdminIntent.SetError -> {
                updateState { copy(error = intent.error, isLoading = false) }
            }

            is AdminIntent.SelectRole -> {
                updateState { copy(selectedRoleName = intent.roleName) }
            }

            // Action intents
            is AdminIntent.LoadRoles -> {
                updateState { copy(isLoading = true, error = null) }
                // Action intent — proxy bridge layer loads roles via gRPC.
            }

            is AdminIntent.LoadPermissions -> {
                updateState { copy(isLoading = true, error = null) }
                // Action intent — proxy bridge layer loads permissions via gRPC.
            }

            is AdminIntent.CreateRole -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer creates the role via gRPC.
            }

            is AdminIntent.CreatePermission -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer creates the permission via gRPC.
            }

            is AdminIntent.DeleteRole -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer deletes the role via gRPC.
            }

            is AdminIntent.DeletePermission -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer deletes the permission via gRPC.
            }

            is AdminIntent.AssignPermission -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer assigns the permission via gRPC.
            }

            is AdminIntent.RemovePermission -> {
                updateState { copy(isLoading = true) }
                // Action intent — proxy bridge layer removes the permission via gRPC.
            }
        }
    }
}
