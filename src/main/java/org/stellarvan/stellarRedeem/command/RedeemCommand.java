package org.stellarvan.stellarRedeem.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.stellarvan.stellarRedeem.config.PluginConfig;
import org.stellarvan.stellarRedeem.service.RedeemService;

public final class RedeemCommand implements CommandExecutor {
    private final PluginConfig pluginConfig;
    private final RedeemService redeemService;

    public RedeemCommand(PluginConfig pluginConfig, RedeemService redeemService) {
        this.pluginConfig = pluginConfig;
        this.redeemService = redeemService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(pluginConfig.messages().onlyPlayer());
            return true;
        }

        if (!player.hasPermission("stellarredeem.redeem")) {
            player.sendMessage(pluginConfig.messages().noPermission());
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(pluginConfig.messages().usage());
            return true;
        }

        String code = String.join(" ", args).trim();
        if (code.isEmpty()) {
            player.sendMessage(pluginConfig.messages().usage());
            return true;
        }

        redeemService.redeem(player, code);
        return true;
    }
}
