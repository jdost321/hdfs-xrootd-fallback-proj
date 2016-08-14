import sys
import time

CONF_DEFAULTS = {
  'NAMESPACE': '',
  'LOGICAL_DIR': '',
  'CACHE_DIR': '',
  'FUSE_MOUNT': '',
  'CKSUM_DIR': '/cksums',
  'HDFS_TMP_DIR': '',
  'BLOCK_SIZE': 134217728,
  # should probably genericize /var/log/ in Makefile
  'LOG_DIR': '/var/log/hdfs-xrootd-healer',
  'NUM_WORKERS': 1
}

def parse_conf(path):
  d = dict(CONF_DEFAULTS)

  line_num = 1
  f = open(path)
  try:
    for line in f:
      line = line.split('#')[0]
      line_arr = line.split('=')
      if len(line_arr) >= 2:
        key = line_arr[0].strip()
        if key.upper() not in d:
          raise ValueError('%s: line %s: %s' % (path, line_num, key))
        key = key.upper()
        if key == 'BLOCK_SIZE' or key == 'NUM_WORKERS':
          d[key] = int(line_arr[1].strip())
        else:
          d[key] = line_arr[1].strip()
      line_num += 1
  finally:
    f.close()
  return d

class Logger(object):
  def __init__(self, path=None, level=0):
    if path is None:
      self.stream = sys.stdout
    else:
      self.stream = open(path, 'a', 1)

    self.level = level

  def write(self, level, msg):
    if level <= self.level:
      self.stream.write(msg)

  def log(self, level, msg):
    if level <= self.level:
      self.stream.write("%s %s\n" % (time.strftime("%b %d %H:%M:%S", time.localtime()), msg))

  def close(self):
    self.stream.close()
