package org.stellarvan.stellarRedeem;

import org.bukkit.Color;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.command.RedeemCommand;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.PluginApiSigner;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.service.CommandTemplateExecutor;
import org.stellarvan.stellarRedeem.service.CooldownService;
import org.stellarvan.stellarRedeem.service.RedeemService;
import org.stellarvan.stellarRedeem.task.HeartbeatTask;

public final class StellarRedeem extends JavaPlugin {
    private HeartbeatTask heartbeatTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig pluginConfig = PluginConfig.load(this);

        if (!validateRequiredConfig(pluginConfig)) {
            getLogger().severe("Invalid config. StellarRedeem will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        boolean redeemEnabled = true;
        if ("CHANGE_ME".equals(pluginConfig.api().serverSecret())) {
            redeemEnabled = false;
            getLogger().warning("api.server-secret is CHANGE_ME. Redeem requests are disabled until configured.");
            getLogger().warning("Please set api.server-secret to a real value to enable StellarRedeem.");
        }

        PluginApiSigner signer = new PluginApiSigner(pluginConfig.api().serverSecret());
        RedeemApiClient redeemApiClient = new RedeemApiClient(this, pluginConfig, signer);
        CooldownService cooldownService = new CooldownService();
        CommandTemplateExecutor commandTemplateExecutor = new CommandTemplateExecutor(this, pluginConfig);
        RedeemService redeemService = new RedeemService(
                this,
                pluginConfig,
                cooldownService,
                redeemApiClient,
                commandTemplateExecutor,
                redeemEnabled
        );

        PluginCommand command = getCommand("redeem");
        if (command == null) {
            getLogger().severe("Command 'redeem' is not defined in plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(new RedeemCommand(pluginConfig, redeemService));

        heartbeatTask = new HeartbeatTask(this, pluginConfig, redeemApiClient);
        if (redeemEnabled) {
            heartbeatTask.start();
        } else {
            getLogger().warning("Heartbeat not started because redeem API is disabled.");
        }

        getLogger().info("StellarRedeem enabled.");
        getLogger().info("API base URL: " + pluginConfig.api().baseUrl());
        getLogger().info("Server ID: " + pluginConfig.api().serverId());
        getLogger().info("Heartbeat: " + (pluginConfig.heartbeat().enabled() ? "enabled" : "disabled"));
    }

    @Override
    public void onDisable() {
        if (heartbeatTask != null) {
            heartbeatTask.stop();
        }
    }

    private boolean validateRequiredConfig(PluginConfig pluginConfig) {
        if (pluginConfig.api().baseUrl().isBlank()) {
            getLogger().severe("api.base-url cannot be empty.");
            return false;
        }
        if (pluginConfig.api().serverId().isBlank()) {
            getLogger().severe("api.server-id cannot be empty.");
            return false;
        }
        if (pluginConfig.api().serverSecret().isBlank()) {
            getLogger().severe("api.server-secret cannot be empty.");
            return false;
        }
        return true;
    }
}
