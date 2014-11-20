package edu.ucsd.t2.hdfs.xrootd.healer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

public class Config {
  private String path;
  private HashMap<String, String> map;

  public Config() {
    path = null;
    map = new HashMap<String, String>();
    map.put("NAMESPACE", null);
    map.put("LOGICAL_DIR", null);
    map.put("CACHE_DIR", null);
    map.put("HDFS_TMP_DIR", null);
    map.put("SSH_USER", null);
  }

  public void parse(String file) throws IOException {
    BufferedReader br = new BufferedReader(new FileReader(file));
    path = file;
    
    String line;
    for (int i = 1; (line = br.readLine()) != null; i++) {
      // strip comments
      line = line.split("#")[0];
      String[] lineArr = line.split("=");
      if (lineArr.length >=2) {
        String key = lineArr[0].trim();
        if (!map.containsKey(key.toUpperCase())) {
          throw new IllegalArgumentException(path + ": " + "line " + i + ": " + key);
        }
        map.put(key.toUpperCase(), lineArr[1].trim());
      }
    }

    br.close();
  }

  public String getPath() {
    return path;
  }

  public String get(String key) {
    return map.get(key);
  }

  public void put(String key, String value) {
    if (!map.containsKey(key.toUpperCase()))
      throw new IllegalArgumentException(key);
    map.put(key.toUpperCase(), value);
  }

  public void dump() {
    System.out.println("Config read from: " + path);
    for (String k: map.keySet()) {
      System.out.println(k + " " + map.get(k));
    }
  }
}
