package org.stellarvan.stellarRedeem.task;

import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ApiClientException;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.HeartbeatPayload;

public final class HeartbeatTask {
    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final RedeemApiClient apiClient;
    private final Consumer<String> debugLogger;
    private BukkitTask task;

    public HeartbeatTask(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            RedeemApiClient apiClient,
            Consumer<String> debugLogger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.apiClient = apiClient;
        this.debugLogger = debugLogger;
    }

    public void start() {
        if (!pluginConfig.heartbeat().enabled()) {
            return;
        }
        if (task != null) {
            return;
        }

        long intervalTicks = Math.max(20L, Math.max(1, pluginConfig.heartbeat().intervalSeconds()) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void sendOnceAsync(Runnable onSuccess, Consumer<String> onFailure) {
        HeartbeatPayload payload = buildPayload();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                apiClient.sendHeartbeat(payload);
                debug("heartbeat success");
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (ApiClientException ex) {
                plugin.getLogger().severe("Heartbeat request failed: " + ex.getMessage());
                debug("heartbeat failed: " + ex.getMessage());
                if (onFailure != null) {
                    onFailure.accept(ex.getMessage());
                }
            }
        });
    }

    private void tick() {
        sendOnceAsync(null, null);
    }

    private HeartbeatPayload buildPayload() {
        String serverVersion;
        try {
            serverVersion = Bukkit.getName() + " " + Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError ignored) {
            serverVersion = Bukkit.getName() + " " + Bukkit.getBukkitVersion();
        }

        return new HeartbeatPayload(
                pluginConfig.api().serverId(),
                "StellarRedeem",
                plugin.getDescription().getVersion(),
                serverVersion,
                Bukkit.getOnlinePlayers().size()
        );
    }

    private void debug(String message) {
        if (debugLogger != null) {
            debugLogger.accept(message);
        }
    }
}
