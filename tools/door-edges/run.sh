#!/bin/zsh
# EdgeRefine (doorway edges from the photos) on saved S24 scans: ./run.sh <scan folder>...
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
S=$D/../../android/app/src/main/java/com/hackathon/setscan
mkdir -p $T/src; cp $S/EdgeRefine.java $S/OutlineFill.java $D/EdgeTest.java $T/src/
$J/javac -d $T/out $T/src/*.java && $J/java -Djava.awt.headless=true -Xmx3g -cp $T/out com.hackathon.setscan.EdgeTest "$@"
