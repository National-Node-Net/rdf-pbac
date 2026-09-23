#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 v<version>" >&2
  exit 1
fi


tag="$1"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_url="$(git -C "$repo_dir" remote get-url origin)"
checkout_dir="$(mktemp -d)"
github_user="${GITHUB_PACKAGES_USER:-}"
github_token="${GITHUB_PACKAGES_TOKEN:-}"

if [[ -z "$github_user" ]]; then
  read -r -p "GitHub username: " github_user
fi

if [[ -z "$github_token" ]]; then
  read -r -s -p "GitHub Packages token: " github_token
  echo
fi

if [[ -z "$github_user" || -z "$github_token" ]]; then
  echo "A GitHub username and Packages token are required." >&2
  exit 1
fi

trap 'rm -rf "$checkout_dir"' EXIT

git clone --no-checkout --branch "$tag" --depth 1 "$repo_url" "$checkout_dir"
git -C "$checkout_dir" -c advice.detachedHead=false checkout "$tag"

cat > "$checkout_dir/settings.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${github_user}</username>
      <password>${github_token}</password>
    </server>
  </servers>
</settings>
EOF

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --env MAVEN_CONFIG=/tmp/.m2 \
  --volume "$checkout_dir:/workspace" \
  --workdir /workspace \
  maven:3.9.11-eclipse-temurin-21 \
  mvn --settings settings.xml --batch-mode --no-transfer-progress -Dgpg.skip=true deploy
