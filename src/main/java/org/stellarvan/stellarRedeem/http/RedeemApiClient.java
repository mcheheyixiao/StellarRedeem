package org.stellarvan.stellarRedeem.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;

public final class RedeemApiClient {
    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final PluginApiSigner signer;
    private final Gson gson;
    private final HttpClient httpClient;

    public RedeemApiClient(JavaPlugin plugin, PluginConfig pluginConfig, PluginApiSigner signer) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.signer = signer;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000L, pluginConfig.api().timeoutMs())))
                .build();
    }

    public ClaimResult claimCode(ClaimRequest request) throws ApiClientException {
        JsonObject body = new JsonObject();
        body.addProperty("code", request.code());
        body.addProperty("serverId", request.serverId());
        body.addProperty("playerName", request.playerName());
        body.addProperty("playerUuid", request.playerUuid());
        body.addProperty("world", request.world());
        if (request.serverVersion() != null) {
            body.addProperty("serverVersion", request.serverVersion());
        }
        if (request.onlinePlayers() != null) {
            body.addProperty("onlinePlayers", request.onlinePlayers());
        }
        if (request.playerIp() != null) {
            body.addProperty("playerIp", request.playerIp());
        }

        HttpResponse<String> response = postJson("/api/minecraft/redeem/claim", gson.toJson(body));
        String responseBody = response.body() == null ? "" : response.body();
        ClaimResponseJson parsed = parseClaimResponse(responseBody);

        if (parsed != null && !parsed.success) {
            return ClaimResult.failure(parsed.reason, parsed.message);
        }

        if (!isSuccessStatus(response.statusCode())) {
            throw new ApiClientException("Claim API returned HTTP " + response.statusCode());
        }

        if (parsed == null) {
            throw new ApiClientException("Claim API returned invalid JSON.");
        }
        if (!parsed.success) {
            return ClaimResult.failure(parsed.reason, parsed.message);
        }
        if (parsed.redeemId == null) {
            throw new ApiClientException("Claim API response missing redeemId.");
        }

        List<String> commands = new ArrayList<>();
        if (parsed.commands != null) {
            parsed.commands.stream()
                    .filter(Objects::nonNull)
                    .forEach(commands::add);
        }
        return ClaimResult.success(parsed.redeemId, parsed.message, commands);
    }

    public void sendComplete(long redeemId, List<String> executedCommands) throws ApiClientException {
        JsonObject body = new JsonObject();
        body.add("executedCommands", gson.toJsonTree(executedCommands));

        HttpResponse<String> response = postJson("/api/minecraft/redeem/" + redeemId + "/complete", gson.toJson(body));
        if (!isSuccessStatus(response.statusCode())) {
            throw new ApiClientException("Complete API returned HTTP " + response.statusCode());
        }
    }

    public void sendFail(long redeemId, String failedCommand, String error, List<String> executedCommands) throws ApiClientException {
        JsonObject body = new JsonObject();
        body.addProperty("failedCommand", failedCommand);
        body.addProperty("error", error);
        body.add("executedCommands", gson.toJsonTree(executedCommands));

        HttpResponse<String> response = postJson("/api/minecraft/redeem/" + redeemId + "/fail", gson.toJson(body));
        if (!isSuccessStatus(response.statusCode())) {
            throw new ApiClientException("Fail API returned HTTP " + response.statusCode());
        }
    }

    public void sendHeartbeat(HeartbeatPayload payload) throws ApiClientException {
        JsonObject body = new JsonObject();
        body.addProperty("serverId", payload.serverId());
        body.addProperty("plugin", payload.plugin());
        body.addProperty("version", payload.version());
        body.addProperty("serverVersion", payload.serverVersion());
        body.addProperty("onlinePlayers", payload.onlinePlayers());

        HttpResponse<String> response = postJson("/api/minecraft/redeem/heartbeat", gson.toJson(body));
        if (!isSuccessStatus(response.statusCode())) {
            throw new ApiClientException("Heartbeat API returned HTTP " + response.statusCode());
        }
    }

    private HttpResponse<String> postJson(String path, String rawBody) throws ApiClientException {
        long timestamp = Instant.now().getEpochSecond();
        String signature = signer.sign(timestamp, rawBody);
        String url = buildUrl(path);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000L, pluginConfig.api().timeoutMs())))
                .header("Content-Type", "application/json")
                .header("X-Stellar-Server-Id", pluginConfig.api().serverId())
                .header("X-Stellar-Timestamp", Long.toString(timestamp))
                .header("X-Stellar-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(rawBody, StandardCharsets.UTF_8))
                .build();

        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiClientException("HTTP request interrupted.", ex);
        } catch (IOException ex) {
            throw new ApiClientException("HTTP request failed: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().severe("Invalid API URL: " + pluginConfig.api().baseUrl());
            throw new ApiClientException("Invalid API URL.", ex);
        }
    }

    private String buildUrl(String path) {
        String baseUrl = pluginConfig.api().baseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + path;
    }

    private boolean isSuccessStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private ClaimResponseJson parseClaimResponse(String body) {
        try {
            return gson.fromJson(body, ClaimResponseJson.class);
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    public record ClaimRequest(
            String code,
            String serverId,
            String playerName,
            String playerUuid,
            String world,
            String serverVersion,
            Integer onlinePlayers,
            String playerIp
    ) {
    }

    public record ClaimResult(boolean success, Long redeemId, String reason, String message, List<String> commands) {
        public static ClaimResult success(long redeemId, String message, List<String> commands) {
            return new ClaimResult(true, redeemId, null, message, List.copyOf(commands));
        }

        public static ClaimResult failure(String reason, String message) {
            return new ClaimResult(false, null, reason, message, List.of());
        }
    }

    public record HeartbeatPayload(
            String serverId,
            String plugin,
            String version,
            String serverVersion,
            int onlinePlayers
    ) {
    }

    private static final class ClaimResponseJson {
        private boolean success;
        private Long redeemId;
        private String reason;
        private String message;
        private List<String> commands;
    }

    public static final class ApiClientException extends Exception {
        public ApiClientException(String message) {
            super(message);
        }

        public ApiClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
