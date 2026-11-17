#!/bin/sh
set -eu
umask 077

# The application is installed only under this user's own RunZone directory.
app_root="$HOME/apps/runzone"
release_id="${1:?release id required}"
public_url="${2:?HTTPS origin required}"
case "$release_id" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid release id' >&2; exit 1;; esac
case "$public_url" in https://*[!a-zA-Z0-9.:/-]*|*' '*|*'?'*|*'#'*) echo 'Invalid public origin' >&2; exit 1;; https://*) ;; *) echo 'HTTPS origin required' >&2; exit 1;; esac

mkdir -p "$app_root/releases" "$app_root/runtime" "$app_root/shared" "$app_root/uploads" "$HOME/.config/systemd/user"
chmod 700 "$app_root" "$app_root/shared"
release_dir="$app_root/releases/$release_id"
if [ ! -d "$release_dir" ]; then
    mkdir "$release_dir"
    tar -xzf "$app_root/uploads/$release_id.tar.gz" -C "$release_dir"
fi

# A private Node runtime avoids upgrading or changing any existing server app.
node_archive='node-v24.22.3-linux-x64.tar.xz'
node_sha='4f2b175a6d53a5c617a80dba2676238a0688cef28c6da4eba35c7d844e66fcfb'
node_dir="$app_root/runtime/node-v24.22.3-linux-x64"
if [ ! -x "$node_dir/bin/node" ]; then
    curl --fail --silent --show-error --location "https://nodejs.org/dist/v24.22.3/$node_archive" -o "$app_root/uploads/$node_archive"
    printf '%s  %s\n' "$node_sha" "$app_root/uploads/$node_archive" | sha256sum --check --status
    tar -xJf "$app_root/uploads/$node_archive" -C "$app_root/runtime"
fi
ln -sfn "$node_dir" "$app_root/runtime/node"

"$node_dir/bin/node" --test "$release_dir/broker.test.mjs"

if [ ! -f "$app_root/shared/service.env" ]; then
    printf 'PUBLIC_URL=%s\nHOST=127.0.0.1\nPORT=8787\nSTRAVA_CLIENT_ID=\nSTRAVA_CLIENT_SECRET=\nSMASHRUN_CLIENT_ID=\nSMASHRUN_CLIENT_SECRET=\nRUNALYZE_CLIENT_ID=\nRUNALYZE_CLIENT_SECRET=\n' "$public_url" > "$app_root/shared/service.env"
fi
chmod 600 "$app_root/shared/service.env"

previous=''
if [ -L "$app_root/current" ]; then previous=$(readlink "$app_root/current"); fi
if [ -n "$previous" ]; then printf '%s\n' "$previous" > "$app_root/shared/previous-release"; fi
ln -sfn "$release_dir" "$app_root/current"
install -m 600 "$release_dir/deploy/runzone-accounts.service" "$HOME/.config/systemd/user/runzone-accounts.service"
systemctl --user daemon-reload
systemctl --user enable runzone-accounts.service
systemctl --user restart runzone-accounts.service

healthy=false
for attempt in 1 2 3 4 5; do
    if curl --fail --silent http://127.0.0.1:8787/health; then healthy=true; break; fi
    sleep 1
done
if [ "$healthy" != true ]; then
    if [ -n "$previous" ]; then ln -sfn "$previous" "$app_root/current"; systemctl --user restart runzone-accounts.service; fi
    echo 'RunZone health check failed' >&2
    exit 1
fi
systemctl --user show runzone-accounts.service -p ActiveState -p MemoryCurrent -p MemoryMax
