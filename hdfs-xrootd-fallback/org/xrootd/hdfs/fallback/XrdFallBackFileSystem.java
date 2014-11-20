package org.xrootd.hdfs.fallback;

import java.io.IOException;
import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DistributedFileSystem;

public class XrdFallBackFileSystem extends DistributedFileSystem {
  public void initialize(URI uri, Configuration conf) throws IOException {
    super.initialize(uri, conf);
    this.dfs = new XFBFSClient(uri, conf, statistics);
  }
  public String getScheme() {
    return "xfbfs";
  }
}
