#!/bin/zsh
# What this machine's OpenGL can do, asked by doing it. Needs a GL context, so unlike
# check.sh it needs -XstartOnFirstThread: GLFW on macOS puts its event loop and its GL
# context on the first thread of the process.
#
#   tools/probe.sh
#
# The classpath comes from Maven rather than from a hand-written list. check.sh writes its
# jars out because it predates this; adding a fourth by hand is how a diagnostic starts
# failing for a reason that has nothing to do with what it is diagnosing. LWJGL 3.3.3 also
# merged its OpenGL loader into lwjgl-opengl, so there is no openglloader jar to add — which
# is the kind of thing that costs twenty minutes if you assume one is there.

set -e

export JAVA_HOME=$(/usr/libexec/java_home -v 23)

mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/probe-classpath.txt -DincludeScope=runtime

CP="$(cat target/probe-classpath.txt):target/classes"

OUT=$(mktemp -d)
kotlinc tools/Probe.kt -cp "$CP" -d "$OUT"
java -XstartOnFirstThread -cp "$OUT:$CP" probe.ProbeKt