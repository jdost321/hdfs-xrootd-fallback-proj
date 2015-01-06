#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <sys/types.h>
#include <dirent.h>
#include <stdio.h>
#include <iostream>
#include <string>
#include <pcrecpp.h>
#include <time.h>
#include <string.h>
#include <stdlib.h>
#include <vector>

#include <sys/file.h>
#include <sys/errno.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#define BIT(n) (1ULL << (n))



void listdir(char* dirname, int lvl);

// print options
enum ESelect { kAll, kComplete, kInComplete};
enum EVerbose { kFull, kPath};

ESelect gSelectMode = kAll;
bool gVerbose = false;

class Rec
{
public:
   // access info 
   struct AStat {
      time_t DetachTime;
      long long BytesDisk;
      long long BytesRam;
      long long BytesMissed;

      void dump() {
         char s[1000];
         struct tm * p = localtime(&DetachTime);

         strftime(s, 1000, "%c", p);

         const time_t* t = &DetachTime;
         printf("[%s], bytesDisk=%lld, bytesRAM=%lld, bytesMissed=%lld\n", s, BytesDisk, BytesRam, BytesMissed);
      }
   };

public:
   int m_version;          // info version
   long long m_bufferSize; // block size, the smallest unit of file (default 1M))
   int m_sizeInBits;       // number of blocks = size-of-file/m_bufferSize
   char* m_buff;           // array of bit marking a download status of block
   int m_accessCnt;        // number of accesses
   std::vector<AStat> m_stat; // list of access statistics

   bool m_complete;        // file is completely downloaded (cached after read)

   Rec(): m_version(-1),
          m_bufferSize(1024*1024),
          m_buff(0), m_sizeInBits(0),
          m_accessCnt(0),
          m_complete(false)
   {
      struct stat st;
   }

    ~Rec() {
        if (m_buff) delete [] m_buff;
    }

    void setBit(int i)
    {
        int cn = i/8;
        int off = i - cn*8;
        m_buff[cn] |= BIT(off);
    }

    bool testBit(int i) const
    {
        int cn = i/8;
        int off = i - cn*8;
        return (m_buff[cn] & BIT(off)) == BIT(off);
    }
    void resizeBits(int s)
    {
        m_sizeInBits = s;
        m_buff = (char*)malloc(getSizeInBytes());
        memset(m_buff, 0, getSizeInBytes());
    }

    int getSizeInBytes() const
    {
        return (m_sizeInBits-1)/8 + 1;
    }

    int getSizeInBits() const
    {
        return m_sizeInBits;
    }

   bool isComplete() const
   {
      return m_complete;
   }

   int read(FILE* fp)
   {
      int fl = flock(fileno(fp),LOCK_SH );
      if (fl) printf("read lock err %s \n", strerror(errno));
      
      int off = 0;

      off += fread(&m_version, sizeof(int), 1, fp);
      off += fread(&m_bufferSize, sizeof(long long), 1, fp);
      int sb;
      if (fread(&sb, sizeof(int), 1, fp) != 1) return -1;
      resizeBits(sb);
      fread(m_buff, getSizeInBytes() , 1, fp);
 
      off = fread(&m_accessCnt, sizeof(int), 1, fp);

      m_stat.resize(m_accessCnt);
      AStat stat;
      int ai = 0;
      for (std::vector<AStat>::iterator i = m_stat.begin(); i != m_stat.end(); ++i, ai++) {
         int off = fread( &(*i), sizeof(AStat), 1, fp);
         if(off != 1)
         {
            printf("AStat[%d] read error, AStat size[%d] read returned %d \n",ai, sizeof(AStat), (int)off );
            return -1;
         }
      }

      int flu = flock(fileno(fp),LOCK_UN);
      if (flu) printf("read un-lock err %s \n", strerror(errno));

      m_complete = isAnythingEmptyInRng(0, sb-1) ? false : true;
      return 1;
   }
    //______________________________________________________________________________


   int write(FILE* fp)
   {
      if (fwrite(&m_version, sizeof(int), 1, fp) != 1) return -1;
      if (fwrite(&m_bufferSize, sizeof(long long), 1, fp) != 1) return -1;
      int nb = getSizeInBits();
      fwrite(&nb, sizeof(int), 1, fp);
      fwrite(m_buff, getSizeInBytes(), 1, fp);

      for (std::vector<AStat>::iterator i = m_stat.begin(); i != m_stat.end(); ++i) {
         fwrite( &(*i), sizeof(AStat), 1, fp);
      }
   }
    //______________________________________________________________________________



    bool isAnythingEmptyInRng(int firstIdx, int lastIdx)
    {
        for (int i = firstIdx; i <= lastIdx; ++i)
            if(! testBit(i)) return true;
    }
    //______________________________________________________________________________


