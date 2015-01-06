package edu.ucsd.t2.hdfs.xrootd.healer;

import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.fs.FsShell;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.BufferedOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.net.URI;
import java.util.Arrays;

public class BlockHealer {
  private static final Config CONF = new Config();
  private static final Logger LOGGER = new Logger();
  private static boolean DEBUG = false;
  private static boolean PRETEND = false;
  private static final int BUF_SIZE = 8192; // chose same size as java's default for Buffered streams

  private static HashSet<String> getCacheList(String dumpCinfoBin) throws IOException,InterruptedException {
    HashSet<String> cacheList = new HashSet<String>();
    Process p = Runtime.getRuntime().exec(new String[]{dumpCinfoBin, "--select", "complete", CONF.get("CACHE_DIR")});
    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line;
    while ((line = br.readLine()) != null) {
      // strip out the .cinfo at the end
      cacheList.add(line.substring(0,line.length() - 6));
    }
    p.waitFor();
    int exitVal = p.exitValue();

    // if exit non 0, throw exception and report the code and stderr
    // assumes only last line of stderr is relevant
    if (exitVal != 0) {
      br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      String errMsg = "";
      while ((line = br.readLine()) != null) {
        errMsg = line;
      }
      throw new IOException("dumpCinfoRec exited unexpectedly with code " + exitVal + ": " + errMsg);
    }
    return cacheList;
  }

