package com.nontracey.aiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Map;

/** Maps opaque API credentials to trusted tenant identities. */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(boolean allowAnonymous, Map<String, String> apiKeys) {
    public SecurityProperties {
        apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
    }
}
