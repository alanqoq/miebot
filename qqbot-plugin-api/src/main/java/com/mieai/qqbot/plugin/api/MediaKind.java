package com.mieai.qqbot.plugin.api;

public enum MediaKind {
    IMAGE(1), VIDEO(2), AUDIO(3), FILE(4);

    private final int qqFileType;

    MediaKind(int qqFileType) {
        this.qqFileType = qqFileType;
    }

    public int qqFileType() {
        return qqFileType;
    }
}
