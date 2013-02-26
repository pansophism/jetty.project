//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer;
import org.eclipse.jetty.websocket.client.blockhead.BlockheadServer.ServerConnection;
import org.junit.Assert;
import org.junit.Test;

public class TomcatServerQuirksTest
{
    public static class LatchedSocket extends WebSocketAdapter
    {
        final CountDownLatch openLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            openLatch.countDown();
        }

        @Override
        public void onWebSocketText(String message)
        {
            dataLatch.countDown();
        }
    }

    /**
     * Test for when encountering a "Transfer-Encoding: chunked" on a Upgrade Response header.
     * <ul>
     * <li><a href="https://bugs.eclipse.org/bugs/show_bug.cgi?id=393075">Eclipse Jetty Bug #393075</a></li>
     * <li><a href="https://issues.apache.org/bugzilla/show_bug.cgi?id=54067">Apache Tomcat Bug #54067</a></li>
     * </ul>
     * 
     * @throws IOException
     */
    @Test
    public void testTomcat7_0_32_WithTransferEncoding() throws Exception
    {
        BlockheadServer server = new BlockheadServer();
        WebSocketClient client = new WebSocketClient();

        try
        {
            int bufferSize = 512;

            server.start();

            // Setup Client Factory
            client.start();

            // Create End User WebSocket Class
            LatchedSocket websocket = new LatchedSocket();

            // Open connection
            URI wsURI = server.getWsUri();
            client.connect(websocket,wsURI);

            // Accept incoming connection
            ServerConnection socket = server.accept();
            socket.setSoTimeout(2000); // timeout

            // Issue upgrade
            // Add the extra problematic header that triggers bug found in jetty-io
            socket.addResponseHeader("Transfer-Encoding","chunked");
            socket.upgrade();

            // Wait for proper upgrade
            Assert.assertTrue("Timed out waiting for Client side WebSocket open event",websocket.openLatch.await(1,TimeUnit.SECONDS));

            // Have server write frame.
            int length = bufferSize / 2;
            ByteBuffer serverFrame = ByteBuffer.allocate(bufferSize);
            serverFrame.put((byte)(0x80 | 0x01)); // FIN + TEXT
            serverFrame.put((byte)0x7E); // No MASK and 2 bytes length
            serverFrame.put((byte)(length >> 8)); // first length byte
            serverFrame.put((byte)(length & 0xFF)); // second length byte
            for (int i = 0; i < length; ++i)
            {
                serverFrame.put((byte)'x');
            }
            serverFrame.flip();
            byte buf[] = serverFrame.array();
            socket.write(buf,0,buf.length);
            socket.flush();

            Assert.assertTrue(websocket.dataLatch.await(1000,TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
            server.stop();
        }
    }
}
