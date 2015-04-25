package org.xrootd.hdfs.fallback;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

import org.apache.hadoop.fs.UnresolvedLinkException;
import org.apache.hadoop.hdfs.BlockMissingException;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;

public class XFBFSInputStream extends DFSInputStream {
  static {
    System.loadLibrary("XrdBlockFetcher");
  }

  static private String info(Exception exc) {
    return "exception of type " + exc.getClass().getName() + ": " + exc.getMessage();
  }

  private XrdBlockFetcher xbf;
  private Set<ExtendedBlock> badBlocks;

  private volatile int xrd_tried_and_failed = 0;

  XFBFSInputStream(DFSClient dfsClient, String src, int buffersize, 
      boolean verifyChecksum) throws IOException, UnresolvedLinkException
  {
    super(dfsClient, src, buffersize, verifyChecksum);
    xbf = new XrdBlockFetcher(src, (int) dfsClient.getBlockSize(src));
    badBlocks = Collections.synchronizedSet(new HashSet<ExtendedBlock>());
  }

  public int read(long position, byte[] buffer, int offset, int length)
    throws IOException
  {
    if (xrd_tried_and_failed > 0) {
      return super.read(position, buffer, offset, length);
    }

    // sanity checks
    dfsClient.checkOpen();
    if (closed) {
      throw new IOException("Stream closed");
    }
    long filelen = getFileLength();
    if ((position < 0) || (position >= filelen)) {
      return -1;
    }
    int realLen = length;
    if ((position + length) > filelen) {
      realLen = (int)(filelen - position);
    }
    
    // determine the block and byte range within the block
    // corresponding to position and realLen
    List<LocatedBlock> blockRange = getBlockRange(position, realLen);
    int remaining = realLen;
    Map<ExtendedBlock,Set<DatanodeInfo>> corruptedBlockMap 
      = new HashMap<ExtendedBlock, Set<DatanodeInfo>>();

    for (LocatedBlock blk : blockRange)
    {
      long targetStart = position - blk.getStartOffset();
      long bytesToRead = Math.min(remaining, blk.getBlockSize() - targetStart);
      
      // Top-level try-cactch for reporting of "outgoing" exceptions
      try
      {
        if (badBlocks.contains(blk.getBlock())) {
          synchronized (xbf) {
            xbf.read(position, (int)bytesToRead, buffer, offset);
          }
        } else {
          try {
            fetchBlockByteRange(blk, targetStart, 
                targetStart + bytesToRead - 1, buffer, offset, corruptedBlockMap);
          }
          catch (IOException exc) {
            if (exc instanceof BlockMissingException) {
              String badblock_info = "XFBFSInputStream::read() Bad block: "
                + "\n   file:           " + ((BlockMissingException) exc).getFile()
                + "\n   block offset:   " + ((BlockMissingException) exc).getOffset()
                + "\n   block size:     " + blk.getBlockSize()
                + "\n   block location: " + blk.getBlock()
                + "\n   position:       " + position
                + "\n   bytesToRead:    " + bytesToRead;

              XrdUdpLog.send(0, badblock_info);
              System.out.println(badblock_info);
            }
            synchronized (xbf) {
              if (!xbf.isOpen()) {
                XrdUdpLog.send(1, "XFBFSInputStream::read() Opening XrdClient on " + info(exc));
                xbf.open();
              }

              // cast works since bytesToRead is really <= int size anyway
              xbf.read(position, (int)bytesToRead, buffer, offset);
            }
            badBlocks.add(blk.getBlock());
          }
          finally {
            // Check and report if any block replicas are corrupted.
            // BlockMissingException may be caught if all block replicas are
            // corrupted.
            reportCheckSumFailure(corruptedBlockMap, blk.getLocations().length);
          }
        }
      }
      catch (IOException exc) {
        XrdUdpLog.send(0, "XFBFSInputStream::read() Unhandled " + info(exc));
        ++xrd_tried_and_failed;
        throw exc;
      }

      remaining -= bytesToRead;
      position  += bytesToRead;
      offset    += bytesToRead;
    }
    assert remaining == 0 : "Wrong number of bytes read.";
    if (dfsClient.getStats() != null) {
      dfsClient.getStats().incrementBytesRead(realLen);
    }
    return realLen;
  }
  public synchronized void close() throws IOException {
	  if (closed) {
	    return;
	  }
	  if (xbf != null && xbf.isOpen())
	    xbf.close();
	  
	  super.close();
	  closed = true;
  }
}
