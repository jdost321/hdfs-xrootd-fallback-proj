hd_common_jar := /usr/lib/hadoop/hadoop-common.jar
hd_hdfs_jar := /usr/lib/hadoop-hdfs/hadoop-hdfs.jar
hd_annotations_jar := /usr/lib/hadoop/hadoop-annotations.jar

all: hdfs-xrootd-healer.jar 

clean:
	rm -f edu/ucsd/t2/hdfs/xrootd/healer/*.class *.jar

hdfs-xrootd-healer.jar: edu/ucsd/t2/hdfs/xrootd/healer/BlockHealer.class \
edu/ucsd/t2/hdfs/xrootd/healer/Block.class \
edu/ucsd/t2/hdfs/xrootd/healer/Config.class \
edu/ucsd/t2/hdfs/xrootd/healer/Logger.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$BadBlockStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$BadFileStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$BlockSizeStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$BlockStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$MessageStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$NullStream.class \
edu/ucsd/t2/hdfs/xrootd/healer/HdfsUtil$$NullStream$$1.class \
square_pusher.sh
	jar cvf $@ $(patsubst %,'%',$^)

%.class: %.java
	javac -classpath .:$(hd_common_jar):$(hd_hdfs_jar):$(hd_annotations_jar) $<
