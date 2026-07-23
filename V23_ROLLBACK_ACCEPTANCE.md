# V23 rollback acceptance

V23 only adds four nullable columns and one secondary index to `chat_attachment`. It does not rename, delete, or change any V6.2 column, so rollback is an application rollback rather than a reverse database migration.

Rollback sequence:

1. Set `AUTH_LOGIN_V63_ENABLED=false`, `CHAT_WORKSPACE_DROP_ENABLED=false`, and `CHAT_FOLDER_ATTACHMENTS_ENABLED=false`.
2. Deploy the last V6.2 application image.
3. Leave Flyway schema history and the nullable V23 columns in place.
4. Verify login, ordinary single-file upload, send, and history reads. V6.2 ignores the additional columns.

The automated `V23MigrationRollbackContractTest` rejects destructive or required-column changes in the V23 migration.
