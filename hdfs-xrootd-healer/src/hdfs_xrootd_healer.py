#!/usr/bin/python

import sys
import os
import re
import md5

import subprocess

NAMESPACE = '/cms/phedex/store/relval'
if len(sys.argv) > 1:
  NAMESPACE = sys.argv[1]
LOGICAL_DIR = '/cms/phedex'
CACHE_DIR = '/data/hdfs-cache'
FUSE_MOUNT = '/hadoop'
CKSUM_DIR = '/cksums'
# 128 mb
BLOCK_SIZE = 134217728
HDFS_TMP_DIR = '/hdfshealer'

def build_cache_set(path, cached_files):
  if os.path.isdir(path):
    files = os.listdir(path)

    for f in files:
      abs_path = os.path.join(path, f)
      build_cache_set(abs_path, cached_files)
  else:
    if path.endswith('.cinfo'):
      cached_files.add(re.sub(r'___[0-9]+_[0-9]+.cinfo$', '', path).replace(CACHE_DIR, LOGICAL_DIR, 1))

cached_files = set()
build_cache_set(NAMESPACE.replace(LOGICAL_DIR, CACHE_DIR, 1), cached_files)

# hack to make single namespace work for testing
# it must be a full cinfo cache path to work
if not os.path.isdir(NAMESPACE):
  NAMESPACE = re.sub(r'___[0-9]+_[0-9]+.cinfo$', '', NAMESPACE)

broken_files = []

p = subprocess.Popen(['hdfs', 'fsck', NAMESPACE], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

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
    orig_md5_path = '%s%s%s' % (FUSE_MOUNT, CKSUM_DIR, f)
    try:
      fin = open(orig_md5_path) 
      for line in fin:
        if line.startswith('MD5:'):
          orig_md5 = line.split(':')[1].strip()
          print "%s: %s" % (f, orig_md5)

    finally:
      fin.close()

    new_md5 = md5.new()

    orig_filepath = '%s%s' % (FUSE_MOUNT, f)
    f_base = os.path.basename(f)
    tmp_filepath = os.path.join('%s%s' % (FUSE_MOUNT, HDFS_TMP_DIR), f_base)
    try:
      fin = open(orig_filepath, 'rb')
      print tmp_filepath
      fout = open(tmp_filepath, 'wb')

      while True:
        bytes = fin.read(BLOCK_SIZE)
        if bytes == '':
          break
        new_md5.update(bytes)
        fout.write(bytes)

    finally:
      fin.close()
      fout.close()

    print "new md5: %s" % new_md5.hexdigest()
    if orig_md5 != new_md5.hexdigest():
      print "Checksums don't match, skipping: %s" % f
      print "    original: %s" % orig_md5
      print "  calculated: %s" % new_md5.hexdigest()
      print "rm %s" % tmp_filepath
      os.unlink(tmp_filepath)
      continue

    print "mv %s %s" % (orig_filepath, "%s.bak" % orig_filepath)
    os.rename(orig_filepath, "%s.bak" % orig_filepath)
    print "mv %s %s" % (tmp_filepath, orig_filepath)
    os.rename(tmp_filepath, orig_filepath)
    print "rm %s" % "%s.bak" % orig_filepath
    #os.unlink("%s.bak" % orig_filepath)
