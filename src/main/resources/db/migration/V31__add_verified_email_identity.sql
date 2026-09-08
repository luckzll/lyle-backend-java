CREATE TABLE auth_email_identity (
  email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  verified_at DATETIME NOT NULL,
  UNIQUE KEY uk_auth_email_user (user_id),
  CONSTRAINT fk_auth_email_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
