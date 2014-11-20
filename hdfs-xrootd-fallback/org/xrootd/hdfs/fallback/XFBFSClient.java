package org.xrootd.hdfs.fallback;

import java.io.IOException;
import java.net.URI;
import java.io.File;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInputStream;

public class XFBFSClient extends DFSClient {
  private static class Config {
    final private String[] namespace;
    
    public Config(Configuration conf) {
      namespace = conf.getStrings("xfbfs.namespace");
    }
  }
  
  private static final Config conf;
  // Internal emergency break
  private static final String KILLSWITCH = "/etc/hadoop/conf/xfbfs-stop";
  
  static {
    Configuration c = new Configuration(false);
    c.addResource("xfbfs-site.xml");
    conf = new Config(c);
    System.out.println("XFBFSClient config parsed");
  }
  
  public XFBFSClient(URI nameNodeUri, Configuration conf,
      FileSystem.Statistics stats) throws IOException {
          super(nameNodeUri, conf, stats);
  }
  public DFSInputStream open(String src, int buffersize, boolean verifyChecksum)
      throws IOException, UnresolvedLinkException {
    checkOpen();
    //    Get block info from namenode
    
    // Only create XFBFSInputStream if file is in our whitelist
    if (! new File(KILLSWITCH).exists() && conf.namespace != null) {
      for (String s: conf.namespace) {
        if (src.startsWith(s))
          return new XFBFSInputStream(this, src, buffersize, verifyChecksum);
      }
    }
    return new DFSInputStream(this, src, buffersize, verifyChecksum);
  }

}
