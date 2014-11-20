#!/bin/bash -x

files="./src/hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/DFSClient.java \
./src/hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/DFSInputStream.java \
./src/hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/namenode/NameNode.java \
./src/hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/DistributedFileSystem.java"

usage="./create_hdpatch.sh <orig> <new>"

if [ $# -lt 2 ]; then
    echo $usage
    exit 1
fi

file1=`echo $files | cut -d' ' -f 1`

orig=$1
if [ ! -e $orig/$file1 ]; then
    echo "Can't find `basename $file1` in $orig"
    exit 1
fi
shift
new=$1
if [ ! -e $new/$file1 ]; then
    echo "Can't find `basename $file1` in $new"
    exit 1
fi

if [ $orig/$file1 = $new/$file1 ];then
    echo "<orig> and <new> locations cannot be the same!"
    exit 1
fi

patch=extendable_client.patch

for f in $files; do
    install -D $orig/$f orig/$f
    install -D $new/$f new/$f
done

diff -ru orig new > $patch
rm -rf orig
rm -rf new
