#!/bin/sh
# Minimal Gradle wrapper launcher.
# It needs gradle/wrapper/gradle-wrapper.jar. If that jar is missing, create it once with:
#     gradle wrapper --gradle-version 8.7
# (the GitHub Actions workflow does this automatically; that command also replaces this script
#  with the official, full-featured gradlew).
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle/wrapper/gradle-wrapper.jar is missing." >&2
    echo "Run once:  gradle wrapper --gradle-version 8.7   (or let GitHub Actions build it)." >&2
    exit 1
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
