package edu.ucsd.t2.hdfs.xrootd.healer;

// a struct to hold block meta info for fsck parsing streams
public class Block {
  public String name;
  public String host;
  public String path;
  public int length;
  public long offset;

  public Block() {
    this.name = null;
    this.host = null;
    this.path = null;
    this.length = 0;
    this.offset = 0;
  }
}
