package org.stellarvan.stellarRedeem.task;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.ApiClientException;
import org.stellarvan.stellarRedeem.http.RedeemApiClient.HeartbeatPayload;

public final class HeartbeatTask {
    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final RedeemApiClient apiClient;
    private int taskId = -1;

    public HeartbeatTask(JavaPlugin plugin, PluginConfig pluginConfig, RedeemApiClient apiClient) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.apiClient = apiClient;
    }

    public void start() {
        if (!pluginConfig.heartbeat().enabled()) {
            return;
        }

        long intervalTicks = Math.max(20L, pluginConfig.heartbeat().intervalSeconds() * 20L);
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        String serverVersion;
        try {
            serverVersion = Bukkit.getName() + " " + Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError ignored) {
            serverVersion = Bukkit.getName() + " " + Bukkit.getBukkitVersion();
        }

        HeartbeatPayload payload = new HeartbeatPayload(
                pluginConfig.api().serverId(),
                "StellarRedeem",
                plugin.getDescription().getVersion(),
                serverVersion,
                Bukkit.getOnlinePlayers().size()
        );

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                apiClient.sendHeartbeat(payload);
            } catch (ApiClientException ex) {
                plugin.getLogger().severe("Heartbeat request failed: " + ex.getMessage());
            }
        });
    }
}
