package com.mieai.qqbot.client;

public enum QqMediaKind {
    IMAGE(1), VIDEO(2), AUDIO(3), FILE(4);

    private final int fileType;

    QqMediaKind(int fileType) {
        this.fileType = fileType;
    }

    public int fileType() {
        return fileType;
    }
}
