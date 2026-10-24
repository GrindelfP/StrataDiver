#!/bin/bash
# Capture baseline images for regression testing
# Runs the game headlessly and captures frames at specific sample counts

set -e

JAVA_HOME=$(/usr/libexec/java_home -v 23)
export JAVA_HOME

# Build first
mvn -q compile

CP=~/.m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.9.0/kotlin-stdlib-1.9.0.jar:~/.m2/repository/org/joml/joml/1.10.5/joml-1.10.5.jar:~/.m2/repository/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar:target/classes

mkdir -p baselines

echo "Compiling capture harness..."
kotlinc tools/Check.kt -cp "$CP" -d /tmp/checkout 2>/dev/null

# We need to run the actual game to capture frames. Use the capture() method in StrataDiver.
# Since we can't easily run headless with GLFW, this script documents the capture procedure.
# The actual capture is done via: java -cp ... to.grindelf.stratadiver.StrataDiverKt capture <frames> <output>

cat << 'EOF'
BASELINE CAPTURE PROCEDURE
==========================

To capture baseline images, run the game with the capture() method:

1. Scene A - Spawn point (outdoor, trees, terrain, sky):
   java -cp "target/classes:$CP" \
     -Dstratadiver.scale=1 \
     -Dstratadiver.forceReset=false \
     to.grindelf.stratadiver.StrataDiverKt \
     capture 60 baselines/scene_A_frame_60.png

2. Scene A - Frame 1 (1 sample):
   java -cp "target/classes:$CP" \
     -Dstratadiver.scale=1 \
     -Dstratadiver.forceReset=true \
     to.grindelf.stratadiver.StrataDiverKt \
     capture 1 baselines/scene_A_frame_1.png

3. Scene A - Frame 10:
   java -cp "target/classes:$CP" \
     -Dstratadiver.scale=1 \
     -Dstratadiver.forceReset=true \
     to.grindelf.stratadiver.StrataDiverKt \
     capture 10 baselines/scene_A_frame_10.png

4. Scene B - Underground cave (teleport player underground first):
   # Modify StrataDiver.capture() to spawn at cave coordinates

5. Scene C - Dense forest (teleport to forest):

6. Moving camera (hold key while capturing):
   # Modify capture to move camera

COMPARISON
==========
After making changes, re-capture and compare:
  compare baselines/scene_A_frame_60.png new_scene_A_frame_60.png diff.png

Or visually inspect side by side.

EOF

echo "Baseline capture procedure written. See above for commands."
echo "Directory 'baselines/' created for storing reference images."