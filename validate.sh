#!/usr/bin/env bash
set -euo pipefail

echo '=== Step 1: Build + Java Tests ==='
./gradlew clean build

echo '=== Step 2: JavaScript Tests ==='
cd src/test/js && npm ci && npm test && cd -

echo '=== Step 3: Bash Tests ==='
bash src/test/bash/test_auto_update.sh
if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
  bash src/test/bash/test_docker_compose.sh
else
  echo 'WARNING: Docker not available, skipping test_docker_compose.sh'
fi

echo '=== Step 4: Flyway Migration Test ==='
mkdir -p data
rm -f data/test-migration.db
./gradlew flywayMigrate
echo 'Migrations succeeded. Cleaning up test DB...'
rm -f data/test-migration.db

echo '=== All validations passed ==='
