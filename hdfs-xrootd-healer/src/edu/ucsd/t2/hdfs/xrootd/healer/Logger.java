package edu.ucsd.t2.hdfs.xrootd.healer;

import java.io.PrintStream;
import java.util.Date;
import java.text.SimpleDateFormat;

public class Logger {
  private PrintStream out;
  private int level;
  private SimpleDateFormat dateFormat;

  public Logger() {
    out = System.out;
    level = 0;
    dateFormat = new SimpleDateFormat("MMM dd HH:mm:ss");
  }

  public void setStream(PrintStream out) {
    this.out = out;
  }

  public void log(int level, String msg) {
    if (level <= this.level)
      out.println(dateFormat.format(new Date()) + " " + msg);
  }

  public void println(int level, String msg) {
    if (level <= this.level)
      out.println(msg);
  }
}
