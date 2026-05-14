package org.stellarvan.stellarRedeem.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stellarvan.stellarRedeem.StellarRedeem;
import org.stellarvan.stellarRedeem.config.PluginConfig;

public final class StellarRedeemAdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB_COMMANDS = List.of("help", "reload", "status", "doctor", "testapi");
    private static final List<String> RELOAD_SUB_COMMANDS = List.of("messages", "api");
    private final StellarRedeem plugin;

    public StellarRedeemAdminCommand(StellarRedeem plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("stellarredeem.admin")) {
            PluginConfig config = plugin.getPluginConfig();
            if (config != null) {
                sender.sendMessage(config.messages().noPermission());
            } else {
                sender.sendMessage("No permission.");
            }
            return true;
        }

        if (args.length == 0) {
            plugin.handleHelpCommand(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help" -> {
                plugin.handleHelpCommand(sender);
                yield true;
            }
            case "reload" -> {
                if (args.length == 1) {
                    plugin.handleReloadCommand(sender);
                } else {
                    String reloadTarget = args[1].toLowerCase(Locale.ROOT);
                    switch (reloadTarget) {
                        case "messages" -> plugin.handleReloadMessagesCommand(sender);
                        case "api" -> plugin.handleReloadApiCommand(sender);
                        default -> plugin.handleHelpCommand(sender);
                    }
                }
                yield true;
            }
            case "status" -> {
                plugin.handleStatusCommand(sender);
                yield true;
            }
            case "doctor" -> {
                plugin.handleDoctorCommand(sender);
                yield true;
            }
            case "testapi" -> {
                plugin.handleTestApiCommand(sender);
                yield true;
            }
            default -> {
                plugin.handleHelpCommand(sender);
                yield true;
            }
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String[] args
    ) {
        if (!sender.hasPermission("stellarredeem.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(prefix)) {
                    suggestions.add(sub);
                }
            }
            return suggestions;
        }

        if (args.length == 2 && "reload".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            for (String sub : RELOAD_SUB_COMMANDS) {
                if (sub.startsWith(prefix)) {
                    suggestions.add(sub);
                }
            }
            return suggestions;
        }

        return List.of();
    }
}
