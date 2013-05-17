/**
 * Copyright 2013 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.swift.service.protocol;

import com.facebook.nifty.client.FramedClientConnector;
import com.facebook.nifty.core.NettyConfigBuilder;
import com.facebook.nifty.core.NettyServerTransport;
import com.facebook.nifty.core.ThriftServerDef;
import com.facebook.nifty.duplex.TDuplexProtocolFactory;
import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.service.LogEntry;
import com.facebook.swift.service.ResultCode;
import com.facebook.swift.service.Scribe;
import com.facebook.swift.service.ThriftClient;
import com.facebook.swift.service.ThriftClientConfig;
import com.facebook.swift.service.ThriftClientManager;
import com.facebook.swift.service.ThriftMethod;
import com.facebook.swift.service.ThriftService;
import com.facebook.swift.service.ThriftServiceProcessor;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import io.airlift.units.Duration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TTransportException;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.util.HashedWheelTimer;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class TestClientProtocols
{
    @Test
    public void testBinaryProtocolClient()
            throws Exception
    {
        // Server and client use binary protocol
        try (ScopedServer server = new ScopedServer(new TBinaryProtocol.Factory());
             ThriftClientManager manager = new ThriftClientManager();
             Scribe client = createScribeClient(manager, server, new TBinaryProtocol.Factory())) {
            client.log(ImmutableList.of(new LogEntry("testCategory1", "testMessage1"),
                                        new LogEntry("testCategory2", "testMessage2")));
        }
    }

    @Test
    public void testCompactProtocolClient()
            throws Exception
    {
        // Server and client use compact protocol
        try (ScopedServer server = new ScopedServer(new TCompactProtocol.Factory());
             ThriftClientManager manager = new ThriftClientManager();
             Scribe client = createScribeClient(manager, server, new TCompactProtocol.Factory())) {
            client.log(ImmutableList.of(new LogEntry("testCategory1", "testMessage1"),
                                        new LogEntry("testCategory2", "testMessage2")));
        }
    }

    @Test(expectedExceptions = { TTransportException.class })
    public void testUnmatchedProtocols()
            throws Exception
    {
        // Setup a server to accept compact protocol, and try to send it a message with a binary
        // protocol client. Server should disconnect the client while client is trying to read the
        // response, so we should get a TTransportException
        try (ScopedServer server = new ScopedServer(new TCompactProtocol.Factory());
             ThriftClientManager manager = new ThriftClientManager();
             Scribe client = createScribeClient(manager, server, new TBinaryProtocol.Factory())) {
            client.log(ImmutableList.of(new LogEntry("testCategory1", "testMessage1"),
                                        new LogEntry("testCategory2", "testMessage2")));
        }
    }

    private Scribe createScribeClient(
            ThriftClientManager manager, ScopedServer server, TProtocolFactory protocolFactory)
            throws ExecutionException, InterruptedException, TException
    {
        ThriftClientConfig config = new ThriftClientConfig().setConnectTimeout(Duration.valueOf("1s"))
                                                            .setReadTimeout(Duration.valueOf("1s"))
                                                            .setWriteTimeout(Duration.valueOf("1s"));
        ThriftClient<Scribe> thriftClient = new ThriftClient<>(manager, Scribe.class);//, config, "ScribeClient");
        return thriftClient.open(new FramedClientConnector(HostAndPort.fromParts("localhost", server.getPort()),
                                                           TDuplexProtocolFactory.fromSingleFactory(protocolFactory))).get();
    }

    @ThriftService
    public class ScribeHandler
    {
        @ThriftMethod("Log")
        public ResultCode log(List<LogEntry> messages)
        {
            return ResultCode.OK;
        }
    }

    private class ScopedServer implements AutoCloseable
    {
        private final NettyServerTransport server;

        public ScopedServer(TProtocolFactory protocolFactory)
                throws TTransportException, InterruptedException
        {
            ThriftServiceProcessor processor = new ThriftServiceProcessor(new ThriftCodecManager(), new ScribeHandler());

            ThriftServerDef def = ThriftServerDef.newBuilder()
                                                 .listen(0)
                                                 .withProcessor(processor)
                                                 .speaks(protocolFactory).build();

            server = new NettyServerTransport(def, new NettyConfigBuilder(), new DefaultChannelGroup(), new HashedWheelTimer());
            server.start();
        }

        public int getPort()
        {
            InetSocketAddress address = (InetSocketAddress) server.getServerChannel().getLocalAddress();
            return address.getPort();
        }

        @Override
        public void close()
                throws Exception
        {
            server.stop();
        }
    }
}
