#!/bin/sh
#export JAVA_HOME=/c/Users/takke/.jdks/corretto-1.8.0_292
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
mvn clean install -Dmaven.test.skip=true
