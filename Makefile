all: fallback healer

fallback:
	$(MAKE) -C hdfs-xrootd-fallback

healer:
	$(MAKE) -C hdfs-xrootd-healer

clean:
	$(MAKE) -C hdfs-xrootd-fallback clean
	$(MAKE) -C hdfs-xrootd-healer clean
