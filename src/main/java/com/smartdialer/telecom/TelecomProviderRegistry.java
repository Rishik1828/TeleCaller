package com.smartdialer.telecom;

import com.smartdialer.domain.enums.ProviderType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TelecomProviderRegistry {

    private final Map<ProviderType, TelecomProvider> providers = new ConcurrentHashMap<>();

    public TelecomProviderRegistry(List<TelecomProvider> providerList) {
        for (TelecomProvider p : providerList) {
            providers.put(p.getProviderType(), p);
        }
    }

    public TelecomProvider getProvider(ProviderType type) {
        TelecomProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown telecom provider type: " + type);
        }
        return provider;
    }

    public Map<ProviderType, TelecomProvider> getAllProviders() {
        return providers;
    }
}
