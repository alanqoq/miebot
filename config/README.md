# Database configuration files

`compose.yaml` mounts this directory at `/data/config` as a writable directory.
The application manages `onboarding.json`, which records the first-use wizard
stage, and `database.json`, which records the active database and stores server
passwords encrypted with `app-secret.key`. On first startup the application
creates the state and key files automatically. Do not edit these files while the
service is running, and keep `app-secret.key` with database backups.

For a file-driven hot switch, copy one of the examples to
`database-candidate.json`, adjust it, and create `database-password` when a
server database is selected. In the Web administration system page, test and
reload the candidate. The active file is replaced only after connection, schema,
read, and rollback-based write checks pass.

On Debian, the container runs as UID/GID `10001`. The `qqbot-prepare` Compose
service creates this directory, applies owner-only permissions, and makes it
writable by that identity before the application starts. For a manually restored
or pre-created directory, the equivalent commands are:

```bash
sudo chown -R 10001:10001 config
sudo chmod 0700 config
sudo chmod 0600 config/database-candidate.json 2>/dev/null || true
sudo chmod 0400 config/database-password 2>/dev/null || true
```
