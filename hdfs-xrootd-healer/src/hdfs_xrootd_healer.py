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

    try:
      fin = open('%s%s' % (FUSE_MOUNT, f), 'rb')

      while True:
        bytes = fin.read(BLOCK_SIZE)
        if bytes == '':
          break
        new_md5.update(bytes)

    finally:
      fin.close()

    print "new md5: %s" % new_md5.hexdigest()
