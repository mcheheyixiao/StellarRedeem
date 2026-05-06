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
    private static final List<String> SUB_COMMANDS = List.of("reload", "status", "testapi");
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
            sender.sendMessage("Usage: /stellarredeem <reload|status|testapi>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> {
                plugin.handleReloadCommand(sender);
                yield true;
            }
            case "status" -> {
                plugin.handleStatusCommand(sender);
                yield true;
            }
            case "testapi" -> {
                plugin.handleTestApiCommand(sender);
                yield true;
            }
            default -> {
                sender.sendMessage("Usage: /stellarredeem <reload|status|testapi>");
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
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (String sub : SUB_COMMANDS) {
            if (sub.startsWith(prefix)) {
                suggestions.add(sub);
            }
        }
        return suggestions;
    }
}
