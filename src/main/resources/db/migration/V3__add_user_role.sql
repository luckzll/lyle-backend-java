SET @column_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'app_user'
    AND COLUMN_NAME = 'role'
);

SET @add_role_sql = IF(
  @column_exists = 0,
  'ALTER TABLE app_user ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT ''USER''',
  'SELECT 1'
);

PREPARE add_role_stmt FROM @add_role_sql;
EXECUTE add_role_stmt;
DEALLOCATE PREPARE add_role_stmt;
