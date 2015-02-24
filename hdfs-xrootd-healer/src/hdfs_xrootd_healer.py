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

def get_broken_files(path):
  broken_files = []

  p = subprocess.Popen(['hdfs', 'fsck', path], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

  for line in p.stdout:
    m = re.match(r'([^:]+):.*MISSING.*blocks', line)
    if m is not None:
      broken_files.append(m.group(1))

  # just throw out stderr for now
  for line in p.stderr:
    pass

  p.wait()

  return broken_files

LOG_LEVEL = 0
LOG_OUT = sys.stdout
if len(sys.argv) > 1:
  CONF_PATH = sys.argv[1]
CONF = parse_conf(CONF_PATH)
PRETENT = False

if __name__ == '__main__':
  for a in sys.argv:
    if a == '-pretend':
      PRETEND = True

  broken_files_tot = 0
  repairable_files_tot = 0
  repaired_files_tot = 0

  for ns_path in CONF['NAMESPACE'].split(','):
    log(0, "Processing namespace: %s" % ns_path)

    log(0, "Searching namespace for corrupt files")
    broken_files = get_broken_files(ns_path)

    if len(broken_files) == 0:
      log(0, "No corrupt files found")
      continue

    broken_files_tot += len(broken_files)
    log(0, "Corrupt Files: %s" % len(broken_files))

    log(0, "Generating list of cached files")
    cached_files = set()
    build_cache_set(ns_path.replace(CONF['LOGICAL_DIR'], CONF['CACHE_DIR'], 1), cached_files)
    if len(cached_files) == 0:
      log(0, "No cached files found")
      continue

    log(0, "Cached Files: %s" % len(cached_files))

    repairable_files = 0
    repaired_files = 0

    log(0, "Begin repairing files")

    for f in broken_files:
      if not f in cached_files:
        continue

      repairable_files += 1
      # be optimistic, subtract whenever we fail to heal
      repaired_files += 1

      log(0, f)
      orig_md5_path = '%s%s%s' % (CONF['FUSE_MOUNT'], CONF['CKSUM_DIR'], f)

      fin = None
      try:
        fin = open(orig_md5_path) 
        for line in fin:
          if line.startswith('MD5:'):
            orig_md5 = line.split(':')[1].strip()

      except IOError, e:
        repaired_files -= 1
        log(0, "ERROR: Unable to parse original checksum, skipping: %s" % f)
        LOG_OUT.write("  %s\n" % e)
        continue
      finally:
        if fin is not None:
          fin.close()

      new_md5 = md5.new()

      orig_filepath = '%s%s' % (CONF['FUSE_MOUNT'], f)


      f_base = os.path.basename(f)
      tmp_filepath = os.path.join('%s%s' % (CONF['FUSE_MOUNT'], CONF['HDFS_TMP_DIR']), f_base)

      fin = None
      fout = None
      try:
        fin = open(orig_filepath, 'rb')
        fout = open(tmp_filepath, 'wb')
        while True:
          bytes = fin.read(CONF['BLOCK_SIZE'])
          if bytes == '':
            break
          new_md5.update(bytes)
          fout.write(bytes)
      except IOError, e:
        repaired_files -= 1
        log(0, "ERROR: Error occurred repairing file, skipping: %s" % f)
        LOG_OUT.write("  %s\n" % e)
        continue

      finally:
        if fin != None:
          fin.close()
        if fout != None:
          fout.close()

      if orig_md5 != new_md5.hexdigest():
        log(0, "ERROR: Checksums don't match, skipping: %s" % f)
        LOG_OUT.write("    original: %s\n" % orig_md5)
        LOG_OUT.write("  calculated: %s\n" % new_md5.hexdigest())
        try:
          os.unlink(tmp_filepath)
        except OSError, e:
          log(0, "WARN: Unable to remove file: %s" % tmp_filepath)
          LOG_OUT.write("  %s\n" % e)

        repaired_files -= 1
        continue

      try:
        orig_stat = os.stat(orig_filepath)

        os.chown(tmp_filepath, orig_stat.st_uid, orig_stat.st_gid)
        os.chmod(tmp_filepath, orig_stat.st_mode)
      except OSError, e:
        repaired_files -= 1
        log(0, "ERROR: Unable to preserve meta info, skipping: %s" % f)
        LOG_OUT.write("  %s\n" % e)
        continue

      if PRETEND:
        try:
          os.unlink(tmp_filepath)
        except OSError:
          pass
      else:
        try:
          os.rename(orig_filepath, "%s.bak" % orig_filepath)
          os.rename(tmp_filepath, orig_filepath)
        except OSError, e:
          repaired_files -= 1
          log(0, "ERROR: Failed to replace repaired file: %s" % f)
          LOG_OUT.write("  %s\n" % e)
          continue

        try:
          os.unlink("%s.bak" % orig_filepath)
        except OSError, e:
          log(0, "WARN: Unable to remove file: %s.bak" % orig_filepath)
          LOG_OUT.write("  %s\n" % e)

    repairable_files_tot += repairable_files
    repaired_files_tot += repaired_files
    log(0, "Repairable Files: %s" % repairable_files)
    log(0, "Repaired Files: %s" % repaired_files)

  log(0, "Total Corrupt Files: %s" % broken_files_tot)
  log(0, "Total Repairable Files: %s" % repairable_files_tot)
  log(0, "Total Repaired Files: %s" % repaired_files_tot)
