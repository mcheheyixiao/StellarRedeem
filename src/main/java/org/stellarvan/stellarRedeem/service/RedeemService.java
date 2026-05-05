package org.stellarvan.stellarRedeem.service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ApiClientException;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ClaimRequest;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ClaimResult;
import org.stellarvan.stellarRedeem.service.CommandTemplateExecutor.ExecutionResult;

public final class RedeemService {
    private static final Set<String> INVALID_REASONS = Set.of(
            "invalid_code",
            "expired_code",
            "used_code",
            "already_used"
    );

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final CooldownService cooldownService;
    private final RedeemApiClient apiClient;
    private final CommandTemplateExecutor commandExecutor;
    private final boolean redeemEnabled;

    public RedeemService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            CooldownService cooldownService,
            RedeemApiClient apiClient,
            CommandTemplateExecutor commandExecutor,
            boolean redeemEnabled
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.cooldownService = cooldownService;
        this.apiClient = apiClient;
        this.commandExecutor = commandExecutor;
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
        String worldName = player.getWorld().getName();

        player.sendMessage(pluginConfig.messages().processing());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> executeRedeem(
                playerUuid,
                playerName,
                worldName,
                normalizedCode
        ));
    }

    private void executeRedeem(UUID playerUuid, String playerName, String worldName, String code) {
        ClaimResult claimResult;
        try {
            claimResult = apiClient.claimCode(new ClaimRequest(
                    code,
                    pluginConfig.api().serverId(),
                    playerName,
                    playerUuid.toString(),
                    worldName
            ));
        } catch (ApiClientException ex) {
            plugin.getLogger().severe(
                    "Claim API request failed for player " + playerName + ", code " + maskCode(code) + ": " + ex.getMessage()
            );
            Bukkit.getScheduler().runTask(plugin, () -> sendToOnlinePlayer(playerUuid, pluginConfig.messages().apiError()));
            return;
        }

        if (!claimResult.success()) {
            String failureMessage = resolveClaimFailureMessage(claimResult);
            Bukkit.getScheduler().runTask(plugin, () -> sendToOnlinePlayer(playerUuid, failureMessage));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ExecutionResult executionResult = commandExecutor.execute(
                    playerName,
                    playerUuid.toString(),
                    worldName,
                    claimResult.commands()
            );

            if (executionResult.success()) {
                sendToOnlinePlayer(playerUuid, pluginConfig.messages().success());
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        apiClient.sendComplete(claimResult.redeemId(), executionResult.executedCommands());
                    } catch (ApiClientException ex) {
                        plugin.getLogger().severe(
                                "Complete callback failed for redeemId " + claimResult.redeemId() + ": " + ex.getMessage()
                        );
                    }
                });
            } else {
                sendToOnlinePlayer(playerUuid, pluginConfig.messages().failed());
                plugin.getLogger().severe(
                        "Redeem command failed for redeemId "
                                + claimResult.redeemId()
                                + ", command: "
                                + executionResult.failedCommand()
                                + ", error: "
                                + executionResult.error()
                );
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        apiClient.sendFail(
                                claimResult.redeemId(),
                                executionResult.failedCommand(),
                                executionResult.error(),
                                executionResult.executedCommands()
                        );
                    } catch (ApiClientException ex) {
                        plugin.getLogger().severe(
                                "Fail callback failed for redeemId " + claimResult.redeemId() + ": " + ex.getMessage()
                        );
                    }
                });
            }
        });
    }

    private String resolveClaimFailureMessage(ClaimResult claimResult) {
        if (claimResult.reason() != null && INVALID_REASONS.contains(claimResult.reason())) {
            return pluginConfig.messages().invalid();
        }

        if (claimResult.message() != null && !claimResult.message().isBlank()) {
            return ChatColor.translateAlternateColorCodes('&', claimResult.message());
        }
        return pluginConfig.messages().failed();
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
}
