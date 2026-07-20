package com.mieai.qqbot.admin.bot;

public final class MediaUploadTooLargeException extends RuntimeException {
    private final long actualBytes;
    private final long maxBytes;

    public MediaUploadTooLargeException(long actualBytes, long maxBytes) {
        super("媒体文件超过该机器人的上传上限");
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public long actualBytes() { return actualBytes; }
    public long maxBytes() { return maxBytes; }
}
