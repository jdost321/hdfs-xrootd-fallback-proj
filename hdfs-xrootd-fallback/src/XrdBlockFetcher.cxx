#include "XrdBlockFetcher.h"

#include "XrdClient/XrdClient.hh"

#include <pcrecpp.h>

#include <memory>
#include <string>
#include <list>
#include <algorithm>

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <fcntl.h>

#include <unistd.h>

//==============================================================================
// Utility
//==============================================================================

std::string strprintf(const char* fmt, ...)
{
  int size = 128;

  std::string str;

  va_list ap;
  while (true)
  {
    str.resize(size);
    va_start(ap, fmt);
    int n = vsnprintf((char *)str.c_str(), size, fmt, ap);
    va_end(ap);
    if (n > -1 && n < size)
      return str;

    if (n > -1)
      size = n + 1;
    else
      size *= 2;
  }
}


//==============================================================================
// C++ class
//==============================================================================

class XrdMan
{
public:

  struct Config
  {
    std::string prefix;
    std::string postfix;

    int                      pfn_to_lfn_size;
    std::vector<pcrecpp::RE*> pfn_to_lfn_regexps;
    std::vector<std::string> pfn_to_lfn_replacements;

    bool                     pfn_to_lfn_must_match;

    Config() :
      pfn_to_lfn_size (0),
      pfn_to_lfn_must_match (true)
    {
      // Static init of XrdBlockFetcher java reads /etc/hadoop/xfbfs-site.xml
      // and calls C++ implementation of initConfig() ... see further down.

      fprintf(stderr, "\n"
"XrdMan::Config instantiated, will be filled from Hadoop that reads config\n"
"from /etc/hadoop/xfbfs-site.xml on first exception.\n");

      prefix = "root://uaf-9.t2.ucsd.edu/";
    }

    ~Config()
    {
      for (std::vector<pcrecpp::RE*>::iterator i = pfn_to_lfn_regexps.begin(); i != pfn_to_lfn_regexps.end(); ++i)
        delete *i;
    }

    void add_pfn_to_lfn_rule(const std::string &regexp, const std::string &replace)
    {
      ++pfn_to_lfn_size;
      pfn_to_lfn_regexps     .push_back(new pcrecpp::RE(regexp));
      pfn_to_lfn_replacements.push_back(replace);
    }

    bool pfn_to_lfn(std::string &pfn)
    {
      // Returns true on first successful match'n'replace.
      // Returns false if all matches fail.

      for (int i = 0; i < pfn_to_lfn_size; ++i)
      {
        if (pfn_to_lfn_regexps[i]->Replace(pfn_to_lfn_replacements[i], &pfn))
        {
          return true;
        }
      }
      return false;
    }
  };

  static Config  s_config;
  static int     s_udp_log_fd;

private:

  std::string         m_url;
  int                 m_block_size;

  XrdClient          *m_xrd_client;
  XrdClientStatInfo   m_stat_info;

  std::vector<jbyte>  m_cache;
  long long           m_cache_offset;
  const int           m_cache_capacity;
  int                 m_cache_size;

  void throw_io(JNIEnv *env, const std::string& what)
  {
    env->ThrowNew(env->FindClass("java/io/IOException"), what.c_str());
  }

  void send_log(JNIEnv *env, jint lvl, const std::string& message)
  {
    // XXXX Can jcls and jmth be kept across different envs in member variables?
    jclass    jcls = env->FindClass("org/xrootd/hdfs/fallback/XrdUdpLog");
    jmethodID jmth = env->GetStaticMethodID(jcls, "send", "(ILjava/lang/String;)V");
    jstring   jmsg = env->NewStringUTF(message.c_str());

    env->CallStaticVoidMethod(jcls, jmth, lvl, jmsg);

    // Apparently this is not needed if the thread returns to java.
    // env->DeleteLocalRef(jmsg);
    // env->DeleteLocalRef(jcls);
  }

  jbyte* get_cache(long long offset, int &bytes_available)
  {
    // Make sure we have byte 'offset' in the local cache.
    //
    // Returns the cache position storing the byte at 'offset' and sets
    // 'bytes_available' argument to the number of bytes available in cache
    // from 'offset' onwards.

    if (offset < m_cache_offset || offset >= m_cache_offset + m_cache_size)
    {
      // We have to refetch cache, let's decide how much.

      // Do not read more than cache capacity.
      int len = m_cache_capacity;

      // Do not read beyond block boundary.
      {
        long long boundary = (offset / m_block_size + 1) * m_block_size;

        if (offset + len > boundary)
        {
          len = boundary - offset;
        }
      }

      // Do not read beyond file size.
      {
        if (offset + len > m_stat_info.size)
        {
          len = m_stat_info.size - offset;
        }
      }

      m_xrd_client->Read(&m_cache[0], offset, len);

      m_cache_offset = offset;
      m_cache_size   = len;

      bytes_available = len;

      return &m_cache[0];
    }
    else
    {
      int off_diff = offset - m_cache_offset;
      bytes_available = m_cache_size - off_diff;

      return &m_cache[off_diff];
    }
  }

public:

