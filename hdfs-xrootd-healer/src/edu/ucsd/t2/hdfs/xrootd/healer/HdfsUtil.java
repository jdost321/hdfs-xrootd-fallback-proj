package edu.ucsd.t2.hdfs.xrootd.healer;

import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.fs.FsShell;
import org.apache.hadoop.hdfs.tools.DFSck;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.zip.Adler32;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HdfsUtil {
  // dummy class that does nothing on println (java imitation of /dev/null)
  private static class NullStream extends PrintStream {
    public NullStream() {
      super(new OutputStream(){public void write(int i){}});
    }
  }

  // class to capture println output and store it in a string
  // only stores results of last println call
  private static class MessageStream extends NullStream {
    private String msg;
    public MessageStream() {
      msg = "";
    }
    public void println(String s) {
      msg = s;
    }
    public String getMessage() {
      return msg;
    }
  }

  // assumes fsck with no args
  private static class BadFileStream extends NullStream {
    private ArrayList<String> badFiles;
    private long offset;
    public BadFileStream() {
      badFiles = new ArrayList<String>();
    }
    public void println(String s) {
      if (s.matches(".*MISSING.*blocks.*")) {
        String[] fields = s.split(":");

        badFiles.add(fields[0]);
      }
    }
    public ArrayList<String> getFiles() {
      return badFiles;
    }
  }

  // assumes path is already validated
  // no error handling here becaues hdfs fsck exit codes are useless
  public static ArrayList<String> findBadFiles(HdfsConfiguration conf, String path) throws Exception {
    BadFileStream bfs = new BadFileStream();
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    // DFSck sets output stream in constructor so change it first
    System.setOut(bfs);
    DFSck fsck = new DFSck(conf);
    System.setOut(stdout);
    System.setErr(new NullStream());
    ToolRunner.run(fsck, new String[]{path});
    System.setErr(stderr);

    return bfs.getFiles();
  }

  // assumes -blocks -files -locations passed to fsck
  private static class BlockStream extends NullStream {
    private ArrayList<Block> blocks;
    public BlockStream() {
      blocks = new ArrayList<Block>();
    }
    public void println(String s) {
      if (s.contains("len=")) {
        String[] fields = s.split(" ");
        Block b = new Block();
        b.name = fields[1].split(":")[1];

        // check if block is already broken, it is possible..
        // in that case, host will be null
        if (!s.endsWith("MISSING!")) {
          b.host = fields[4].split(":")[0].substring(1);
        }
        blocks.add(b);
      }
    }
    public ArrayList<Block> getBlocks() {
      return blocks;
    }
  }

  public static ArrayList<Block> findBlocks(HdfsConfiguration conf, String file) throws Exception {
    BlockStream bs = new BlockStream();
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    // DFSck sets output stream in constructor so change it first
    System.setOut(bs);
    DFSck fsck = new DFSck(conf);
    System.setOut(stdout);
    System.setErr(new NullStream());
    ToolRunner.run(fsck, new String[]{file, "-files", "-blocks",
      "-locations"});
    System.setErr(stderr);

    return bs.getBlocks();
  }

  // assumes -blocks -files passed to fsck
  private static class BadBlockStream extends NullStream {
    private ArrayList<Block> badBlocks;
    private long offset;
    public BadBlockStream() {
      badBlocks = new ArrayList<Block>();
      offset = 0;
    }
    public void println(String s) {
      if (s.contains("len=")) {
        String[] fields = s.split(" ");
        int length = Integer.parseInt(fields[2].substring(4));

        if (s.endsWith("MISSING!")) {
          Block bb = new Block();
          bb.name = fields[1].split(":")[1];
          bb.length = length;
          bb.offset = offset;
          badBlocks.add(bb);
        }
        offset += length;
      }
    }
    public ArrayList<Block> getBlocks() {
      return badBlocks;
    }
  }

  public static ArrayList<Block> findBadBlocks(HdfsConfiguration conf, String file) throws Exception {
    BadBlockStream bbs = new BadBlockStream();
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    // DFSck sets output stream in constructor so change it first
    System.setOut(bbs);
    DFSck fsck = new DFSck(conf);
    System.setOut(stdout);
    System.setErr(new NullStream());
    ToolRunner.run(fsck, new String[]{file, "-files", "-blocks"});
    System.setErr(stderr);

    return bbs.getBlocks();
  }

  // captures output of hadoop fs -stat %o <file>
  private static class BlockSizeStream extends NullStream {
    private int size;
    public BlockSizeStream() {
      size = 0;
    }
    public void println(String s) {
      size = Integer.parseInt(s);
    }
    public int getSize() {
      return size;
    }
  }

  public static int getBlockSize(FsShell shell, String file) throws Exception {
    BlockSizeStream bss = new BlockSizeStream();
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    // FsShell seems to set output stream on run rather than constructor
    System.setOut(bss);
    System.setErr(new NullStream());
    int retVal = ToolRunner.run(shell, new String[]{"-stat", "%o", file});
    System.setOut(stdout);
    System.setErr(stderr);
    return retVal == 0 ? bss.getSize() : -1;
  }

  // wrapper for hadoop -put to provide exception handling
  public static void put(FsShell shell, int blockSize, String file, String dest) throws Exception {
    PrintStream stderr = System.err;
    MessageStream ms = new MessageStream();
    System.setErr(ms);
    int retVal = ToolRunner.run(shell, new String[]{"-Ddfs.replication=1", "-Ddfs.blocksize=" + blockSize, "-put",
      file, dest});
    System.setErr(stderr);

    if (retVal != 0) {
      throw new IOException(ms.getMessage());
    }
  }

  // wrapper for hadoop -rm to provide exception handling and supress useless stdout messages
  public static void rm(FsShell shell, String file) throws Exception {
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    MessageStream ms = new MessageStream();
    System.setOut(new NullStream());
    System.setErr(ms);
    int retVal = ToolRunner.run(shell, new String[]{"-rm", file});
    System.setOut(stdout);
    System.setErr(stderr);

    if (retVal != 0) {
      throw new IOException(ms.getMessage());
    }
  }

  public static long getAdler32(DFSClient client, String cksumFile) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(client.open(cksumFile)));
    String line;
    long cksum = -1;
    while ((line = br.readLine()) != null) {
      if (line.startsWith("ADLER32:")) {
        String[] strings = line.split(":");
        cksum = Long.parseLong(strings[1], 16);
      }
    }
    br.close();
    return cksum;
  }

  public static byte[] getMD5(DFSClient client, String cksumFile) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(client.open(cksumFile)));
    String line;
    String md5 = null;
    while ((line = br.readLine()) != null) {
      if (line.startsWith("MD5:")) {
        md5 = line.split(":")[1];
      }
    }
    br.close();
    //cksum = Long.parseLong(strings[1], 16);
    byte[] cksum = new byte[16];
    for (int i = 0; i < 16; i++) {
      String b = md5.substring(i * 2, (i + 1) * 2);
      cksum[i] = (byte) Integer.parseInt(b, 16);
    }
    return cksum;
  }

  public static long calcAdler32(DFSClient client, ArrayList<Block> blocks, int blockSize, String file) throws IOException{
    DFSInputStream stream = client.open(file);
    byte[] buf = new byte[blockSize];
    Adler32 sum = new Adler32();

    int offset = 0;
    long fileOffset = 0;
    int bytesRead = 0;

    Iterator<Block> bi = blocks.iterator();
    Block b = bi.next();

    while (true) {
      if (b.offset == fileOffset) {
        FileInputStream cStream = new FileInputStream(b.path);

        int cBytesRead = 0;
        int cOffset = 0;
        while (true) {
          cBytesRead = cStream.read(buf, cOffset, b.length - cOffset);
          cOffset += cBytesRead;
          if (cOffset == b.length)
            break;
        }
        cStream.close();
        sum.update(buf, 0, cOffset);

        fileOffset += cOffset;

        long nSkipped = 0;
        while (cOffset > 0) {
          nSkipped = stream.skip(cOffset);
          cOffset -= nSkipped;
        }
        if (bi.hasNext())
          b = bi.next();
      }
      else {
        if ((bytesRead = stream.read(buf, offset, buf.length - offset)) == -1)
          break;

        offset += bytesRead;
        fileOffset += bytesRead;
        if (offset == buf.length) {
          offset = 0;
          sum.update(buf);
        }
      }
    }
    if (offset < buf.length)
      sum.update(buf, 0, offset);

    stream.close();
    return sum.getValue();
  }

  public static byte[] calcMD5(DFSClient client, ArrayList<Block> blocks, int blockSize, String file) throws IOException,NoSuchAlgorithmException {
    DFSInputStream stream = client.open(file);
    byte[] buf = new byte[blockSize];
    //Adler32 sum = new Adler32();
    MessageDigest md = MessageDigest.getInstance("MD5");

    int offset = 0;
    long fileOffset = 0;
    int bytesRead = 0;

    Iterator<Block> bi = blocks.iterator();
    Block b = bi.next();

    while (true) {
      if (b.offset == fileOffset) {
        FileInputStream cStream = new FileInputStream(b.path);

        int cBytesRead = 0;
        int cOffset = 0;
        while (true) {
          cBytesRead = cStream.read(buf, cOffset, b.length - cOffset);
          cOffset += cBytesRead;
          if (cOffset == b.length)
            break;
        }
        cStream.close();
        md.update(buf, 0, cOffset);

        fileOffset += cOffset;

        long nSkipped = 0;
        while (cOffset > 0) {
          nSkipped = stream.skip(cOffset);
          cOffset -= nSkipped;
        }
        if (bi.hasNext())
          b = bi.next();
      }
      else {
        if ((bytesRead = stream.read(buf, offset, buf.length - offset)) == -1)
          break;

        offset += bytesRead;
        fileOffset += bytesRead;
        if (offset == buf.length) {
          offset = 0;
          md.update(buf);
        }
      }
    }
    if (offset < buf.length)
      md.update(buf, 0, offset);

    stream.close();
    return md.digest();
  }
}
