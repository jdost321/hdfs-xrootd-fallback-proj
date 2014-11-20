#!/bin/bash

# most of this is shamelessly stolen from
# /usr/bin/hadoop
# /usr/lib/hadoop/bin/hadoop

# set JAVA_HOME
. /usr/libexec/bigtop-detect-javahome

# hack to include our jar in CLASSPATH
HADOOP_CLASSPATH=`dirname $0`/hdfs-xrootd-healer.jar

export HADOOP_LIBEXEC_DIR=/usr/lib/hadoop/libexec
. $HADOOP_LIBEXEC_DIR/hadoop-config.sh

# Always respect HADOOP_OPTS and HADOOP_CLIENT_OPTS
HADOOP_OPTS="$HADOOP_OPTS $HADOOP_CLIENT_OPTS"

# not sure what it is for but real hadoop client sets it
#make sure security appender is turned off
HADOOP_OPTS="$HADOOP_OPTS -Dhadoop.security.logger=${HADOOP_SECURITY_LOGGER:-INFO,NullAppender}"

export CLASSPATH=$CLASSPATH

exec "$JAVA" $JAVA_HEAP_MAX $HADOOP_OPTS edu.ucsd.t2.hdfs.xrootd.healer.BlockHealer "$@"
