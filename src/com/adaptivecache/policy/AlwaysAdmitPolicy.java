package com.adaptivecache.policy;

import com.adaptivecache.core.CacheEntry;

/** Admits everything — the default (pre-TinyLFU) behaviour. */
public final class AlwaysAdmitPolicy implements AdmissionPolicy {

    @Override
    public boolean admit(String key, CacheEntry entry) {
        return true;
    }

    @Override
    public String name() {
        return "always";
    }
}
