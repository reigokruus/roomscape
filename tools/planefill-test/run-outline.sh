#!/bin/zsh
# OutlineFill (walls to the room outline, test only) on saved S24 scans: ./run-outline.sh <out folder> <scan folder>...
D=${0:A:h}; T=$(mktemp -d); J=$(ls -d ~/DEV/tools/jdk-21*/Contents/Home)/bin
S=$D/../../android/app/src/main/java/com/hackathon/setscan
mkdir -p $T/src/com/hackathon/setscan
cp $S/OutlineFill.java $D/OutlineFillTest.java $T/src/com/hackathon/setscan/
$J/javac -d $T/out $T/src/com/hackathon/setscan/*.java && $J/java -Djava.awt.headless=true -Xmx3g -cp $T/out com.hackathon.setscan.OutlineFillTest "$@"
