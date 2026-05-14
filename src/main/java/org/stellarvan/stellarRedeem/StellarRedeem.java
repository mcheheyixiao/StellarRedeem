package org.stellarvan.stellarRedeem;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
    private static final String DEFAULT_ADMIN_PREFIX = "&8[&b繁星&f兑换&8] ";

    private volatile PluginConfig pluginConfig;
    private volatile RedeemApiClient apiClient;
    private volatile CommandTemplateExecutor commandExecutor;
    private volatile RedeemService redeemService;
    private volatile boolean redeemEnabled;
    private volatile CallbackRetryQueue callbackRetryQueue;
    private volatile CallbackRetryQueue callbackQueueForService;
    private volatile boolean callbackRetryActive;

    private CooldownService cooldownService;
    private HeartbeatTask heartbeatTask;
    private CallbackRetryTask callbackRetryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RuntimeBundle bundle = rebuildRuntime(PluginConfig.load(this), false);
        if (!bundle.valid()) {
            getLogger().severe("Invalid config. StellarRedeem will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!registerCommands()) {
            getServer().getPluginManager().disablePlugin(this);
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

    public String prefixedMessage(String message) {
        PluginConfig cfg = pluginConfig;
        if (cfg != null) {
            return cfg.messages().withPrefix(message);
        }
        return ChatColor.translateAlternateColorCodes('&', adminPrefix() + (message == null ? "" : message));
    }

    public void handleHelpCommand(CommandSender sender) {
        sendAdminMessage(sender, "&bStellarRedeem 管理命令：");
        sendAdminMessage(sender, "&f/stellarredeem help");
        sendAdminMessage(sender, "&f/stellarredeem reload");
        sendAdminMessage(sender, "&f/stellarredeem reload messages");
        sendAdminMessage(sender, "&f/stellarredeem reload api");
        sendAdminMessage(sender, "&f/stellarredeem status");
        sendAdminMessage(sender, "&f/stellarredeem doctor");
        sendAdminMessage(sender, "&f/stellarredeem testapi");
    }

    public void handleReloadCommand(CommandSender sender) {
        sendAdminMessage(sender, "&7正在重载配置...");
        PluginConfig loadedConfig = loadConfigFromDisk();
        if (loadedConfig == null) {
            sendReloadLoadFailure(sender);
            return;
        }

        RuntimeBundle bundle = rebuildRuntime(loadedConfig, true);
        if (!bundle.valid()) {
            sendReloadValidationFailure(sender, bundle.errors());
            return;
        }

        sendAdminMessage(sender, "&a配置校验通过。");
        sendAdminMessage(sender, "&aAPI 客户端已重建。");
        sendAdminMessage(sender, heartbeatReloadMessage());
        sendAdminMessage(sender, callbackRetryReloadMessage());
        sendAdminMessage(sender, "&a重载完成。");
    }

    public void handleReloadMessagesCommand(CommandSender sender) {
        PluginConfig currentConfig = pluginConfig;
        if (currentConfig == null) {
            sendAdminMessage(sender, "&cStellarRedeem 尚未初始化。");
            return;
        }

        sendAdminMessage(sender, "&7正在重载消息配置...");
        PluginConfig loadedConfig = loadConfigFromDisk();
        if (loadedConfig == null) {
            sendReloadLoadFailure(sender);
            return;
        }

        PluginConfig mergedConfig = currentConfig.withMessages(loadedConfig.messages());
        if (!applyMessagesConfig(mergedConfig)) {
            sendAdminMessage(sender, "&c消息配置重载失败，已保留旧运行配置。");
            return;
        }

        sendAdminMessage(sender, "&a消息配置已生效。");
        sendAdminMessage(sender, "&7API 客户端与任务保持原状态。");
    }

    public void handleReloadApiCommand(CommandSender sender) {
        PluginConfig currentConfig = pluginConfig;
        if (currentConfig == null) {
            sendAdminMessage(sender, "&cStellarRedeem 尚未初始化。");
            return;
        }

        sendAdminMessage(sender, "&7正在重载 API 配置...");
        PluginConfig loadedConfig = loadConfigFromDisk();
        if (loadedConfig == null) {
            sendReloadLoadFailure(sender);
            return;
        }

        RuntimeBundle bundle = rebuildRuntime(currentConfig.withApi(loadedConfig.api()), true);
        if (!bundle.valid()) {
            sendReloadValidationFailure(sender, bundle.errors());
            return;
        }

        sendAdminMessage(sender, "&aAPI 配置校验通过。");
        sendAdminMessage(sender, "&aAPI 客户端已重建。");
        sendAdminMessage(sender, heartbeatReloadMessage());
        sendAdminMessage(sender, callbackRetryReloadMessage());
        sendAdminMessage(sender, "&aAPI 运行时已重载完成。");
    }

    public void handleStatusCommand(CommandSender sender) {
        PluginConfig cfg = pluginConfig;
        if (cfg == null) {
            sendAdminMessage(sender, "&cStellarRedeem 尚未初始化。");
            return;
        }

        sendAdminMessage(sender, "&b当前运行状态：");
        sendAdminMessage(sender, "&7Plugin enabled: &f" + isEnabled());
        sendAdminMessage(sender, "&7Redeem enabled: &f" + redeemEnabled);
        sendAdminMessage(sender, "&7API Base URL: &f" + cfg.api().baseUrl());
        sendAdminMessage(sender, "&7Server ID: &f" + cfg.api().serverId());
        sendAdminMessage(sender, "&7Server Secret: &f" + formatSecretStatusForStatus(cfg.api().serverSecret()));
        sendAdminMessage(sender, "&7Heartbeat: &f" + formatRuntimeState(isHeartbeatActive())
                + " &7(interval=&f" + cfg.heartbeat().intervalSeconds() + "s&7)");
        sendAdminMessage(sender, "&7Callback Retry: &f" + formatRuntimeState(callbackRetryActive));
        sendAdminMessage(sender, "&7Callback Queue Size: &f" + getCallbackQueueSize());
        sendAdminMessage(sender, "&7Debug: &f" + formatRuntimeState(cfg.debug().enabled()));
        sendAdminMessage(sender, "&7Context world: &f" + cfg.context().includeWorld());
        sendAdminMessage(sender, "&7Context server version: &f" + cfg.context().includeServerVersion());
        sendAdminMessage(sender, "&7Context online players: &f" + cfg.context().includeOnlinePlayers());
        sendAdminMessage(sender, "&7Context player IP: &f" + cfg.context().includePlayerIp());
    }

    public void handleDoctorCommand(CommandSender sender) {
        PluginConfig cfg = pluginConfig;
        if (cfg == null) {
            sendAdminMessage(sender, "&cStellarRedeem 尚未初始化。");
            return;
        }

        String secretStatus = formatSecretStatus(cfg.api().serverSecret());
        int queueSize = getCallbackQueueSize();

        sendAdminMessage(sender, "&bStellarRedeem 诊断报告：");
        sendAdminMessage(sender, "&7Plugin enabled: &f" + isEnabled());
        sendAdminMessage(sender, "&7Redeem enabled: &f" + redeemEnabled);
        sendAdminMessage(sender, "&7API Base URL: &f" + cfg.api().baseUrl());
        sendAdminMessage(sender, "&7Server ID: &f" + cfg.api().serverId());
        sendAdminMessage(sender, "&7Server Secret: &f" + secretStatus);
        sendAdminMessage(sender, "&7Timeout: &f" + cfg.api().timeoutMs() + " ms");
        sendAdminMessage(sender, "&7Heartbeat: &f" + formatRuntimeState(isHeartbeatActive()));
        sendAdminMessage(sender, "&7Callback Retry: &f" + formatRuntimeState(callbackRetryActive));
        sendAdminMessage(sender, "&7Callback Queue Size: &f" + queueSize);
        sendAdminMessage(sender, "&7Debug: &f" + formatRuntimeState(cfg.debug().enabled()));
        sendAdminMessage(sender, "&7Context world: &f" + cfg.context().includeWorld());
        sendAdminMessage(sender, "&7Context server version: &f" + cfg.context().includeServerVersion());
        sendAdminMessage(sender, "&7Context online players: &f" + cfg.context().includeOnlinePlayers());
        sendAdminMessage(sender, "&7Context player IP: &f" + cfg.context().includePlayerIp());
        sendAdminMessage(sender, "&7API 连通性请使用 &f/stellarredeem testapi");

        if ("CHANGE_ME".equals(secretStatus)) {
            sendAdminMessage(sender, "&e建议：server-secret 仍为 CHANGE_ME，请改为随机长密钥。");
        }
        if (queueSize > 0) {
            sendAdminMessage(sender, "&e建议：callback queue size > 0，请检查网站 API 或 complete/fail 回调。");
        }
        if (cfg.context().includePlayerIp()) {
            sendAdminMessage(sender, "&e建议：include-player-ip 已开启，请确认隐私政策。");
        }
    }

    public void handleTestApiCommand(CommandSender sender) {
        PluginConfig cfg = pluginConfig;
        if (cfg == null) {
            sendAdminMessage(sender, "&cStellarRedeem 尚未初始化。");
            return;
        }

        if (!redeemEnabled) {
            sendAdminMessage(sender, "&eRedeem 已禁用，未执行 API 测试。");
            if ("CHANGE_ME".equals(formatSecretStatus(cfg.api().serverSecret()))) {
                sendAdminMessage(sender, "&e常见原因：api.server-secret 仍为 CHANGE_ME，请配置后执行 /stellarredeem reload api。");
            } else {
                sendAdminMessage(sender, "&e常见原因：API 配置未完成或当前运行时不可用。");
            }
            return;
        }

        HeartbeatTask task = heartbeatTask;
        if (task == null) {
            sendAdminMessage(sender, "&cHeartbeat 任务不可用，无法执行测试。");
            return;
        }

        sendAdminMessage(sender, "&7正在通过 heartbeat 测试 API 连通性...");
        task.sendOnceAsync(
                () -> Bukkit.getScheduler().runTask(this, () -> sendAdminMessage(sender, "&aAPI 连通正常。")),
                error -> Bukkit.getScheduler().runTask(this, () -> {
                    sendAdminMessage(sender, "&cAPI 连通性测试失败。");
                    sendAdminMessage(sender, "&c错误：&f" + sanitizeErrorSummary(error));
                })
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

    private synchronized RuntimeBundle rebuildRuntime(PluginConfig newConfig, boolean fromReloadCommand) {
        RuntimeBundle bundle = buildRuntimeBundle(newConfig);
        if (!bundle.valid()) {
            for (String error : bundle.errors()) {
                getLogger().severe(error);
            }
            return bundle;
        }

        if (fromReloadCommand) {
            getLogger().info("Applying StellarRedeem runtime reload.");
        }

        stopTasks();
        if (cooldownService == null) {
            cooldownService = new CooldownService();
        }

        this.pluginConfig = bundle.config();
        this.apiClient = bundle.apiClient();
        this.commandExecutor = bundle.commandExecutor();
        this.callbackRetryQueue = bundle.callbackRetryQueue();
        this.callbackQueueForService = bundle.callbackQueueForService();
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
            if (heartbeatTask != null) {
                heartbeatTask.start();
            }
            if (callbackRetryTask != null) {
                callbackRetryTask.start();
            }
        } else {
            getLogger().warning("Redeem requests are disabled by config. Heartbeat and callback retry are not started.");
        }

        logStartupStatus();
        return bundle;
    }

    private boolean applyMessagesConfig(PluginConfig newConfig) {
        RedeemApiClient currentApiClient = apiClient;
        if (currentApiClient == null) {
            return false;
        }
        if (cooldownService == null) {
            cooldownService = new CooldownService();
        }

        // reload messages intentionally keeps API client and scheduled tasks unchanged.
        this.pluginConfig = newConfig;
        this.commandExecutor = new CommandTemplateExecutor(this, newConfig);
        this.redeemService = new RedeemService(
                this,
                newConfig,
                cooldownService,
                currentApiClient,
                commandExecutor,
                callbackQueueForService,
                this::debug,
                redeemEnabled
        );
        return true;
    }

    private PluginConfig loadConfigFromDisk() {
        try {
            reloadConfig();
            return PluginConfig.load(this);
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to reload config.yml: " + ex.getMessage());
            return null;
        }
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
        RedeemApiClient newApiClient = new RedeemApiClient(this, config, signer);
        CommandTemplateExecutor newCommandExecutor = new CommandTemplateExecutor(this, config);
        HeartbeatTask newHeartbeatTask = new HeartbeatTask(this, config, newApiClient, this::debug);

        CallbackRetryQueue queue = null;
        CallbackRetryQueue queueForService = null;
        CallbackRetryTask retryTask = null;
        if (config.callbackRetry().enabled()) {
            queue = new CallbackRetryQueue(this, config.callbackRetry());
            if (queue.isStorageReady()) {
                queueForService = queue;
                if (newRedeemEnabled) {
                    retryTask = new CallbackRetryTask(this, config, newApiClient, queue, this::debug);
                }
            } else {
                getLogger().warning("Callback retry disabled because queue file is unavailable: " + queue.getFilePath());
            }
        }

        return builder.buildValid(
                newApiClient,
                newCommandExecutor,
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

    private boolean isHeartbeatActive() {
        PluginConfig cfg = pluginConfig;
        return cfg != null && redeemEnabled && heartbeatTask != null && cfg.heartbeat().enabled();
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
        getLogger().info("Heartbeat: " + (isHeartbeatActive() ? "enabled" : "disabled"));
        getLogger().info("Callback retry: " + (callbackRetryActive ? "enabled" : "disabled"));
    }

    private void sendAdminMessage(CommandSender sender, String message) {
        sender.sendMessage(prefixedMessage(message));
    }

    private void sendReloadLoadFailure(CommandSender sender) {
        sendAdminMessage(sender, "&c重载失败，已保留旧运行配置。");
        sendAdminMessage(sender, "&c错误：&f无法读取 config.yml，请检查 YAML 格式。");
    }

    private void sendReloadValidationFailure(CommandSender sender, List<String> errors) {
        sendAdminMessage(sender, "&c重载失败，已保留旧运行配置。");
        sendAdminMessage(sender, "&c错误：&f" + summarizeErrors(errors));
    }

    private String summarizeErrors(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "未知配置错误。";
        }
        List<String> summary = new ArrayList<>();
        for (String error : errors) {
            if (error == null || error.isBlank()) {
                continue;
            }
            summary.add(sanitizeErrorSummary(error));
        }
        if (summary.isEmpty()) {
            return "未知配置错误。";
        }
        return String.join(" | ", summary);
    }

    private String sanitizeErrorSummary(String error) {
        if (error == null || error.isBlank()) {
            return "unknown";
        }
        String flattened = error.replace('\r', ' ').replace('\n', ' ').trim();
        if (flattened.length() <= 200) {
            return flattened;
        }
        return flattened.substring(0, 200) + "...";
    }

    private String heartbeatReloadMessage() {
        PluginConfig cfg = pluginConfig;
        if (cfg == null || !cfg.heartbeat().enabled()) {
            return "&7Heartbeat 未启用。";
        }
        if (!redeemEnabled) {
            return "&eHeartbeat 未启动，Redeem 当前已禁用。";
        }
        return "&aHeartbeat 已重启。";
    }

    private String callbackRetryReloadMessage() {
        PluginConfig cfg = pluginConfig;
        if (cfg == null || !cfg.callbackRetry().enabled()) {
            return "&7Callback Retry 未启用。";
        }
        if (!redeemEnabled) {
            return "&eCallback Retry 未启动，Redeem 当前已禁用。";
        }
        if (!callbackRetryActive) {
            return "&eCallback Retry 未启动，请检查队列文件是否可用。";
        }
        return "&aCallback Retry 已重启。";
    }

    private String formatSecretStatus(String secret) {
        if (secret == null || secret.isBlank()) {
            return "empty";
        }
        if ("CHANGE_ME".equals(secret.trim())) {
            return "CHANGE_ME";
        }
        return "configured";
    }

    private String formatSecretStatusForStatus(String secret) {
        return switch (formatSecretStatus(secret)) {
            case "CHANGE_ME" -> "CHANGE_ME，需要配置";
            case "empty" -> "empty，配置错误";
            default -> "configured";
        };
    }

    private String formatRuntimeState(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private String adminPrefix() {
        PluginConfig cfg = pluginConfig;
        if (cfg != null) {
            return cfg.messages().prefix();
        }
        return DEFAULT_ADMIN_PREFIX;
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
            List<String> errors
    ) {
        static Builder builder(PluginConfig config) {
            return new Builder(config);
        }

        private static final class Builder {
            private final PluginConfig config;
            private final List<String> errors = new ArrayList<>();

            private Builder(PluginConfig config) {
                this.config = config;
            }

            private void error(String message) {
                errors.add(message);
            }

            private List<String> errors() {
                return errors;
            }

            private RuntimeBundle buildInvalid() {
                return new RuntimeBundle(false, config, null, null, null, null, null, null, false, List.copyOf(errors));
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
                        List.of()
                );
            }
        }
    }
}
