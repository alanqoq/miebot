# Legacy Docker secrets directory

The Compose deployment no longer requires a manually prepared Docker Secret.
On first startup the application creates a Base64-encoded 32-byte AES key at
`config/app-secret.key` with owner-only permissions where POSIX permissions are
available.

Keep that generated file together with database backups. Replacing or losing it makes
previously encrypted bot AppSecrets and persisted database passwords unreadable.
