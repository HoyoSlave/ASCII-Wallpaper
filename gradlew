#!/bin/sh
# Gradle wrapper fallback script that delegates to gradle or wrapper jar
DIR="$( cd "$( dirname "$0" )" && pwd )"
if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -Xmx64m -Xms64m -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    echo "Error: Neither gradle-wrapper.jar nor system gradle found." >&2
    exit 1
fi
