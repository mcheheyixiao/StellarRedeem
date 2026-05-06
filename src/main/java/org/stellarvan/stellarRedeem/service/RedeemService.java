package org.stellarvan.stellarRedeem.service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ApiClientException;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ClaimRequest;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ClaimResult;
import org.stellarvan.stellarRedeem.retry.CallbackRetryQueue;
import org.stellarvan.stellarRedeem.retry.PendingCallback;
import org.stellarvan.stellarRedeem.service.CommandTemplateExecutor.ExecutionResult;

public final class RedeemService {
    private static final Set<String> INVALID_REASONS = Set.of(
            "invalid_code",
            "revoked",
            "expired",
            "used_up",
            "category_disabled",
            "server_not_allowed",
            "player_not_allowed",
            "bound_account_required",
            "email_not_verified",
            "account_not_active",
            "per_player_limit_reached",
            "per_account_limit_reached"
    );
    private static final Set<String> API_ERROR_REASONS = Set.of(
            "rule_invalid",
            "server_auth_failed",
            "internal_error"
    );

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final CooldownService cooldownService;
    private final RedeemApiClient apiClient;
    private final CommandTemplateExecutor commandExecutor;
    private final CallbackRetryQueue callbackRetryQueue;
    private final Consumer<String> debugLogger;
    private final boolean redeemEnabled;