  // return number of failed block registrations
  private static int sshExec(String user, String host, ArrayList<String> argList, ArrayList<String> script) throws IOException,InterruptedException {
    int failCount = 0;
    // prepare and execute ssh
    String args[] = new String[4 + argList.size()];
    args[0] = "ssh";
    args[1] = user + "@" + host;
    args[2] = "bash";
    args[3] = "-s";
    for (int i = 4; i < args.length; i++)
      args[i] = argList.get(i - 4);

    Process p = Runtime.getRuntime().exec(args);

    // write out the script
    PrintWriter pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(p.getOutputStream())));
    for (String l: script) {
      pw.println(l);
    }
    pw.close();

    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line;
    while ((line = br.readLine()) != null) {
      LOGGER.log(0, line);
      // assume any stdout line is a failed block mv
      failCount++;
    }
    p.waitFor();
    int exitVal = p.exitValue();

    // if exit non 0, throw exception and report the code and stderr
    // assumes only last line of stderr is relevant
    if (exitVal != 0) {
      br = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      String errMsg = "";
      while ((line = br.readLine()) != null) {
        errMsg = line;
      }
      throw new IOException("ssh exited unexpectedly with code " + exitVal + ": " + errMsg);
    }
    return failCount;
  }

  private static void append(FileOutputStream fout, File f) throws IOException {
    FileInputStream fin = new FileInputStream(f);
    byte[] buf = new byte[BUF_SIZE];
    int offset = 0;
    int bytesRead = 0;
    while ((bytesRead = fin.read(buf, 0, buf.length - offset)) != -1) {
      offset += bytesRead;

      if (offset == buf.length) {
        fout.write(buf);
        offset = 0;
      }
    }
    // output remaining blocks in buffer
    fout.write(buf, 0, offset);
    fin.close();
  }

  public static void main(String[] argv) throws Exception {
    String dumpCinfoBin = System.getenv("DUMP_CINFO_BIN");
    if (dumpCinfoBin == null) {
      System.err.println("DUMP_CINFO_BIN is not set in the environment, exiting.");
      System.exit(1);
    }
    String conf_path = System.getenv("HDFS_XRD_HEALER_CONF");
    if (conf_path == null) {
      System.err.println("HDFS_XRD_HEALER_CONF is not set in the environment, exiting.");
      System.exit(1);
    }
    
    CONF.parse(conf_path);

    for (String a: argv) {
      if (a.equals("-debug"))
        DEBUG = true;
      else if (a.equals("-pretend"))
        PRETEND = true;
      else
        CONF.put("NAMESPACE", a);
    }

    PrintStream logStream = null;
    if (!DEBUG) {
      String log_path = CONF.get("LOG");
      logStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(log_path, true)), true);
      LOGGER.setStream(logStream);
    }

    String[] namespacePaths = CONF.get("NAMESPACE").split(",");

    LOGGER.log(0, "Generating list of cached blocks");
    HashSet<String> cacheList = getCacheList(dumpCinfoBin);
    if (cacheList.size() == 0) {
      LOGGER.log(0, "No complete blocks found in cache, exiting.");
      System.exit(0);
    }

    // to aggregate total stats
    int badBlockTot = 0;
    int repBlockTot = 0;
    int badFileTot = 0;
    int repFileTot = 0;
    int partialFileTot = 0;
    int fixedBlockTot = 0;

    for (String ns: namespacePaths) {
      LOGGER.log(0, "Processing namespace: " + ns);
      FsShell shell = new FsShell();
      int retVal = ToolRunner.run(shell, new String[]{"-test","-e",ns});
      if (retVal != 0) {
        LOGGER.log(0, "path not found in hadoop: " + ns);
        continue;
      } 

      HdfsConfiguration conf = new HdfsConfiguration();

      LOGGER.log(0, "Searching namespace for corrupt files");
      ArrayList<String> badFiles = HdfsUtil.findBadFiles(conf, ns);
      if (badFiles.size() == 0) {
        LOGGER.log(0, "No corrupt files found");
        continue;
      }

      LOGGER.log(0, "Corrupt Files: " + badFiles.size());
      badFileTot += badFiles.size();

      // for each host map contains a list of blocks bin0, bout0, bin1, bout1, ...
      // where binN is the new block name and boutN is the original block name
      // on DN the operation performed is mv binN boutN
      HashMap<String, ArrayList<String>> blockMap = new HashMap<String, ArrayList<String>>();

      // remember tmp hadoop files to remove later
      ArrayList<String> tmpFiles = new ArrayList<String>();

      LOGGER.log(0, "Injecting cached blocks into hadoop");

      // vars to collect some stats
      int badBlockCount = 0;
      int repBlockCount = 0;
      int repFileCount = 0;
      int partialFileCount = 0;

      // used for checksum comparisons
      DFSClient client = new DFSClient(new URI(conf.get("fs.defaultFS")), conf);

      for (String file: badFiles) {
        ArrayList<Block> badBlocks = HdfsUtil.findBadBlocks(conf, file);

        // it is possible file recovered between findBadFiles query and findBadBlocks query
        if (badBlocks.size() == 0)
          continue;

        badBlockCount += badBlocks.size();

        int blockSize = HdfsUtil.getBlockSize(shell, file);

        // couldn't determine block size of broken file, skip and move on
        if (blockSize < 0) {
          continue;
        }

        // determine and store blocks found in cache
        ArrayList<Block> foundBlocks = new ArrayList<Block>();
        String cachePath = file.replaceFirst(CONF.get("LOGICAL_DIR"), CONF.get("CACHE_DIR"));
        for (Block b: badBlocks) {
          String blockPath = cachePath + "___" + b.length + "_" + b.offset;
          if (cacheList.contains(blockPath)) {
            b.path = blockPath;
            foundBlocks.add(b);
          }
        }

        // file has broken blocks but didn't find any in cache, move on to next file
        if (foundBlocks.size() == 0) {
          continue;
        }
        LOGGER.log(0, file + " bad_blocks=" + badBlocks.size() + " cached_blocks=" + foundBlocks.size());
        for (Block b: foundBlocks)
          LOGGER.println(0, "    " + b.path);

        // if we don't have all the bad blocks in cache, don't heal this file
        if (badBlocks.size() != foundBlocks.size()) {
          partialFileCount++;
          continue;
        }

        // compute checksum, only heal if file hasn't changed!
        byte[] origCksum;
        byte[] newCksum;
        try {
          origCksum = HdfsUtil.getMD5(client, CONF.get("CKSUM_DIR") + file);       
        }
        catch (IOException e) {
          LOGGER.log(0, "Unable to parse original checksum, skipping: /cksums" + file);
          continue;
        }
        try {
          newCksum = HdfsUtil.calcMD5(client, foundBlocks, blockSize, file);
        }
        catch (IOException e) {
          LOGGER.log(0, "Unable calculate new checksum, skipping: " + file);
          continue;
        }
        if (!Arrays.equals(origCksum, newCksum)) {
          LOGGER.log(0, "Checksums don't match, skipping: " + file);
          String md5String = "";
          for (int i = 0; i < 16; i++) {
            md5String += String.format("%02x", origCksum[i]);
          }
          LOGGER.println(0, "    original: " + md5String);
          md5String = "";
          for (int i = 0; i < 16; i++) {
            md5String += String.format("%02x", newCksum[i]);
          }
          LOGGER.println(0, "  calculated: " + md5String);
          continue;
        }

        repBlockCount += foundBlocks.size();
        repFileCount++;

        if (!PRETEND) {
          // create local tmp file
          File tmpFile = File.createTempFile("block_healer", null);
          FileOutputStream fout = new FileOutputStream(tmpFile);

          for (Block b: foundBlocks) {
            File cacheFile = new File(b.path);
            append(fout, cacheFile);
          }
          fout.close();
          try {
            // write tmp file to hadoop, may throw IOE if unsuccessful
            HdfsUtil.put(shell, blockSize, tmpFile.toString(), CONF.get("HDFS_TMP_DIR"));

            // remember tmp file hadoop path for deletion later
            tmpFiles.add(CONF.get("HDFS_TMP_DIR") + "/" + tmpFile.getName());

            // get new names and locations of fixed blocks
            ArrayList<Block> newBlocks = HdfsUtil.findBlocks(conf, CONF.get("HDFS_TMP_DIR") + "/" + tmpFile.getName());

            for (int i = 0; i < newBlocks.size(); i++) {
              Block b = newBlocks.get(i);
              // host may be null if block goes corrupt after put and before findBlocks above
              // in this case just skip it
              if (b.host == null) {
                LOGGER.log(0, "WARN: " + file + ": failed injecting block: " + foundBlocks.get(i).name);
              }
              else {
                if (!blockMap.containsKey(b.host)){
                  blockMap.put(b.host, new ArrayList<String>());
                }
                ArrayList<String> blockList = blockMap.get(b.host);
                blockList.add(b.name);
                blockList.add(foundBlocks.get(i).name);
              }
            }
          }
          catch (IOException e) {
            LOGGER.log(0, "ERROR: " + file + ": could not inject any blocks: " + e.getMessage());
          }
          // remove local tmp file whether or not succesfully written to hadoop
          finally {
            tmpFile.delete();
          }
        }
      }
      client.close();

      LOGGER.log(0, "Repairable Files: " + repFileCount);
      LOGGER.log(0, "Partially Cached Files: " + partialFileCount);
      LOGGER.log(0, "Corrupt Blocks: " + badBlockCount);
      LOGGER.log(0, "Repairable Blocks: " + repBlockCount);

      repFileTot += repFileCount;
      partialFileTot += partialFileCount;
      badBlockTot += badBlockCount;
      repBlockTot += repBlockCount;

      if (blockMap.size() == 0) {
        LOGGER.log(0, "No repairable blocks found");
        continue;
      }

      // read in square_pusher.sh script
      ArrayList<String> scriptLines = new ArrayList<String>();
      BufferedReader br = new BufferedReader(new InputStreamReader(BlockHealer.class.getResourceAsStream("/square_pusher.sh")));
      String line;
      while ((line = br.readLine()) != null) {
        scriptLines.add(line);
      }
      br.close();

      // count how many we actually fixed
      int fixedBlockCount = 0;
      // loop over hosts and run the script to rename new blocks to original names
      LOGGER.log(0, "Connecting to datanodes to restore blocks");
      String out;
      for (String host: blockMap.keySet()) {
        ArrayList<String> blocks = blockMap.get(host);

        // be optimistic now then subtract failures later
        fixedBlockCount += blocks.size() / 2;

        out = host + ": ";
        for (int i = 0; i < blocks.size(); i++) {
          if (i % 2 == 0)
            out += "(" + blocks.get(i) + " > ";
          else
            out += blocks.get(i) + "), ";
        }
        LOGGER.log(0, out);

        try {
          int failures = sshExec(CONF.get("SSH_USER"), host, blocks, scriptLines);
          fixedBlockCount -= failures;
        }
        catch (IOException e) {
          LOGGER.log(0, "ERROR: failed processing block list: " + e.getMessage());
          fixedBlockCount -= blocks.size() / 2;
        }
      }

      LOGGER.log(0, "Repaired Blocks: " + fixedBlockCount);
      fixedBlockTot += fixedBlockCount;
      // remove tmp files from hadoop
      LOGGER.log(0, "Cleaning up hadoop tmp files in " + CONF.get("HDFS_TMP_DIR"));
      for (String file: tmpFiles) {
        try {
          HdfsUtil.rm(shell, file);
        }
        catch (IOException e) {
          LOGGER.log(0, "WARN: failed removing file:" + file + ": " + e.getMessage()); 
        }
      }
    }

    // log aggregate totals if more than one namespace
    if (namespacePaths.length > 1) {
      LOGGER.log(0, "Total Corrupt Files: " + badFileTot);
      LOGGER.log(0, "Total Repairable Files: " + repFileTot);
      LOGGER.log(0, "Total Partially Cached Files: " + partialFileTot);
      LOGGER.log(0, "Total Corrupt Blocks: " + badBlockTot);
      LOGGER.log(0, "Total Repairable Blocks: " + repBlockTot);
      LOGGER.log(0, "Total Repaired Blocks: " + fixedBlockTot);
    }

    if (!DEBUG)
      logStream.close();
  }
}
