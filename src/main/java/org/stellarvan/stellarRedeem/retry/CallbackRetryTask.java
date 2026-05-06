package org.stellarvan.stellarRedeem.retry;

import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ApiClientException;

public final class CallbackRetryTask {
    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final RedeemApiClient apiClient;
    private final CallbackRetryQueue queue;
    private final Consumer<String> debugLogger;
    private BukkitTask task;

    public CallbackRetryTask(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            RedeemApiClient apiClient,
            CallbackRetryQueue queue,
            Consumer<String> debugLogger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.apiClient = apiClient;
        this.queue = queue;
        this.debugLogger = debugLogger;
    }

    public void start() {
        if (!pluginConfig.callbackRetry().enabled()) {
            return;
        }
        if (!queue.isStorageReady()) {
            plugin.getLogger().warning("Callback retry task not started because queue file is not ready.");
            return;
        }
        if (task != null) {
            return;
        }

        long intervalTicks = Math.max(20L, Math.max(1, pluginConfig.callbackRetry().intervalSeconds()) * 20L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        int maxAttempts = Math.max(1, pluginConfig.callbackRetry().maxAttempts());
        List<PendingCallback> snapshot = queue.snapshot();
        for (PendingCallback callback : snapshot) {
            if (callback.isExhausted(maxAttempts)) {
                continue;
            }

            boolean success = sendCallback(callback);
            if (success) {
                queue.remove(callback);
                debug("callback retry success: type=" + callback.getType() + ", redeemId=" + callback.getRedeemId());
                continue;
            }

            queue.markRetryFailed(callback);
            if (callback.isExhausted(maxAttempts)) {
                plugin.getLogger().warning(
                        "Callback retry exhausted for redeemId " + callback.getRedeemId() + " after " + callback.getAttempts() + " attempts."
                );
            }
        }
    }

    private boolean sendCallback(PendingCallback callback) {
        try {
            if (callback.getType() == PendingCallback.CallbackType.COMPLETE) {
                apiClient.sendComplete(callback.getRedeemId(), callback.getExecutedCommands());
            } else {
                apiClient.sendFail(
                        callback.getRedeemId(),
                        callback.getFailedCommand(),
                        callback.getError(),
                        callback.getExecutedCommands()
                );
            }
            return true;
        } catch (ApiClientException ex) {
            debug(
                    "callback retry failed: type="
                            + callback.getType()
                            + ", redeemId="
                            + callback.getRedeemId()
                            + ", attempts="
                            + callback.getAttempts()
                            + ", error="
                            + ex.getMessage()
            );
            return false;
        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "Unexpected callback retry failure for redeemId "
                            + callback.getRedeemId()
                            + ": "
                            + ex.getMessage()
            );
            return false;
        }
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.accept(message);
        }
    }
}