    public RedeemService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            CooldownService cooldownService,
            RedeemApiClient apiClient,
            CommandTemplateExecutor commandExecutor,
            CallbackRetryQueue callbackRetryQueue,
            Consumer<String> debugLogger,
            boolean redeemEnabled
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.cooldownService = cooldownService;
        this.apiClient = apiClient;
        this.commandExecutor = commandExecutor;
        this.callbackRetryQueue = callbackRetryQueue;
        this.debugLogger = debugLogger;
        this.redeemEnabled = redeemEnabled;
    }

    public void redeem(Player player, String rawCode) {
        if (!redeemEnabled) {
            player.sendMessage(pluginConfig.messages().apiError());
            return;
        }

        boolean bypassCooldown = player.hasPermission("stellarredeem.admin");
        if (!bypassCooldown) {
            long remaining = cooldownService.getRemainingSeconds(
                    player.getUniqueId(),
                    Math.max(0, pluginConfig.redeem().cooldownSeconds())
            );
            if (remaining > 0) {
                player.sendMessage(pluginConfig.messages().cooldownWithSeconds(remaining));
                return;
            }
            cooldownService.markUsed(player.getUniqueId());
        }

        String normalizedCode = normalizeCode(rawCode);
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        String executionWorldName = player.getWorld().getName();
        PluginConfig.Context context = pluginConfig.context();
        String claimWorld = context.includeWorld() ? executionWorldName : "";
        String serverVersion = context.includeServerVersion() ? resolveServerVersion() : null;
        Integer onlinePlayers = context.includeOnlinePlayers() ? Bukkit.getOnlinePlayers().size() : null;
        String playerIp = context.includePlayerIp() ? resolvePlayerIp(player) : null;

        debug("claim request start: player=" + playerName + ", code=" + maskCode(normalizedCode));
        debug(
                "claim context included: world="
                        + context.includeWorld()
                        + ", serverVersion="
                        + context.includeServerVersion()
                        + ", onlinePlayers="
                        + context.includeOnlinePlayers()
                        + ", playerIp="
                        + context.includePlayerIp()
        );
        player.sendMessage(pluginConfig.messages().processing());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> executeRedeem(
                playerUuid,
                playerName,
                executionWorldName,
                claimWorld,
                serverVersion,
                onlinePlayers,
                playerIp,
                normalizedCode
        ));
    }

    private void executeRedeem(
            UUID playerUuid,
            String playerName,
            String executionWorldName,
            String claimWorld,
            String serverVersion,
            Integer onlinePlayers,
            String playerIp,
            String code
    ) {
        ClaimResult claimResult;
        try {
            claimResult = apiClient.claimCode(new ClaimRequest(
                    code,
                    pluginConfig.api().serverId(),
                    playerName,
                    playerUuid.toString(),
                    claimWorld,
                    serverVersion,
                    onlinePlayers,
                    playerIp
            ));
        } catch (ApiClientException ex) {
            debug("claim failed with api error: player=" + playerName + ", code=" + maskCode(code) + ", error=" + ex.getMessage());
            plugin.getLogger().severe(
                    "Claim API request failed for player " + playerName + ", code " + maskCode(code) + ": " + ex.getMessage()
            );
            Bukkit.getScheduler().runTask(plugin, () -> sendToOnlinePlayer(playerUuid, pluginConfig.messages().apiError()));
            return;
        }

        if (!claimResult.success()) {
            debug("claim rejected reason=" + claimResult.reason() + ", player=" + playerName);
            String failureMessage = resolveClaimFailureMessage(claimResult);
            Bukkit.getScheduler().runTask(plugin, () -> sendToOnlinePlayer(playerUuid, failureMessage));
            return;
        }

        long redeemId = claimResult.redeemId() == null ? -1L : claimResult.redeemId();
        debug("claim success: player=" + playerName + ", redeemId=" + redeemId);

        Bukkit.getScheduler().runTask(plugin, () -> {
            ExecutionResult executionResult = commandExecutor.execute(
                    playerName,
                    playerUuid.toString(),
                    executionWorldName,
                    claimResult.commands()
            );

            if (executionResult.success()) {
                sendToOnlinePlayer(playerUuid, pluginConfig.messages().success());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        apiClient.sendComplete(redeemId, executionResult.executedCommands());
                    } catch (ApiClientException ex) {
                        plugin.getLogger().severe(
                                "Complete callback failed for redeemId " + redeemId + ": " + ex.getMessage()
                        );
                        queueCallback(PendingCallback.complete(redeemId, executionResult.executedCommands()));
                    }
                });
            } else {
                sendToOnlinePlayer(playerUuid, pluginConfig.messages().failed());
                plugin.getLogger().severe(
                        "Redeem command failed for redeemId "
                                + redeemId
                                + ", command: "
                                + executionResult.failedCommand()
                                + ", error: "
                                + executionResult.error()
                );
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        apiClient.sendFail(
                                redeemId,
                                executionResult.failedCommand(),
                                executionResult.error(),
                                executionResult.executedCommands()
                        );
                    } catch (ApiClientException ex) {
                        plugin.getLogger().severe(
                                "Fail callback failed for redeemId " + redeemId + ": " + ex.getMessage()
                        );
                        queueCallback(PendingCallback.fail(
                                redeemId,
                                executionResult.failedCommand(),
                                executionResult.error(),
                                executionResult.executedCommands()
                        ));
                    }
                });
            }
        });
    }

    private void queueCallback(PendingCallback callback) {
        if (callbackRetryQueue == null) {
            return;
        }

        boolean queued = callbackRetryQueue.enqueue(callback);
        if (queued) {
            plugin.getLogger().warning(
                    "Callback queued for retry: type=" + callback.getType() + ", redeemId=" + callback.getRedeemId()
            );
            debug("callback queued: type=" + callback.getType() + ", redeemId=" + callback.getRedeemId());
        } else {
            plugin.getLogger().warning(
                    "Callback queue rejected callback: type=" + callback.getType() + ", redeemId=" + callback.getRedeemId()
            );
        }
    }

    private String resolveClaimFailureMessage(ClaimResult claimResult) {
        String reason = claimResult.reason() == null ? null : claimResult.reason().toLowerCase(Locale.ROOT);
        if (reason != null && API_ERROR_REASONS.contains(reason)) {
            return pluginConfig.messages().apiError();
        }

        if (claimResult.message() != null && !claimResult.message().isBlank()) {
            return ChatColor.translateAlternateColorCodes('&', claimResult.message());
        }

        if (reason != null && INVALID_REASONS.contains(reason)) {
            return pluginConfig.messages().invalid();
        }
        return pluginConfig.messages().failed();
    }

    private String resolveServerVersion() {
        try {
            return Bukkit.getName() + " " + Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError ignored) {
            return Bukkit.getName() + " " + Bukkit.getBukkitVersion();
        }
    }

    private String resolvePlayerIp(Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private void sendToOnlinePlayer(UUID uuid, String message) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            onlinePlayer.sendMessage(message);
        }
    }

    private String normalizeCode(String code) {
        String normalized = code.trim();
        if (pluginConfig.redeem().caseInsensitive()) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    private String maskCode(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.accept(message);
        }
    }
}