  XrdMan(JNIEnv* env, const char* url, int block_size) :
    m_block_size     (block_size),
    m_cache_offset   (-1),
    m_cache_capacity (std::min(512*1024, block_size)),
    m_cache_size     (0)
  {
    m_url = url;
    if (s_config.pfn_to_lfn(m_url))
    {
      send_log(env, 1, strprintf("XrdMan::XrdMan() PFN to LFN:\n  %s\n  %s", url, m_url.c_str()));
    }
    else
    {
      if (s_config.pfn_to_lfn_must_match)
      {
        throw_io(env, strprintf("XrdMan::XrdMan() Mandatory PFN to LFN match/replace failed for '%s'.",
                                url));
      }
    }

    m_url = strprintf("%s%s?hdfs_block_size=%d%s",
                      s_config.prefix.c_str(),
                      m_url.c_str(), block_size,
                      s_config.postfix.c_str());

    send_log(env, 0, strprintf("XrdMan::XrdMan() Opening LFN '%s'", m_url.c_str()));

    m_xrd_client = new XrdClient(m_url.c_str());

    if ( ! m_xrd_client->Open(0, kXR_async) ||
           m_xrd_client->LastServerResp()->status != kXR_ok)
    {
      struct ServerResponseBody_Error *srb = m_xrd_client->LastServerError();

      throw_io(env, strprintf("XrdMan::XrdMan() Failed opening URL '%s', errnum=%d. Server error:\n%s",
                              m_url.c_str(), srb->errnum, srb->errmsg));
    }

    m_xrd_client->Stat(&m_stat_info);

    m_cache.resize(m_cache_capacity);
  }

  ~XrdMan()
  {
    delete m_xrd_client;
  }

  void AboutToDestroy(JNIEnv *env)
  {
    // This just sends a log message. Ideally would do it in the detructor
    // but I do need JNIenv for sending.

    send_log (env, 0, strprintf("XrdMan::~XrdMan() Closing LFN '%s'", m_url.c_str()));
  }

  void Read(JNIEnv *env, long long offset, int length, jbyteArray arr, int arr_offset)
  {
    send_log(env, 2, strprintf("XrdMan::Read(off=%lld, len=%d) %s", offset, length, m_url.c_str()));

    if (length <= 0)
    {
      return;
    }
    if (offset + length > m_stat_info.size || offset < 0)
    {
      throw_io(env, strprintf("XrdMan::Read(off=%lld, len=%d) Request for data outside of file, file-size=%lld.",
                             offset, length, m_stat_info.size));
    }

    long long position = 0;

    while (length > 0)
    {
      int    bytes_available;
      jbyte *cache   = get_cache(offset + position, bytes_available);
      int    to_copy = std::min(bytes_available, length);

      env->SetByteArrayRegion(arr, arr_offset, to_copy, cache);

      length     -= to_copy;
      position   += to_copy;
      arr_offset += to_copy;
    }

  }
};

XrdMan::Config  XrdMan::s_config;
int             XrdMan::s_udp_log_fd = -1;

//==============================================================================
// Implementation of Java methods
//==============================================================================

namespace
{
  std::string getJniString(JNIEnv *env, jstring jstr)
  {
    if (jstr == 0) return std::string();

    const char *str = env->GetStringUTFChars(jstr,  0);
    std::string ret(str);
    env->ReleaseStringUTFChars(jstr, str);
    return ret;
  }
}

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_initConfig
(JNIEnv *env, jclass cls,
 jstring prefix, jstring postfix,
 jobjectArray p2l_regexps,
 jboolean p2l_must_match)
{
  // Open socket to UDP listener
  // XrdMan::s_udp_log_fd = ...

  XrdMan::s_config.prefix  = getJniString(env, prefix);
  XrdMan::s_config.postfix = getJniString(env, postfix);

  XrdMan::s_config.pfn_to_lfn_must_match = p2l_must_match;

  printf("================================================================================\n"
         "XrdBlockFetcher native configuration\n"
         "--------------------------------------------------------------------------------\n"
         "prefix=%s\n"
         "postfix=%s\n"
         "regexp_must_match=%s\n",
         XrdMan::s_config.prefix.c_str(), XrdMan::s_config.postfix.c_str(), p2l_must_match ? "true" : "false");

  printf("--------------------------------------------------------------------------------\n");

  int re_count = env->GetArrayLength(p2l_regexps) / 2;

  for (int i = 0; i < re_count; ++i)
  {
    std::string regexp  = getJniString(env, (jstring) env->GetObjectArrayElement(p2l_regexps, 2*i));
    std::string replace = getJniString(env, (jstring) env->GetObjectArrayElement(p2l_regexps, 2*i + 1));

    printf("regexp %2d: '%s'  ->  '%s'\n", i, regexp.c_str(), replace.c_str());

    XrdMan::s_config.add_pfn_to_lfn_rule(regexp, replace);
  }
  printf("================================================================================\n");

  fflush(stdout);
}

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_createXrdClient
(JNIEnv *env, jobject self, jstring url, jint block_size)
{
  jclass   jcls = env->GetObjectClass(self);
  jfieldID jfid = env->GetFieldID(jcls, "m_native_handle", "J");

  XrdMan *xman = new XrdMan(env,
                            getJniString(env, url).c_str(),
                            block_size);

  env->SetLongField(self, jfid, (long long) xman);
}

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_destroyXrdClient
(JNIEnv *env, jobject self, jlong handle)
{
  XrdMan *xman = (XrdMan*) handle;

  xman->AboutToDestroy(env);
  delete xman;
}

//------------------------------------------------------------------------------

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_readXrd
(JNIEnv *env, jobject self, jlong handle, jlong offset, jint length,
 jbyteArray arr, jint arr_offset)
{
  XrdMan *xman = (XrdMan*) handle;

  xman->Read(env, offset, length, arr, arr_offset);
}
