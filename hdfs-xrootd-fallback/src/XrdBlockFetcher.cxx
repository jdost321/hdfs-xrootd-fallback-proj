#include "XrdBlockFetcher.h"

#include "XrdCl/XrdClFile.hh"

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

    int                       pfn_to_lfn_size;
    std::vector<pcrecpp::RE*> pfn_to_lfn_regexps;
    std::vector<std::string>  pfn_to_lfn_replacements;

    bool                      pfn_to_lfn_must_match;

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

  XrdCl::File        *m_xrd_client;
  XrdCl::StatInfo    *m_stat_info;

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

  jbyte* get_cache(JNIEnv *env, long long offset, int &bytes_available)
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
        if (offset + len > m_stat_info->GetSize())
        {
          len = m_stat_info->GetSize() - offset;
        }
      }

      uint32_t bytes_read;
      XrdCl::Status st = m_xrd_client->Read(offset, len, &m_cache[0], bytes_read);
      if ( ! st.IsOK())
      {
        throw_io(env, strprintf("XrdMan::get_cache() Failed Stat on URL '%s', errnum=%d, errstr:\n%s",
                                m_url.c_str(), st.errNo, st.ToString().c_str()));
        return 0;
      }

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
    m_xrd_client     (0),
    m_stat_info      (0),
    m_cache_offset   (-1),
    m_cache_capacity (std::min(512*1024, block_size)),
    m_cache_size     (0)
  {
    m_url = url;
    if (s_config.pfn_to_lfn(m_url))
    {
      send_log(env, 1, strprintf("XrdMan::XrdMan() PFN to LFN:\n  %s\n  %s", url, m_url.c_str()));
    }
    /* drop lfn error handling until we fix throws in JNI
    else
    {
      if (s_config.pfn_to_lfn_must_match)
      {
        throw_io(env, strprintf("XrdMan::XrdMan() Mandatory PFN to LFN match/replace failed for '%s'.",
                                url));
      }
    }
    */

    m_url = strprintf("%s%s?hdfs_block_size=%d%s",
                      s_config.prefix.c_str(),
                      m_url.c_str(), block_size,
                      s_config.postfix.c_str());

    m_xrd_client = new XrdCl::File();

    m_cache.resize(m_cache_capacity);
  }

  ~XrdMan()
  {
    m_xrd_client->Close();
    delete m_xrd_client;
    delete m_stat_info;
  }

  void AboutToDestroy(JNIEnv *env)
  {
    // This just sends a log message. Ideally would do it in the detructor
    // but I do need JNIenv for sending.

    send_log (env, 0, strprintf("XrdMan::~XrdMan() Closing LFN '%s'", m_url.c_str()));
  }

  bool Open(JNIEnv *env)
  {
    send_log(env, 0, strprintf("XrdMan::Open() Opening LFN '%s'", m_url.c_str()));

    XrdCl::XRootDStatus st;

    st = m_xrd_client->Open(m_url, XrdCl::OpenFlags::Read);

    if ( ! st.IsOK())
    {
      throw_io(env, strprintf("XrdMan::Open() Failed Open on URL '%s', errnum=%d, errstr:\n%s",
                              m_url.c_str(), st.errNo, st.ToString().c_str()));
      return false;
    }

    st = m_xrd_client->Stat(false, m_stat_info);

    if ( ! st.IsOK())
    {
      throw_io(env, strprintf("XrdMan::Open() Failed Stat on URL '%s', errnum=%d, errstr:\n%s",
                              m_url.c_str(), st.errNo, st.ToString().c_str()));
      return false;
    }

    return true;
  }

  void Read(JNIEnv *env, long long offset, int length, jbyteArray arr, int arr_offset)
  {
    send_log(env, 2, strprintf("XrdMan::Read(off=%lld, len=%d) %s", offset, length, m_url.c_str()));

    if (length <= 0)
    {
      return;
    }
    if (offset + length > m_stat_info->GetSize() || offset < 0)
    {
      throw_io(env, strprintf("XrdMan::Read(off=%lld, len=%d) Request for data outside of file, file-size=%llu.",
                             offset, length, m_stat_info->GetSize()));
      return;
    }

    long long position = 0;

    while (length > 0)
    {
      int    bytes_available;

      jbyte *cache   = get_cache(env, offset + position, bytes_available);
      if (cache == 0)
      {
        // Exception set from get_cache().
        return;
      }

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

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_openXrd
(JNIEnv *env, jobject self, jlong handle)
{
  jclass   jcls = env->GetObjectClass(self);
  jfieldID jfid = env->GetFieldID(jcls, "m_is_open", "Z");

  XrdMan *xman = (XrdMan*) handle;

  if (xman->Open(env))
    env->SetBooleanField(self, jfid, true);
}

JNIEXPORT void JNICALL Java_org_xrootd_hdfs_fallback_XrdBlockFetcher_readXrd
(JNIEnv *env, jobject self, jlong handle, jlong offset, jint length,
 jbyteArray arr, jint arr_offset)
{
  XrdMan *xman = (XrdMan*) handle;

  xman->Read(env, offset, length, arr, arr_offset);
}
