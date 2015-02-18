#!/usr/bin/python

import sys
import os
import re
import md5
import subprocess
import time

'''NAMESPACE = '/cms/phedex/store/relval'
if len(sys.argv) > 1:
  NAMESPACE = sys.argv[1]
LOGICAL_DIR = '/cms/phedex'
CACHE_DIR = '/data/hdfs-cache'
FUSE_MOUNT = '/hadoop'
CKSUM_DIR = '/cksums'
# 128 mb
BLOCK_SIZE = 134217728
HDFS_TMP_DIR = '/hdfshealer'
'''

def log(level, msg):
  if level <= LOG_LEVEL:
    LOG_OUT.write("%s %s\n" % (time.strftime("%b %d %H:%M:%S", time.localtime()), msg))
  
def parse_conf(path):
  d = {
    'NAMESPACE': '',
    'LOGICAL_DIR': '',
    'CACHE_DIR': '/cksums',
    'FUSE_MOUNT': '',
    'CKSUM_DIR': '',
    'HDFS_TMP_DIR': '',
    'BLOCK_SIZE': 134217728
  }

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
        if key == 'BLOCK_SIZE':
          d[key] = int(line_arr[1].strip())
        else:
          d[key] = line_arr[1].strip()
      line_num += 1
  finally:
    f.close()
  return d

def build_cache_set(path, cached_files):
  if os.path.isdir(path):
    files = os.listdir(path)

    for f in files:
      abs_path = os.path.join(path, f)
      build_cache_set(abs_path, cached_files)
  else:
    if path.endswith('.cinfo'):
      cached_files.add(re.sub(r'___[0-9]+_[0-9]+.cinfo$', '', path).replace(CONF['CACHE_DIR'], CONF['LOGICAL_DIR'], 1))

LOG_LEVEL = 0
LOG_OUT = sys.stdout
if len(sys.argv) > 1:
  CONF_PATH = sys.argv[1]
CONF = parse_conf(CONF_PATH)

if __name__ == '__main__':
  cached_files = set()
  build_cache_set(CONF['NAMESPACE'].replace(CONF['LOGICAL_DIR'], CONF['CACHE_DIR'], 1), cached_files)

  # hack to make single namespace work for testing
  # it must be a full cinfo cache path to work
  if not os.path.isdir(CONF['NAMESPACE']):
    CONF['NAMESPACE'] = re.sub(r'___[0-9]+_[0-9]+.cinfo$', '', CONF['NAMESPACE'])

  broken_files = []

  p = subprocess.Popen(['hdfs', 'fsck', CONF['NAMESPACE']], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

  for line in p.stdout:
    #print line,
    m = re.match(r'([^:]+):.*MISSING.*blocks', line)
    if m is not None:
      broken_files.append(m.group(1))

  # just throw out stderr for now
  for line in p.stderr:
    pass

  p.wait()

  #for cf in sorted(cached_files):
  #    print cf

  for f in broken_files:
    if f in cached_files:
      orig_md5_path = '%s%s%s' % (CONF['FUSE_MOUNT'], CONF['CKSUM_DIR'], f)

      fin = open(orig_md5_path) 
      try:
        for line in fin:
          if line.startswith('MD5:'):
            orig_md5 = line.split(':')[1].strip()
            log(0, "%s: %s" % (f, orig_md5))

      finally:
        fin.close()

      new_md5 = md5.new()

      orig_filepath = '%s%s' % (CONF['FUSE_MOUNT'], f)

      orig_stat = os.stat(orig_filepath)

      f_base = os.path.basename(f)
      tmp_filepath = os.path.join('%s%s' % (CONF['FUSE_MOUNT'], CONF['HDFS_TMP_DIR']), f_base)

      fin = open(orig_filepath, 'rb')
      log(0, tmp_filepath)
      fout = open(tmp_filepath, 'wb')
      try:
        while True:
          bytes = fin.read(CONF['BLOCK_SIZE'])
          if bytes == '':
            break
          new_md5.update(bytes)
          fout.write(bytes)

      finally:
        fin.close()
        fout.close()

      log(0, "new md5: %s" % new_md5.hexdigest())
      if orig_md5 != new_md5.hexdigest():
        log(0, "Checksums don't match, skipping: %s" % f)
        LOG_OUT.write("    original: %s\n" % orig_md5)
        LOG_OUT.write("  calculated: %s\n" % new_md5.hexdigest())
        log(0, "rm %s" % tmp_filepath)
        os.unlink(tmp_filepath)
        continue

      os.chown(tmp_filepath, orig_stat.st_uid, orig_stat.st_gid)
      os.chmod(tmp_filepath, orig_stat.st_mode)

      log(0, "mv %s %s" % (orig_filepath, "%s.bak" % orig_filepath))
      os.rename(orig_filepath, "%s.bak" % orig_filepath)
      log(0, "mv %s %s" % (tmp_filepath, orig_filepath))
      os.rename(tmp_filepath, orig_filepath)
      log(0, "rm %s" % "%s.bak" % orig_filepath)
      #os.unlink("%s.bak" % orig_filepath)
