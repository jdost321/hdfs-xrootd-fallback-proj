// Needs this:
// 1. export CLASSPATH=/usr/lib/hadoop/client/hdfs-xrootd-fallback-1.0.2.jar:`./expand-classpath.sh `
// 2. ln -s /etc/hadoop/conf/xfbfs-site.xml .
//
// Maybe we can weed down what goes into 1.
// Will java blindly load all jars it finds in classpath?
//
// Instead of 2. it would be way better to read the site config, somehow.
// I don't know :) Note that "fs.xfbfs.impl" is injected manually into builder.

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <hdfs.h>

#include "md5.h"

//==============================================================================

#include <sys/time.h>

inline double dtime()
{
    double tseconds = 0.0;
    struct timeval mytime;
    gettimeofday(&mytime,(struct timezone*)0);
    tseconds = (double)(mytime.tv_sec + mytime.tv_usec*1.0e-6);
    return tseconds;
}

//==============================================================================

const bool  TIMEP = true;

const char *NNODE = "xfbfs://proxy-1.t2.ucsd.edu";
//const char *NNODE = "hdfs://proxy-1.t2.ucsd.edu";
int         NPORT = 9000;

// const char *NUSER = "";

tSize BLOCK_SIZE = 128 * 1024 * 1024;

//==============================================================================

int main(int argc, char **argv)
{
   // XXX Missing argument check.
   // . input file
   // . output file
   // . optional block size (default 128m)

   if (argc > 3)
   {
      char *bs = argv[3];
      int l    = strlen(bs) - 1;
      int mult = 1;
      if (l > 0)
      {
         if (bs[l] < 48 || bs[l] > 57)
         {
            if (bs[l] == 'k' || bs[l] == 'K') mult = 1024;
            if (bs[l] == 'm' || bs[l] == 'M') mult = 1024*1024;
            bs[l] = 0;
         }
      }
      BLOCK_SIZE = atoll(bs) * mult;
   }
   printf("Using block_size %lld\n", BLOCK_SIZE);

   mbedtls_md5_context m5ctx, *m5c = &m5ctx;

   mbedtls_md5_init  (m5c);
   mbedtls_md5_starts(m5c);

   hdfsBuilder *bld = hdfsNewBuilder();

   hdfsBuilderSetNameNode    (bld, NNODE);
   hdfsBuilderSetNameNodePort(bld, NPORT);
   // hdfsBuilderSetUserName(bld, const char *userName);

   // hdfsBuilderConfSetStr(bld, const char *key, const char *val);
   hdfsBuilderConfSetStr(bld, "fs.xfbfs.impl", "org.xrootd.hdfs.fallback.XrdFallBackFileSystem");

   hdfsFS fs = hdfsBuilderConnect(bld);
   if (!fs)
   {
      fprintf(stderr, "Failed to connect to %s : %d!\n", NNODE, NPORT);
      exit(-1);
   }

   hdfsFile inn = hdfsOpenFile(fs, argv[1], O_RDONLY, 0, 0, 0);
   if(!inn)
   {
      fprintf(stderr, "Failed to open %s for reading!\n", argv[1]);
      exit(-1);
   }

   hdfsFile out = hdfsOpenFile(fs, argv[2], O_WRONLY|O_CREAT, 0, 0, 0);
   if(!out)
   {
      fprintf(stderr, "Failed to open %s for writing!\n", argv[2]);
      exit(-1);
   }

   unsigned char* buf = new unsigned char[BLOCK_SIZE];

   tOffset pos = 0;
   tSize   nr, nw;
   double  t;
   do
   {
      if (TIMEP)
      {
         printf("Reading %10d at pos %10lld ", BLOCK_SIZE, pos);
         fflush(stdout);
         t = dtime();
      }

      nr = hdfsPread (fs, inn, pos, (void*)buf, BLOCK_SIZE);

      if (nr == -1)
      {
         fprintf(stderr, "Failed to read %s at pos %lld!\n", argv[1], pos);
         exit(-1);
      }

      mbedtls_md5_update(m5c, buf, nr);

      if (TIMEP)
      {
         t = dtime() - t;
         printf("(%6.2f MB/s) ... writing ", nr / 1024 / 1024 / t);
         fflush(stdout);
         t = dtime();
      }

      nw = hdfsWrite(fs, out, (void*)buf, nr);

      if (nw != nr)
      {
         fprintf(stderr, "Failed to write %s at pos %lld!\n", argv[2], pos);
         exit(-1);
      }

      if (TIMEP)
      {
         t = dtime() - t;
         printf("(%6.2f MB/s)\n", nr / 1024 / 1024 / t);
      }

      pos += nr;
   }
   while (nr == BLOCK_SIZE);

   hdfsCloseFile(fs, inn);

   if (hdfsFlush(fs, out))
   {
      fprintf(stderr, "Failed to 'flush' %s\n", argv[1]);
      exit(-1);
   }

   hdfsCloseFile(fs, out);

   delete [] buf;

   unsigned char md5[16];
   mbedtls_md5_finish(m5c, md5);

   printf("MD5: ");
   for (int i = 0; i < 16; ++i) printf("%02x", md5[i]);
   printf("\n");
}
