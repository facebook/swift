/**
 * Copyright 2012 Facebook, Inc.
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
package com.facebook.swift.service.base;

import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.service.ThriftClientManager;
import com.facebook.swift.service.ThriftServer;
import com.facebook.swift.service.ThriftServiceProcessor;
import com.google.common.net.HostAndPort;
import org.apache.thrift.transport.TTransportException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

public class TestSuiteBase<ServiceInterface, ClientInterface> {
    private ThriftCodecManager codecManager = new ThriftCodecManager();
    private ThriftClientManager clientManager;
    private Class<? extends ClientInterface> clientClass;
    private Class<? extends ServiceInterface> handlerClass;
    private ClientInterface client;
    private ThriftServer server;
    private ServiceInterface handler;

    public TestSuiteBase(Class<? extends ServiceInterface> handlerClass,
                         Class<? extends ClientInterface> clientClass) {
        this.clientClass = clientClass;
        this.handlerClass = handlerClass;
    }

    @BeforeClass
    public void setupSuite() throws InstantiationException, IllegalAccessException {
        handler = handlerClass.newInstance();
        server = createServer(handler).start();
    }

    @BeforeMethod
    public void setupTest() throws TTransportException {
        clientManager = new ThriftClientManager(codecManager);
        client = createClient(clientManager);
    }

    @AfterMethod
    public void tearDownTest() throws Exception {
        AutoCloseable closeable = (AutoCloseable) client;
        closeable.close();
        clientManager.close();
    }

    @AfterClass
    public void tearDownSuite() {
        server.close();
    }

    private ThriftServer createServer(ServiceInterface handler)
            throws IllegalAccessException, InstantiationException {
        ThriftServiceProcessor processor = new ThriftServiceProcessor(codecManager, handler);
        return new ThriftServer(processor);
    }

    private ClientInterface createClient(ThriftClientManager clientManager) throws
      TTransportException {
        HostAndPort address = HostAndPort.fromParts("localhost", server.getPort());
        return clientManager.createClient(address, clientClass);
    }

    protected ClientInterface getClient() {
        return client;
    }

    protected ServiceInterface getHandler()
    {
        return handler;
    }
}
