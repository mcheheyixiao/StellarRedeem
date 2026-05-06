package org.stellarvan.stellarRedeem;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.command.RedeemCommand;
import org.stellarvan.stellarRedeem.command.StellarRedeemAdminCommand;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.http.PluginApiSigner;
import org.stellarvan.stellarRedeem.http.RedeemApiClient;
import org.stellarvan.stellarRedeem.retry.CallbackRetryQueue;
import org.stellarvan.stellarRedeem.retry.CallbackRetryTask;
import org.stellarvan.stellarRedeem.service.CommandTemplateExecutor;
import org.stellarvan.stellarRedeem.service.CooldownService;
import org.stellarvan.stellarRedeem.service.RedeemService;
import org.stellarvan.stellarRedeem.task.HeartbeatTask;

public final class StellarRedeem extends JavaPlugin {
    private volatile PluginConfig pluginConfig;
    private volatile RedeemService redeemService;
    private volatile boolean redeemEnabled;
    private volatile CallbackRetryQueue callbackRetryQueue;
    private volatile boolean callbackRetryActive;

    private CooldownService cooldownService;
    private HeartbeatTask heartbeatTask;
    private CallbackRetryTask callbackRetryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!rebuildRuntime(false)) {
            getLogger().severe("Invalid config. StellarRedeem will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerCommands()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
    }

    @Override
    public void onDisable() {
        stopTasks();
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public RedeemService getRedeemService() {
        return redeemService;
    }

    public void handleReloadCommand(CommandSender sender) {
        sender.sendMessage("Reloading StellarRedeem...");
        reloadConfig();
        if (rebuildRuntime(true)) {
            sender.sendMessage("StellarRedeem reloaded.");
        } else {
            sender.sendMessage("StellarRedeem reload failed. Keeping previous runtime.");
        }
    }

    public void handleStatusCommand(CommandSender sender) {
        PluginConfig cfg = pluginConfig;
        if (cfg == null) {
            sender.sendMessage("StellarRedeem not initialized.");
            return;
        }

        sender.sendMessage("StellarRedeem status:");
        sender.sendMessage("- Plugin enabled: " + isEnabled());
        sender.sendMessage("- Redeem enabled: " + redeemEnabled);
        sender.sendMessage("- Base URL: " + cfg.api().baseUrl());
        sender.sendMessage("- Server ID: " + cfg.api().serverId());
        sender.sendMessage("- Heartbeat: " + (cfg.heartbeat().enabled() ? "enabled" : "disabled")
                + " (interval=" + cfg.heartbeat().intervalSeconds() + "s)");
        sender.sendMessage("- Callback retry: " + (callbackRetryActive ? "enabled" : "disabled"));
        sender.sendMessage("- Callback queue size: " + getCallbackQueueSize());
        sender.sendMessage("- Debug enabled: " + cfg.debug().enabled());
    }

    public void handleTestApiCommand(CommandSender sender) {
        if (!redeemEnabled) {
            sender.sendMessage("Redeem API is disabled by current config.");
            return;
        }
        HeartbeatTask task = heartbeatTask;
        if (task == null) {
            sender.sendMessage("Heartbeat task is not available.");
            return;
        }

        sender.sendMessage("Testing API reachability via heartbeat...");
        task.sendOnceAsync(
                () -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("API reachable.")),
                error -> Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("API test failed: " + error))
        );
    }

    public void debug(String message) {
        PluginConfig cfg = pluginConfig;
        if (cfg != null && cfg.debug().enabled()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private boolean registerCommands() {
        PluginCommand redeem = getCommand("redeem");
        if (redeem == null) {
            getLogger().severe("Command 'redeem' is not defined in plugin.yml.");
            return false;
        }
        redeem.setExecutor(new RedeemCommand(this));

        PluginCommand admin = getCommand("stellarredeem");
        if (admin == null) {
            getLogger().severe("Command 'stellarredeem' is not defined in plugin.yml.");
            return false;
        }
        StellarRedeemAdminCommand adminCommand = new StellarRedeemAdminCommand(this);
        admin.setExecutor(adminCommand);
        admin.setTabCompleter(adminCommand);
        return true;
    }

    private synchronized boolean rebuildRuntime(boolean fromReloadCommand) {
        PluginConfig newConfig = PluginConfig.load(this);
        RuntimeBundle bundle = buildRuntimeBundle(newConfig);
        if (!bundle.valid()) {
            for (String error : bundle.errors()) {
                getLogger().severe(error);
            }
            return false;
        }

        if (fromReloadCommand) {
            getLogger().info("Applying StellarRedeem runtime reload.");
        }

        stopTasks();
        if (cooldownService == null) {
            cooldownService = new CooldownService();
        }

        this.pluginConfig = bundle.config();
        this.callbackRetryQueue = bundle.callbackRetryQueue();
        this.callbackRetryActive = bundle.callbackRetryTask() != null;
        this.redeemEnabled = bundle.redeemEnabled();
        this.redeemService = new RedeemService(
                this,
                bundle.config(),
                cooldownService,
                bundle.apiClient(),
                bundle.commandExecutor(),
                bundle.callbackQueueForService(),
                this::debug,
                bundle.redeemEnabled()
        );
        this.heartbeatTask = bundle.heartbeatTask();
        this.callbackRetryTask = bundle.callbackRetryTask();

        if (redeemEnabled) {
            heartbeatTask.start();
            if (callbackRetryTask != null) {
                callbackRetryTask.start();
            }
        } else {
            getLogger().warning("Redeem requests are disabled by config. Heartbeat and callback retry are not started.");
        }

        logStartupStatus();
        return true;
    }

    private RuntimeBundle buildRuntimeBundle(PluginConfig config) {
        RuntimeBundle.Builder builder = RuntimeBundle.builder(config);

        validateRequiredConfig(config, builder);
        if (!builder.errors().isEmpty()) {
            return builder.buildInvalid();
        }

        boolean newRedeemEnabled = true;
        if ("CHANGE_ME".equals(config.api().serverSecret().trim())) {
            newRedeemEnabled = false;
            getLogger().warning("api.server-secret is CHANGE_ME. Redeem requests are disabled until configured.");
        }

        PluginApiSigner signer = new PluginApiSigner(config.api().serverSecret());
        RedeemApiClient apiClient = new RedeemApiClient(this, config, signer);
        CommandTemplateExecutor commandExecutor = new CommandTemplateExecutor(this, config);
        HeartbeatTask newHeartbeatTask = new HeartbeatTask(this, config, apiClient, this::debug);

        CallbackRetryQueue queue = null;
        CallbackRetryQueue queueForService = null;
        CallbackRetryTask retryTask = null;
        if (config.callbackRetry().enabled()) {
            queue = new CallbackRetryQueue(this, config.callbackRetry());
            if (queue.isStorageReady()) {
                queueForService = queue;
                if (newRedeemEnabled) {
                    retryTask = new CallbackRetryTask(this, config, apiClient, queue, this::debug);
                }
            } else {
                getLogger().warning("Callback retry disabled because queue file is unavailable: " + queue.getFilePath());
            }
        }

        return builder.buildValid(
                apiClient,
                commandExecutor,
                newHeartbeatTask,
                queue,
                queueForService,
                retryTask,
                newRedeemEnabled
        );
    }

    private void validateRequiredConfig(PluginConfig config, RuntimeBundle.Builder builder) {
        if (config.api().baseUrl().isBlank()) {
            builder.error("api.base-url cannot be empty.");
        }
        if (config.api().serverId().isBlank()) {
            builder.error("api.server-id cannot be empty.");
        }
        if (config.api().serverSecret().isBlank()) {
            builder.error("api.server-secret cannot be empty.");
        }
        if (config.api().timeoutMs() <= 0) {
            builder.error("api.timeout-ms must be > 0.");
        }
    }

    private int getCallbackQueueSize() {
        CallbackRetryQueue queue = callbackRetryQueue;
        if (queue == null) {
            return 0;
        }
        return queue.size();
    }

    private void stopTasks() {
        if (heartbeatTask != null) {
            heartbeatTask.stop();
            heartbeatTask = null;
        }
        if (callbackRetryTask != null) {
            callbackRetryTask.stop();
            callbackRetryTask = null;
        }
    }

    private void logStartupStatus() {
        PluginConfig cfg = pluginConfig;
        if (cfg == null) {
            return;
        }
        getLogger().info("StellarRedeem enabled.");
        getLogger().info("Redeem enabled: " + redeemEnabled);
        getLogger().info("API base URL: " + cfg.api().baseUrl());
        getLogger().info("Server ID: " + cfg.api().serverId());
        getLogger().info("Heartbeat: " + (cfg.heartbeat().enabled() ? "enabled" : "disabled"));
        getLogger().info("Callback retry: " + (callbackRetryActive ? "enabled" : "disabled"));
    }

    private record RuntimeBundle(
            boolean valid,
            PluginConfig config,
            RedeemApiClient apiClient,
            CommandTemplateExecutor commandExecutor,
            HeartbeatTask heartbeatTask,
            CallbackRetryQueue callbackRetryQueue,
            CallbackRetryQueue callbackQueueForService,
            CallbackRetryTask callbackRetryTask,
            boolean redeemEnabled,
            java.util.List<String> errors
    ) {
        static Builder builder(PluginConfig config) {
            return new Builder(config);
        }

        private static final class Builder {
            private final PluginConfig config;
            private final java.util.List<String> errors = new java.util.ArrayList<>();

            private Builder(PluginConfig config) {
                this.config = config;
            }

            private void error(String message) {
                errors.add(message);
            }

            private java.util.List<String> errors() {
                return errors;
            }

            private RuntimeBundle buildInvalid() {
                return new RuntimeBundle(false, config, null, null, null, null, null, null, false, java.util.List.copyOf(errors));
            }

            private RuntimeBundle buildValid(
                    RedeemApiClient apiClient,
                    CommandTemplateExecutor commandExecutor,
                    HeartbeatTask heartbeatTask,
                    CallbackRetryQueue callbackRetryQueue,
                    CallbackRetryQueue callbackQueueForService,
                    CallbackRetryTask callbackRetryTask,
                    boolean redeemEnabled
            ) {
                return new RuntimeBundle(
                        true,
                        config,
                        apiClient,
                        commandExecutor,
                        heartbeatTask,
                        callbackRetryQueue,
                        callbackQueueForService,
                        callbackRetryTask,
                        redeemEnabled,
                        java.util.List.of()
                );
            }
        }
    }
}
