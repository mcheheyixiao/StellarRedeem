package org.stellarvan.stellarRedeem.retry;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;
import org.stellarvan.stellarRedeem.config.PluginConfig;

public final class CallbackRetryQueue {
    private static final Type LIST_TYPE = new TypeToken<List<PendingCallback>>() {
    }.getType();

    private final JavaPlugin plugin;
    private final Gson gson;
    private final Path filePath;
    private final int maxQueueSize;
    private final List<PendingCallback> queue;
    private boolean storageReady;

    public CallbackRetryQueue(JavaPlugin plugin, PluginConfig.CallbackRetry config) {
        this.plugin = plugin;
        this.gson = new Gson();
        this.filePath = resolveFilePath(plugin, config.file());
        this.maxQueueSize = Math.max(1, config.maxQueueSize());
        this.queue = new ArrayList<>();
        this.storageReady = prepareStorage();
        if (storageReady) {
            loadFromDisk();
        }
    }

    public synchronized boolean isStorageReady() {
        return storageReady;
    }

    public synchronized Path getFilePath() {
        return filePath;
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized int exhaustedCount(int maxAttempts) {
        int count = 0;
        for (PendingCallback callback : queue) {
            if (callback.isExhausted(maxAttempts)) {
                count += 1;
            }
        }
        return count;
    }

    public synchronized List<PendingCallback> snapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized boolean enqueue(PendingCallback callback) {
        if (!storageReady) {
            plugin.getLogger().warning("Callback retry queue storage unavailable. Callback not queued.");
            return false;
        }
        if (callback == null) {
            return false;
        }
        if (queue.size() >= maxQueueSize) {
            plugin.getLogger().warning(
                    "Callback retry queue is full (" + maxQueueSize + "). Dropping callback for redeemId " + callback.getRedeemId()
            );
            return false;
        }
        callback.sanitizeAfterLoad();
        queue.add(callback);
        saveQuietly();
        return true;
    }

    public synchronized void remove(PendingCallback callback) {
        if (queue.remove(callback)) {
            saveQuietly();
        }
    }

    public synchronized void markRetryFailed(PendingCallback callback) {
        callback.markRetryFailed(System.currentTimeMillis());
        saveQuietly();
    }

    public synchronized void saveQuietly() {
        if (!storageReady) {
            return;
        }

        String json = gson.toJson(queue, LIST_TYPE);
        Path tempPath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try {
            Files.writeString(tempPath, json, StandardCharsets.UTF_8);
            moveReplace(tempPath, filePath);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save callback retry queue: " + ex.getMessage());
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
        }
    }

    private Path resolveFilePath(JavaPlugin plugin, String fileName) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        String normalized = fileName == null ? "" : fileName.trim();
        if (normalized.isEmpty()) {
            normalized = "callback-queue.json";
        }
        Path resolved = dataFolder.resolve(normalized).normalize();
        if (!resolved.startsWith(dataFolder)) {
            plugin.getLogger().warning("callback-retry.file points outside plugin data folder, fallback to callback-queue.json");
            return dataFolder.resolve("callback-queue.json");
        }
        return resolved;
    }

    private boolean prepareStorage() {
        Path parent = filePath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(filePath)) {
                Files.writeString(filePath, "[]", StandardCharsets.UTF_8);
            }
            if (!Files.isReadable(filePath) || !Files.isWritable(filePath)) {
                plugin.getLogger().warning("Callback retry queue file is not readable/writable: " + filePath);
                return false;
            }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to initialize callback retry queue file " + filePath + ": " + ex.getMessage());
            return false;
        }
    }

    private void loadFromDisk() {
        try {
            String raw = Files.readString(filePath, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                queue.clear();
                saveQuietly();
                return;
            }
            List<PendingCallback> loaded = gson.fromJson(raw, LIST_TYPE);
            queue.clear();
            if (loaded != null) {
                for (PendingCallback callback : loaded) {
                    if (callback == null) {
                        continue;
                    }
                    callback.sanitizeAfterLoad();
                    queue.add(callback);
                }
            }
        } catch (JsonParseException | IllegalStateException | IOException ex) {
            plugin.getLogger().warning("Callback retry queue file is broken: " + filePath + ", reason: " + ex.getMessage());
            backupBrokenFile();
            queue.clear();
            saveQuietly();
        }
    }

    private void backupBrokenFile() {
        Path brokenPath = filePath.resolveSibling(filePath.getFileName() + ".broken." + System.currentTimeMillis());
        try {
            moveReplace(filePath, brokenPath);
            plugin.getLogger().warning("Broken callback queue backed up to: " + brokenPath);
        } catch (IOException moveEx) {
            plugin.getLogger().warning("Failed to backup broken callback queue: " + moveEx.getMessage());
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException deleteEx) {
                plugin.getLogger().warning("Failed to delete broken callback queue: " + deleteEx.getMessage());
            }
        }
    }

    private void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
