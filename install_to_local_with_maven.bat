set JAVA_HOME=c:\Users\takke\.jdks\corretto-1.8.0_292
rem Use Maven Wrapper (pinned to Maven 3.9.x) instead of mvn on PATH: the build fails with Maven 3.10.
call mvnw.cmd clean install -Dmaven.test.skip=true
