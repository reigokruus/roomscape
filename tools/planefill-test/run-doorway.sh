#!/bin/zsh
# OutlineFill (walls to the room outline, test only) on saved S24 scans: ./run-outline.sh <out folder> <scan folder>...
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
S=$D/../../android/app/src/main/java/com/hackathon/setscan
mkdir -p $T/src/com/hackathon/setscan
awk '/static org.json.JSONArray toJson/{skip=1} skip&&/^    }$/{skip=0; next} !skip' $S/Planes.java > $T/src/com/hackathon/setscan/Planes.java
cp $S/OutlineFill.java $S/DoorwayFinder.java $D/DoorwayTest.java $T/src/com/hackathon/setscan/
$J/javac -d $T/out $T/src/com/hackathon/setscan/*.java && $J/java -Djava.awt.headless=true -Xmx3g -cp $T/out com.hackathon.setscan.DoorwayTest "$@"
