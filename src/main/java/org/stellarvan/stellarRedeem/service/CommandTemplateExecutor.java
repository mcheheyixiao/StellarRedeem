package org.stellarvan.stellarRedeem.service;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;

public final class CommandTemplateExecutor {
    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;

    public CommandTemplateExecutor(JavaPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public ExecutionResult execute(
            String playerName,
            String playerUuid,
            String worldName,
            List<String> commandTemplates
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Command execution must run on main thread.");
        }

        List<String> executedCommands = new ArrayList<>();
        String firstFailedCommand = null;
        String firstError = null;
        ConsoleCommandSender console = Bukkit.getConsoleSender();

        for (String template : commandTemplates) {
            if (template == null) {
                continue;
            }

            String command = replacePlaceholders(template, playerName, playerUuid, worldName).trim();
            if (command.isEmpty()) {
                continue;
            }
            while (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            if (command.isEmpty()) {
                continue;
            }

            try {
                if (pluginConfig.command().logExecutedCommands()) {
                    plugin.getLogger().info("Executing redeem command: " + command);
                }

                boolean dispatched = Bukkit.dispatchCommand(console, command);
                if (!dispatched) {
                    if (firstFailedCommand == null) {
                        firstFailedCommand = command;
                        firstError = "dispatchCommand returned false";
                    }
                    if (pluginConfig.command().stopOnFirstFailure()) {
                        break;
                    }
                    continue;
                }
                executedCommands.add(command);
            } catch (Exception ex) {
                if (firstFailedCommand == null) {
                    firstFailedCommand = command;
                    firstError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
                if (pluginConfig.command().stopOnFirstFailure()) {
                    break;
                }
            }
        }

        if (firstFailedCommand != null) {
            return ExecutionResult.failure(firstFailedCommand, firstError, executedCommands);
        }
        return ExecutionResult.success(executedCommands);
    }

    private String replacePlaceholders(String template, String playerName, String playerUuid, String worldName) {
        return template
                .replace("{player}", playerName)
                .replace("<player>", playerName)
                .replace("{uuid}", playerUuid)
                .replace("{world}", worldName);
    }

    public record ExecutionResult(boolean success, String failedCommand, String error, List<String> executedCommands) {
        public static ExecutionResult success(List<String> executedCommands) {
            return new ExecutionResult(true, null, null, List.copyOf(executedCommands));
        }

        public static ExecutionResult failure(String failedCommand, String error, List<String> executedCommands) {
            return new ExecutionResult(false, failedCommand, error, List.copyOf(executedCommands));
        }
    }
}
