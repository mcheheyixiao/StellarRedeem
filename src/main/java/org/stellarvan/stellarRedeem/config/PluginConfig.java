package org.stellarvan.stellarRedeem.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {
    private static final String DEFAULT_MESSAGE_PREFIX = "&8[&b繁星&f兑换&8] ";

    private final Api api;
    private final Redeem redeem;
    private final Command command;
    private final Heartbeat heartbeat;
    private final Debug debug;
    private final CallbackRetry callbackRetry;
    private final Context context;
    private final Messages messages;

    public PluginConfig(
            Api api,
            Redeem redeem,
            Command command,
            Heartbeat heartbeat,
            Debug debug,
            CallbackRetry callbackRetry,
            Context context,
            Messages messages
    ) {
        this.api = api;
        this.redeem = redeem;
        this.command = command;
        this.heartbeat = heartbeat;
        this.debug = debug;
        this.callbackRetry = callbackRetry;
        this.context = context;
        this.messages = messages;
    }

    public static PluginConfig load(JavaPlugin plugin) {
        return load(plugin.getConfig());
    }

    public static PluginConfig load(FileConfiguration cfg) {
        Api api = new Api(
                stringValue(cfg, "api.base-url", "https://www.stellarvan.cn"),
                stringValue(cfg, "api.server-id", "survival-1"),
                stringValue(cfg, "api.server-secret", "CHANGE_ME"),
                cfg.getLong("api.timeout-ms", 5000L)
        );
        Redeem redeem = new Redeem(
                cfg.getInt("redeem.cooldown-seconds", 5),
                cfg.getBoolean("redeem.case-insensitive", true),
                cfg.getBoolean("redeem.allow-console", false)
        );
        Command command = new Command(
                cfg.getBoolean("command.stop-on-first-failure", true),
                cfg.getBoolean("command.log-executed-commands", true)
        );
        Heartbeat heartbeat = new Heartbeat(
                cfg.getBoolean("heartbeat.enabled", true),
                cfg.getInt("heartbeat.interval-seconds", 60)
        );
        Debug debug = new Debug(
                cfg.getBoolean("debug.enabled", false)
        );
        CallbackRetry callbackRetry = new CallbackRetry(
                cfg.getBoolean("callback-retry.enabled", true),
                stringValue(cfg, "callback-retry.file", "callback-queue.json"),
                cfg.getInt("callback-retry.interval-seconds", 30),
                cfg.getInt("callback-retry.max-attempts", 10),
                cfg.getInt("callback-retry.max-queue-size", 500)
        );
        Context context = new Context(
                cfg.getBoolean("context.include-world", true),
                cfg.getBoolean("context.include-server-version", true),
                cfg.getBoolean("context.include-online-players", true),
                cfg.getBoolean("context.include-player-ip", false)
        );
        Messages messages = new Messages(
                colorize(stringValue(cfg, "messages.prefix", DEFAULT_MESSAGE_PREFIX)),
                colorize(stringValue(cfg, "messages.only-player", "&c该指令只能由玩家在游戏内使用。")),
                colorize(stringValue(cfg, "messages.no-permission", "&c你没有使用卡密兑换的权限。")),
                colorize(stringValue(cfg, "messages.usage", "&e用法：&f/redeem <卡密>")),
                colorize(stringValue(cfg, "messages.cooldown", "&7星轨尚未冷却，请在 &e{seconds} &7秒后再试。")),
                colorize(stringValue(cfg, "messages.processing", "&7正在核验星契，请稍候...")),
                colorize(stringValue(cfg, "messages.success", "&a兑换成功！&7奖励已送达你的冒险旅程。")),
                colorize(stringValue(cfg, "messages.invalid", "&c这份星契已失效、过期，或已被使用。")),
                colorize(stringValue(cfg, "messages.failed", "&c兑换流程出现异常，请联系管理员处理。")),
                colorize(stringValue(cfg, "messages.api-error", "&c星契服务暂时无法响应，请稍后再试。"))
        );
        return new PluginConfig(api, redeem, command, heartbeat, debug, callbackRetry, context, messages);
    }

    public PluginConfig withMessages(Messages newMessages) {
        return new PluginConfig(api, redeem, command, heartbeat, debug, callbackRetry, context, newMessages);
    }

    public PluginConfig withApi(Api newApi) {
        return new PluginConfig(newApi, redeem, command, heartbeat, debug, callbackRetry, context, messages);
    }

    public Api api() {
        return api;
    }

    public Redeem redeem() {
        return redeem;
    }

    public Command command() {
        return command;
    }

    public Heartbeat heartbeat() {
        return heartbeat;
    }

    public Debug debug() {
        return debug;
    }

    public CallbackRetry callbackRetry() {
        return callbackRetry;
    }

    public Context context() {
        return context;
    }

    public Messages messages() {
        return messages;
    }

    private static String stringValue(FileConfiguration cfg, String path, String def) {
        String value = cfg.getString(path);
        return value == null ? def : value;
    }

    private static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public record Api(String baseUrl, String serverId, String serverSecret, long timeoutMs) {
    }

    public record Redeem(int cooldownSeconds, boolean caseInsensitive, boolean allowConsole) {
    }

    public record Command(boolean stopOnFirstFailure, boolean logExecutedCommands) {
    }

    public record Heartbeat(boolean enabled, int intervalSeconds) {
    }

    public record Debug(boolean enabled) {
    }

    public record CallbackRetry(
            boolean enabled,
            String file,
            int intervalSeconds,
            int maxAttempts,
            int maxQueueSize
    ) {
    }

    public record Context(
            boolean includeWorld,
            boolean includeServerVersion,
            boolean includeOnlinePlayers,
            boolean includePlayerIp
    ) {
    }

    public record Messages(
            String prefix,
            String onlyPlayer,
            String noPermission,
            String usage,
            String cooldown,
            String processing,
            String success,
            String invalid,
            String failed,
            String apiError
    ) {
        public String onlyPlayer() {
            return withPrefix(this.onlyPlayer);
        }

        public String noPermission() {
            return withPrefix(this.noPermission);
        }

        public String usage() {
            return withPrefix(this.usage);
        }

        public String processing() {
            return withPrefix(this.processing);
        }

        public String success() {
            return withPrefix(this.success);
        }

        public String invalid() {
            return withPrefix(this.invalid);
        }

        public String failed() {
            return withPrefix(this.failed);
        }

        public String apiError() {
            return withPrefix(this.apiError);
        }

        public String withPrefix(String message) {
            String coloredMessage = colorize(message == null ? "" : message);
            String configuredPrefix = prefix == null ? colorize(DEFAULT_MESSAGE_PREFIX) : prefix;
            if (configuredPrefix.isEmpty() || coloredMessage.isEmpty()) {
                return coloredMessage;
            }
            if (hasExistingPrefix(message) || hasExistingPrefix(coloredMessage)) {
                return coloredMessage;
            }
            return configuredPrefix + coloredMessage;
        }

        public String cooldownWithSeconds(long seconds) {
            return withPrefix(this.cooldown.replace("{seconds}", Long.toString(seconds)));
        }

        private boolean hasExistingPrefix(String message) {
            if (message == null) {
                return false;
            }
            String trimmed = message.stripLeading();
            return trimmed.startsWith("[") || trimmed.startsWith("&8[") || trimmed.startsWith("§8[");
        }
    }
}
