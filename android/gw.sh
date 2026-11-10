#!/usr/bin/env bash
# Gradle senza daemon (vedi gradle.properties). Uso: ./gw.sh :app:assembleDebug
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${GW_JAVA_HOME:-$HOME/.jdks/jdk-21.0.13+9}"
export GRADLE_OPTS="-Xmx3g -Xms256m -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
GR=gradle  # non ./gradlew: il wrapper non applica il javaagent e Gradle forka un daemon
# 16 core: ne lascia 4 liberi (core 12-15) e abbassa la priorita' per non rallentare il PC
exec nice -n 10 taskset -c 0-11 "$GR" --no-daemon ${GW_OFFLINE---offline} -Dorg.gradle.workers.max=8 -Dorg.gradle.java.home="$JAVA_HOME" "$@"
