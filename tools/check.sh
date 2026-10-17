#!/bin/zsh
# Compile the game and run the headless checks. Same incantation AGENTS.md documents,
# with the failure-to-exit-code handling it omits.
#
#   tools/check.sh
#
# `export` rather than the `JAVA_HOME=... mvn` prefix form: the prefix does not survive
# into a later `&&` branch and the build dies on IllegalArgumentException: 27.
set -e

export JAVA_HOME=$(/usr/libexec/java_home -v 23)

mvn -q compile

CP=~/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.0/kotlin-stdlib-1.9.0.jar:~/.m2/repository/org/joml/joml/1.10.5/joml-1.10.5.jar:~/.m2/repository/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar:target/classes

OUT=$(mktemp -d)
kotlinc tools/Check.kt -cp "$CP" -d "$OUT"
java -cp "$OUT:$CP" check.CheckKt
