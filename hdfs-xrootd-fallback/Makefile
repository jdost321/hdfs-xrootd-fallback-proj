# Grrr, how can I get this from java??? Like java-config or sth.
# JJ := /usr/lib/jvm/java-1.6.0-sun-1.6.0.45
# JJ := /usr/lib/jvm/java-1.7.0-openjdk.x86_64
# Mysteriously, this works, too:
JJ := ${JAVA_HOME}

XRD := /usr

CXXFLAGS := -O2 -fPIC -g

vers := 1.0.0
xbf_maj_vers := 1
xbf_vers := $(xbf_maj_vers).0.0

prefix := /usr/local
sysconfdir := $(prefix)/etc
hd_common_jar := /usr/lib/hadoop/hadoop-common.jar
hd_annotations_jar := /usr/lib/hadoop/hadoop-annotations.jar
hd_hdfs_jar := /usr/lib/hadoop-hdfs/hadoop-hdfs.jar

all: hdfs-xrootd-fallback-$(vers).jar libXrdBlockFetcher.so.$(xbf_vers)

clean:
	rm -f *.class org/xrootd/hdfs/fallback/*.class *.so* *.o *.h *.jar

hdfs-xrootd-fallback-$(vers).jar: org/xrootd/hdfs/fallback/XrdFallBackFileSystem.class \
org/xrootd/hdfs/fallback/XFBFSClient.class org/xrootd/hdfs/fallback/XFBFSInputStream.class \
org/xrootd/hdfs/fallback/XFBFSClient$$Config.class org/xrootd/hdfs/fallback/XrdBlockFetcher.class \
org/xrootd/hdfs/fallback/XrdUdpLog.class
	jar cvf $@ $(patsubst %,'%',$^)

%.class: %.java
	javac -classpath .:$(hd_common_jar):$(hd_annotations_jar):$(hd_hdfs_jar) $<

%.h: org/xrootd/hdfs/fallback/%.class
	javah -jni -classpath .:$(hd_common_jar) -force -o $@ $(subst /,.,$(basename $<))

%.o: %.cxx %.h
	g++ -I${JJ}/include -I${JJ}/include/linux -I${XRD}/include/xrootd ${CXXFLAGS} -c $<

libXrdBlockFetcher.so.$(xbf_vers): XrdBlockFetcher.o
	g++ -shared -Wl,-soname,libXrdBlockFetcher.so.$(xbf_maj_vers) -o $@ -L${XRD}/lib64 -lXrdClient -lpcrecpp -pthread $<

test: XBFTest.class

XBFTest.class: XBFTest.java
	javac $<

.PRECIOUS: %.h %.o

test_install:
	cp hadoop-hdfs-2.0.0-cdh4.1.1.jar /usr/lib/hadoop-hdfs/
	cp hdfs-xrootd-fallback-$(vers).jar /usr/lib/hadoop/client/
	cp libXrdBlockFetcher.so.$(xbf_vers) /usr/lib/

install: all
	mkdir -p $(DESTDIR)$(prefix)/lib/hadoop/client/
	install -m 644 hdfs-xrootd-fallback-$(vers).jar $(DESTDIR)$(prefix)/lib/hadoop/client/
	mkdir -p $(DESTDIR)$(prefix)/lib64/
	install libXrdBlockFetcher.so.$(xbf_vers) $(DESTDIR)$(prefix)/lib64/
	cd $(DESTDIR)$(prefix)/lib64/ && \
	ln -s -f libXrdBlockFetcher.so.$(xbf_vers) libXrdBlockFetcher.so.$(xbf_maj_vers) && \
	ln -s -f libXrdBlockFetcher.so.$(xbf_maj_vers) libXrdBlockFetcher.so
	mkdir -p $(DESTDIR)$(sysconfdir)/hadoop/conf.osg/
	install -m 644 xfbfs-site.xml $(DESTDIR)$(sysconfdir)/hadoop/conf.osg/

uninstall:
	rm -f $(DESTDIR)$(prefix)/lib/hadoop/client/hdfs-xrootd-fallback-$(vers).jar
	rm -f $(DESTDIR)$(prefix)/lib64/libXrdBlockFetcher.so*
	rm -f $(DESTDIR)$(sysconfdir)/hadoop/conf.osg/xfbfs-site.xml

dist:
	rsync -avR org/xrootd/hdfs/fallback/*.java *.cxx Makefile LICENSE README xfbfs-site.xml hdfs-xrootd-fallback-$(vers)/
	tar -czf hdfs-xrootd-fallback-$(vers).tar.gz hdfs-xrootd-fallback-$(vers)
	rm -rf hdfs-xrootd-fallback-$(vers)
