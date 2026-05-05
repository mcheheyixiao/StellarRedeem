package org.stellarvan.stellarRedeem.http;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PluginApiSigner {
    private final byte[] secret;

    public PluginApiSigner(String serverSecret) {
        this.secret = serverSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(long timestampSeconds, String rawBody) {
        try {
            String payload = timestampSeconds + "." + rawBody;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign request payload", ex);
        }
    }

    private String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder(data.length * 2);
        for (byte b : data) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
