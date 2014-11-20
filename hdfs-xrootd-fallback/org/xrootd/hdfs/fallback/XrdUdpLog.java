package org.xrootd.hdfs.fallback;

import org.apache.hadoop.conf.Configuration;

import java.net.*;


public class XrdUdpLog
{
    private static boolean          m_failed;

    private static InetAddress      m_addr;
    private static int              m_port;
    private static int              m_level;
    private static DatagramSocket   m_sock;

    // ----------------------------------------------------------------

    static
    {
        m_failed = false;

        try
        {
            Configuration conf = new Configuration(false);
            conf.addResource("xfbfs-site.xml");

            m_addr  = InetAddress.getByName(conf.get("xfbfs.udplog_host"));
            m_port  = conf.getInt("xfbfs.udplog_port", 9415);
            m_level = conf.getInt("xfbfs.udplog_level", 0);

            m_sock = new DatagramSocket();


            String info = "XrdUdpLog initialized, sending to " +
                m_addr.getHostName() + ":" + m_port + ".";

            sendLowLevel(info);

            System.out.println(info);
        }
        catch (Exception exc)
        {
            m_failed = true;

            System.out.println("XrdUdpLog *FAILED* sending to " + m_addr.getHostName() + ":" + m_port + "\n" +
                               "   UDP logging is now disabled.");
        }

    }

    private static void sendLowLevel(String message) throws java.io.IOException
    {
        byte []        msg = message.getBytes();
        DatagramPacket pck = new DatagramPacket(msg, msg.length, m_addr, m_port);

        m_sock.send(pck);
    }

    public static void send(int lvl, String message)
    {
        if (m_failed || lvl > m_level) return;

        try
        {
            sendLowLevel(message);
        }
        catch (Exception exc)
        {
            // This really shouldn't happen as sending succeeded in static init.
            // Let's just log this for now.
            System.out.println("XrdUdpLog::send() exception caught: " + exc.getMessage());

            // If this startds happening often, we could disable further
            // sending (by setting m_failed = true) or try to recreate socket.
        }
    }
}
