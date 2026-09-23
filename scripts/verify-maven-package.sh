#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <branch>" >&2
  echo "Example: $0 develop" >&2
  exit 1
fi

branch="$1"
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_url="$(git -C "$repo_dir" remote get-url origin)"
checkout_dir="$(mktemp -d)"
safe_branch="${branch//\//_}"
log_dir="$repo_dir/target"
log_file="$log_dir/verify-maven-package-${safe_branch}-$(date -u +%Y%m%dT%H%M%SZ).log"
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

echo "GitHub Packages credentials found."

trap 'rm -rf "$checkout_dir"' EXIT

mkdir -p "$log_dir"
git clone --branch "$branch" --depth 1 "$repo_url" "$checkout_dir"

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

echo "Writing Maven output to $log_file"

if ! docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --env MAVEN_CONFIG=/tmp/.m2 \
  --volume "$checkout_dir:/workspace" \
  --workdir /workspace \
  maven:3.9.11-eclipse-temurin-21 \
  mvn --settings settings.xml --batch-mode --no-transfer-progress -Dgpg.skip=true verify 2>&1 | tee "$log_file"; then
  echo "Verification failed. Log saved to $log_file" >&2
  exit 1
fi

echo "Verification passed. Log saved to $log_file"
