CREATE TABLE auth_wechat_identity (
    app_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    verified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (app_id, open_id),
    UNIQUE KEY uk_wechat_user_app (user_id, app_id),
    CONSTRAINT fk_wechat_identity_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
