#! /bin/bash

export JAVA_HOME=/Users/forax/leyden-premain/leyden/build/macosx-aarch64-server-release/images/jdk

if [[ -f mvn.jsa ]]
then
  echo "replay"
  # -Xlog:sca*=warning:file=replay.dump.log
  export MAVEN_OPTS="-XX:SharedArchiveFile=mvn.jsa -XX:SharedCodeArchive=mvn.jsca -XX:+ReplayTraining -XX:+LoadSharedCode -XX:ReservedSharedCodeSize=1000M";
  mvn "$@"
else
  echo "training"
  # -Xlog:cds*=warning:file=training.dump.log
  export MAVEN_OPTS="-XX:ArchiveClassesAtExit=mvn.jsa -XX:SharedCodeArchive=mvn.jsca -XX:+ArchiveInvokeDynamic -XX:+RecordTraining -XX:+StoreSharedCode -XX:ReservedSharedCodeSize=1000M";
  mvn "$@"
fi