   void print(bool full)
   {
      bool printBits = false;
      int cntd = 0;
      if (printBits) printf("printing %d blocks: \n", m_sizeInBits);
      for (int i = 0; i < m_sizeInBits; ++i)
      {
         if (printBits) printf("%d ", testBit(i));
         if (testBit(i)) cntd++;

      }
      if (printBits) printf("\n");

      printf("Stat version == %d, bufferSize %lld nBlocks %d nDownlaoded %d %s\n",m_version, m_bufferSize, m_sizeInBits , cntd, (m_sizeInBits == cntd) ? " complete" :"");
      if (full) {
         // printf("num access %d \n", m_accessCnt);
         printf("\n");
         for (int i=0; i < m_accessCnt; ++i)
         {
            printf("access %d >> ", i);
            m_stat[i].dump();
      
         }
      }
   }

};

//______________________________________________________________________________


void listdir(char* dirname, int lvl)
{

   int i;
   DIR* d_fh;
   struct dirent* entry;
   char longest_name[4096];

   while( (d_fh = opendir(dirname)) == NULL) {
      fprintf(stderr, "Couldn't open directory: %s\n", dirname);
      exit(-1);
   }

   while((entry=readdir(d_fh)) != NULL) {

      /* Don't descend up the tree or include the current directory */
      if(strncmp(entry->d_name, "..", 2) != 0 &&
         strncmp(entry->d_name, ".", 1) != 0) {

         /* If it's a directory print it's name and recurse into it */
         if (entry->d_type == DT_DIR) {
            // printf("directory %s (d)\n", entry->d_name);

            /* Prepend the current directory and recurse */
            strncpy(longest_name, dirname, 4095);
            strncat(longest_name, "/", 4095);
            strncat(longest_name, entry->d_name, 4095);
            listdir(longest_name, lvl+1);
         }
         else {

            /* Print some leading space depending on the directory level */
            // for(i=0; i < 2*lvl; i++)
            //    printf(" ");

            size_t sz = strlen(entry->d_name);
            if (!strncmp(&entry->d_name[sz-6], ".cinfo", 6)) {
               Rec r;
               const string fp = string(dirname) + "/" + string(entry->d_name);
               FILE* f = fopen(fp.c_str(),"r");
               int res = r.read(f);
               if (gSelectMode == kAll 
                   || (r.isComplete() && (gSelectMode == kComplete)) 
                   || ((!r.isComplete()) && (gSelectMode == kInComplete))) 
               {
                  printf("%s\n", fp.c_str());
                  if (gVerbose) {
                     r.print(true);
                     printf("\n");
                  }
               }

               fclose(f);
            }
         }
      }
   }

   closedir(d_fh);

   return;
}

void next_arg_or_die(std::vector<string>& args, std::vector<string>::iterator& i, bool allow_single_minus=false)
{
  std::vector<string>::iterator j = i;
  if (++j == args.end() || ((*j)[0] == '-' && ! (*j == "-" && allow_single_minus)))
  {
     printf("option %s reqires argument\n", (*i).c_str());
    exit(1);
  }
  i = j;
}

int main(int argc, char** argv)
{
 

   std::vector<string> args;
   for (int i = 1; i < argc; ++i)
   {
      args.push_back(argv[i]);
   }


   std::vector<string>::iterator i = args.begin(); 
   while (i != args.end())
   {
      std::vector<string>::iterator start = i;
      if (*i == "-h" || *i == "-help" || *i == "--help" || *i == "-?")
      {
         printf("Arguments: [options] path\n"
                "\n"
                " path       directory name of local cache"
                "\n"
                " --select   <all|complete|incomplete>  chose which info file to print options "
                "\n"
                " --verbose  print all info in *cinfo file including access statistics"
                "\n"

                );
         exit(0);
      }
      else if (*i == "--verbose")
      {
         gVerbose = true;
         i = args.erase(start, ++i);

         printf("after verbose > %s \n", (*i).c_str());
      } 
      else if (*i == "--select")
      {
         next_arg_or_die(args, i);
         std::string val = *i;
         if (val == "complete")
            gSelectMode = kComplete;
         else if (val == "incomplete")
            gSelectMode = kInComplete;
         else if (val == "all")
            gSelectMode = kAll;

         i = args.erase(start, ++i);

      } 
      else
      {
         ++i;
      }
   }


   if (args.size() != 1)
   {
      fprintf(stderr, "Error: exactly one dir should be requested, %d arguments found.\n", (int) args.size());
      exit(1);
   }



   
   listdir((char*)args.front().c_str(), 0);


   return 0;
}
