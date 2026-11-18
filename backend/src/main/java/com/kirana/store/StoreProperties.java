package com.kirana.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.stores")
public record StoreProperties(
        @DefaultValue("3") double radiusKm,
        @DefaultValue("20") int maxResults) {
}
