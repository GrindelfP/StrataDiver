#!/bin/zsh
# Render the game for N frames and write the result to a PNG, so a change to the renderer
# can be *looked at* rather than asserted about.
#
#   tools/shot.sh [frames] [out.png]
#
# Needs -XstartOnFirstThread for the same reason tools/probe.sh does. The window is real
# but never shown to anyone: frames are drawn, the framebuffer is read back and written out.

set -e

export JAVA_HOME=$(/usr/libexec/java_home -v 23)

mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt -DincludeScope=runtime

CP="$(cat target/probe-classpath.txt):target/classes"

OUT=$(mktemp -d)
kotlinc tools/Shot.kt -cp "$CP" -d "$OUT"
java -XstartOnFirstThread ${=JAVA_OPTS} -cp "$OUT:$CP" shot.ShotKt "$@"