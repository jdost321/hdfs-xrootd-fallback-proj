package org.xrootd.hdfs.fallback;

import org.apache.hadoop.conf.Configuration;
import java.io.IOException;

public class XrdBlockFetcher
{
    public  long   m_native_handle;
    private int    m_block_size;
    private String m_url;
    private boolean m_is_open;

    // ----------------------------------------------------------------

    private native static void initConfig(String prefix, String postfix,
                                          String[] p2l_regexps,
                                          boolean  p2l_must_match);

    private native void createXrdClient(String url, int block_size);
    private native void destroyXrdClient(long handle);

    private native void openXrd(long handle);
    private native void readXrd(long handle, long offset, int len,
                                byte[] arr, int arr_offset);

    private void assert_is_open(String location) throws IOException
    {
        if ( ! isOpen())
        {
            throw new IOException("XrdBlockFetcher::" + location +
                                  "() xrootd file not opened.");
        }
    }

    // ----------------------------------------------------------------

    static
    {
        Configuration conf = new Configuration(false);
        conf.addResource("xfbfs-site.xml");

        initConfig(conf.get("xfbfs.xrootd_prefix"),
                   conf.get("xfbfs.xrootd_postfix"),
                   conf.getTrimmedStrings("xfbfs.pfn_to_lfn_regexps"),
                   conf.getBoolean("xfbfs.pfn_to_lfn_must_match", true));

        XrdUdpLog.send(0, "XrdBlockFetcher static initialization done.");
    }

    // ----------------------------------------------------------------

    public XrdBlockFetcher(String url, int block_size)
    {
        m_native_handle = 0;
        m_url           = url;
        m_block_size    = block_size;
        m_is_open       = false;

        createXrdClient(m_url, block_size);
        // m_native_handle set as the last line in above native function
    }

    public boolean isOpen()
    {
        return m_is_open;
    }

    public void open() throws IOException
    {
        openXrd(m_native_handle);
        // m_is_open set to true in above native function if success
    }

    public void close() throws IOException
    {
        assert_is_open("close");

        destroyXrdClient(m_native_handle);
        m_native_handle = 0;
    }

    public void read(long offset, int len, byte[] arr) throws IOException
    {
        assert_is_open("read");

        readXrd(m_native_handle, offset, len, arr, 0);
    }

    public void read(long offset, int len, byte[] arr, int arr_offset) throws IOException
    {
        assert_is_open("read");

        readXrd(m_native_handle, offset, len, arr, arr_offset);
    }
}
