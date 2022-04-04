package com.ghostchu.villageraihelper;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class VillagerPastStatus {
    private boolean aiEnabled;
    private boolean awareEnabled;
    private UUID operator;

    public VillagerPastStatus(boolean aiEnabled, boolean awareEnabled,@NotNull UUID operator) {
        this.aiEnabled = aiEnabled;
        this.awareEnabled = awareEnabled;
        this.operator = operator;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public boolean isAwareEnabled() {
        return awareEnabled;
    }

    @NotNull
    public UUID getOperator() {
        return operator;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    public void setAwareEnabled(boolean awareEnabled) {
        this.awareEnabled = awareEnabled;
    }

    public void setOperator(@NotNull UUID operator) {
        this.operator = operator;
    }
}
