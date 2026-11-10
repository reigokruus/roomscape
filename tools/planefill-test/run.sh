#!/bin/zsh
# PlaneFill (S24 plain wall/floor gap fill) on saved scans, on the Mac: ./run.sh <out folder> <scan folder>...
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
S=$D/../../android/app/src/main/java/com/hackathon/setscan
mkdir -p $T/src/com/hackathon/setscan
cp $S/PlaneFill.java $D/PlaneFillTest.java $T/src/com/hackathon/setscan/
# Planes without its org.json export (not on the desktop JDK)
awk '/static org.json.JSONArray toJson/{skip=1} skip&&/^    }$/{skip=0; next} !skip' $S/Planes.java > $T/src/com/hackathon/setscan/Planes.java
$J/javac -d $T/out $T/src/com/hackathon/setscan/*.java && $J/java -Xmx3g -cp $T/out com.hackathon.setscan.PlaneFillTest "$@"
