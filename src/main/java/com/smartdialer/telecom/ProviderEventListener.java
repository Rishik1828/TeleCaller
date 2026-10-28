package com.smartdialer.telecom;

@FunctionalInterface
public interface ProviderEventListener {
    void onEvent(ProviderEvent event);
}
