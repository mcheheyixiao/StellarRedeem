package org.stellarvan.stellarRedeem.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {
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
        FileConfiguration cfg = plugin.getConfig();
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
                colorize(stringValue(cfg, "messages.only-player", "&cThis command can only be used by players.")),
                colorize(stringValue(cfg, "messages.no-permission", "&cYou do not have permission.")),
                colorize(stringValue(cfg, "messages.usage", "&eUsage: /redeem <code>")),
                colorize(stringValue(cfg, "messages.cooldown", "&cPlease wait {seconds}s before redeeming again.")),
                colorize(stringValue(cfg, "messages.processing", "&eVerifying redeem code...")),
                colorize(stringValue(cfg, "messages.success", "&aRedeem success, rewards delivered.")),
                colorize(stringValue(cfg, "messages.invalid", "&cInvalid, expired, or already used code.")),
                colorize(stringValue(cfg, "messages.failed", "&cRedeem failed, contact admin.")),
                colorize(stringValue(cfg, "messages.api-error", "&cRedeem service is unavailable, try again later."))
        );
        return new PluginConfig(api, redeem, command, heartbeat, debug, callbackRetry, context, messages);
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
        public String cooldownWithSeconds(long seconds) {
            return cooldown.replace("{seconds}", Long.toString(seconds));
        }
    }
}
