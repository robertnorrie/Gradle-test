#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

directory="$(dirname "$(dirname "$(readlink -f "$0")")")"

rm -f sources.tar.gz
tar -czf sources.tar.gz -C "$directory" \
  src/main/java \
  src/main/resources \
  lib/models/src/main/java \
  lib/models/config/template-unix.txt \
  data/ \
  \
  build.gradle.kts settings.gradle.kts \
  lib/models/build.gradle.kts lib/models/settings.gradle.kts