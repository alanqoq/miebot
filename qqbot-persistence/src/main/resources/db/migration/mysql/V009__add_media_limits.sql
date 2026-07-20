ALTER TABLE bots
    ADD COLUMN max_media_upload_bytes BIGINT NOT NULL DEFAULT 16777216,
    ADD CONSTRAINT ck_bots_max_media_upload
        CHECK (max_media_upload_bytes BETWEEN 1048576 AND 268435456);
