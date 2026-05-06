package org.stellarvan.stellarRedeem.retry;

import java.util.ArrayList;
import java.util.List;

public final class PendingCallback {
    private CallbackType type;
    private long redeemId;
    private List<String> executedCommands;
    private String failedCommand;
    private String error;
    private int attempts;
    private long createdAt;
    private long lastAttemptAt;

    private PendingCallback() {
        this.executedCommands = new ArrayList<>();
    }

    private PendingCallback(
            CallbackType type,
            long redeemId,
            List<String> executedCommands,
            String failedCommand,
            String error
    ) {
        this.type = type;
        this.redeemId = redeemId;
        this.executedCommands = sanitizeCommands(executedCommands);
        this.failedCommand = failedCommand;
        this.error = error;
        this.attempts = 0;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastAttemptAt = 0L;
    }

    public static PendingCallback complete(long redeemId, List<String> executedCommands) {
        return new PendingCallback(CallbackType.COMPLETE, redeemId, executedCommands, null, null);
    }

    public static PendingCallback fail(long redeemId, String failedCommand, String error, List<String> executedCommands) {
        return new PendingCallback(CallbackType.FAIL, redeemId, executedCommands, failedCommand, error);
    }

    public CallbackType getType() {
        return type;
    }

    public long getRedeemId() {
        return redeemId;
    }

    public List<String> getExecutedCommands() {
        return List.copyOf(sanitizeCommands(executedCommands));
    }

    public String getFailedCommand() {
        return failedCommand;
    }

    public String getError() {
        return error;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastAttemptAt() {
        return lastAttemptAt;
    }

    public boolean isExhausted(int maxAttempts) {
        return attempts >= Math.max(1, maxAttempts);
    }

    public void markRetryFailed(long attemptedAtMillis) {
        this.attempts += 1;
        this.lastAttemptAt = attemptedAtMillis;
    }

    public void sanitizeAfterLoad() {
        if (type == null) {
            type = CallbackType.FAIL;
        }
        if (executedCommands == null) {
            executedCommands = new ArrayList<>();
        } else {
            executedCommands = sanitizeCommands(executedCommands);
        }
        if (attempts < 0) {
            attempts = 0;
        }
        if (createdAt < 0) {
            createdAt = 0;
        }
        if (lastAttemptAt < 0) {
            lastAttemptAt = 0;
        }
        if (type == CallbackType.COMPLETE) {
            failedCommand = null;
            error = null;
        }
    }

    private static List<String> sanitizeCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> clean = new ArrayList<>(commands.size());
        for (String command : commands) {
            if (command != null) {
                clean.add(command);
            }
        }
        return clean;
    }

    public enum CallbackType {
        COMPLETE,
        FAIL
    }
}
