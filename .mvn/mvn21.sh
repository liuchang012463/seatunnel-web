#!/usr/bin/env bash
# Invoke Maven with JDK 21, bypassing the broken mvn shell script (Git Bash
# path translation) and the unavailable .mvn/wrapper jar.
JAVA_BIN=/d/Programmings/Java/jdk21u010/bin/java
MVN_HOME=D:/Programmings/apache-maven-3.9.11
"$JAVA_BIN" \
  -cp "$MVN_HOME/boot/plexus-classworlds-2.9.0.jar" \
  -Dclassworlds.conf="$MVN_HOME/bin/m2.conf" \
  -Dmaven.home="$MVN_HOME" \
  -Dmaven.multiModuleProjectDirectory="E:/QLWorkProjects/huo/seatunnel-web" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
