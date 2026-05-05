package org.stellarvan.stellarRedeem.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CooldownService {
    private final Map<UUID, Long> lastRedeemMillis = new ConcurrentHashMap<>();

    public long getRemainingSeconds(UUID uuid, int cooldownSeconds) {
        Long lastUse = lastRedeemMillis.get(uuid);
        if (lastUse == null || cooldownSeconds <= 0) {
            return 0L;
        }

        long cooldownMillis = cooldownSeconds * 1000L;
        long elapsed = System.currentTimeMillis() - lastUse;
        long remaining = cooldownMillis - elapsed;
        if (remaining <= 0) {
            lastRedeemMillis.remove(uuid);
            return 0L;
        }
        return (remaining + 999L) / 1000L;
    }

    public void markUsed(UUID uuid) {
        lastRedeemMillis.put(uuid, System.currentTimeMillis());
    }
}
