package org.stellarvan.stellarRedeem.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {
    private final Api api;
    private final Redeem redeem;
    private final Command command;
    private final Heartbeat heartbeat;
    private final Messages messages;

    public PluginConfig(Api api, Redeem redeem, Command command, Heartbeat heartbeat, Messages messages) {
        this.api = api;
        this.redeem = redeem;
        this.command = command;
        this.heartbeat = heartbeat;
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
        Messages messages = new Messages(
                colorize(stringValue(cfg, "messages.only-player", "&cThis command can only be used by players.")),
                colorize(stringValue(cfg, "messages.no-permission", "&c你没有权限使用卡密兑换。")),
                colorize(stringValue(cfg, "messages.usage", "&e用法：/redeem <卡密>")),
                colorize(stringValue(cfg, "messages.cooldown", "&c请稍候 {seconds} 秒后再兑换。")),
                colorize(stringValue(cfg, "messages.processing", "&e正在验证卡密，请稍候...")),
                colorize(stringValue(cfg, "messages.success", "&a兑换成功，奖励已发放。")),
                colorize(stringValue(cfg, "messages.invalid", "&c卡密无效、已过期或已被使用。")),
                colorize(stringValue(cfg, "messages.failed", "&c兑换失败，请联系管理员。")),
                colorize(stringValue(cfg, "messages.api-error", "&c兑换服务暂时不可用，请稍后再试。"))
        );
        return new PluginConfig(api, redeem, command, heartbeat, messages);
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
