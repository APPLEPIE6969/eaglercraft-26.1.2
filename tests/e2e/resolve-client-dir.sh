#!/usr/bin/env bash
# Resolves the directory that holds the built EaglerCraftX client.
#
# Usage: resolve-client-dir.sh <artifact-dir>
#
# The upload-artifact layout differs depending on how the artifact was
# produced, so both the "contents at the root" and the "public/ prefix"
# layouts are accepted. The resolved path is printed on stdout.
set -euo pipefail

artifact_dir="${1:-e2e-artifact}"

if [ -f "${artifact_dir}/index.html" ]; then
  printf '%s\n' "${artifact_dir}"
  exit 0
fi

if [ -f "${artifact_dir}/public/index.html" ]; then
  printf '%s\n' "${artifact_dir}/public"
  exit 0
fi

echo "::error::No index.html found in ${artifact_dir}" >&2
exit 1
