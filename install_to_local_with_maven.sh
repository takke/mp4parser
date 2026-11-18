#!/bin/sh
#
# カスタム版 mp4parser をローカル Maven リポジトリ (~/.m2) にインストールする
#
# - Java 8 でビルドする
# - Maven は PATH 上のものではなく Maven Wrapper (./mvnw) を使う。バージョンは .mvn/wrapper/maven-wrapper.properties で固定している
#   (Maven 3.10 では aspectj-maven-plugin 1.5 の tools.jar の解決に失敗して BUILD FAILURE になるため、3.9 系に固定)

set -e
cd "$(dirname "$0")"

#export JAVA_HOME=/c/Users/takke/.jdks/corretto-1.8.0_292
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
./mvnw clean install -Dmaven.test.skip=true
