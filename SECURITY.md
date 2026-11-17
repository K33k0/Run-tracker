# Secrets and private data

The repository contains source code and blank configuration examples. Real provider app secrets belong in the server's owner-only environment file. User access and refresh tokens are encrypted on each phone with Android Keystore; they must never be committed.

Do not commit `.env` files, `service.env`, signing keys, device backups, run recordings, location screenshots, authentication logs or exported TCX files. Build output and local tools are ignored. The hosted account service URL is intentionally public and is not an authentication credential.

Before publishing a change, run a full-history Gitleaks scan. CI also runs a pinned, checksum-verified Gitleaks release with redacted output. Scanner results reduce the risk of accidental disclosure; they are not a guarantee that every kind of sensitive data will be detected.

If a credential is ever committed, revoke or rotate it immediately, remove it from the source and history, and check cached artifacts/logs before republishing. Do not paste a live secret into a public issue or pull request.
