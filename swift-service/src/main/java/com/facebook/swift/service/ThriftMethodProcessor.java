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
package com.facebook.swift.service;

import com.facebook.swift.codec.ThriftCodec;
import com.facebook.swift.codec.ThriftCodecManager;
import com.facebook.swift.codec.internal.TProtocolReader;
import com.facebook.swift.codec.internal.TProtocolWriter;
import com.facebook.swift.codec.metadata.ThriftFieldMetadata;
import com.facebook.swift.codec.metadata.ThriftType;
import com.facebook.swift.service.metadata.ThriftMethodMetadata;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.weakref.jmx.Flatten;
import org.weakref.jmx.Managed;

import javax.annotation.concurrent.ThreadSafe;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static io.airlift.units.Duration.nanosSince;
import static org.apache.thrift.TApplicationException.INTERNAL_ERROR;

@ThreadSafe
public class ThriftMethodProcessor
{
    private final String name;
    private final Object service;
    private final Method method;
    private final String resultStructName;
    private final Map<Short, ThriftCodec<?>> parameterCodecs;
    private final ThriftCodec<Object> successCodec;
    private final Map<Class<?>, ExceptionProcessor> exceptionCodecs;

    private final ThriftMethodStats stats = new ThriftMethodStats();

    public ThriftMethodProcessor(
            Object service,
            ThriftMethodMetadata methodMetadata,
            ThriftCodecManager codecManager
    )
    {
        this.service = service;

        name = methodMetadata.getName();
        resultStructName = name + "_result";

        method = methodMetadata.getMethod();

        ImmutableMap.Builder<Short, ThriftCodec<?>> builder = ImmutableMap.builder();
        for (ThriftFieldMetadata fieldMetadata : methodMetadata.getParameters()) {
            builder.put(fieldMetadata.getId(), codecManager.getCodec(fieldMetadata.getType()));
        }
        parameterCodecs = builder.build();

        ImmutableMap.Builder<Class<?>, ExceptionProcessor> exceptions = ImmutableMap.builder();
        for (Map.Entry<Short, ThriftType> entry : methodMetadata.getExceptions().entrySet()) {
            Class<?> type = TypeToken.of(entry.getValue().getJavaType()).getRawType();
            ExceptionProcessor processor = new ExceptionProcessor(entry.getKey(), codecManager.getCodec(entry.getValue()));
            exceptions.put(type, processor);
        }
        exceptionCodecs = exceptions.build();

        successCodec = (ThriftCodec<Object>) codecManager.getCodec(methodMetadata.getReturnType());
    }

    @Managed
    public String getName()
    {
        return name;
    }

    public Class<?> getServiceClass() {
        return service.getClass();
    }

    @Managed
    @Flatten
    public ThriftMethodStats getStats()
    {
        return stats;
    }

    public void process(TProtocol in, TProtocol out, int sequenceId)
            throws Exception
    {
        long start = System.nanoTime();

        // read args
        Object[] args = readArguments(in);

        // invoke method
        Object result;
        try {
            result = invokeMethod(args);
            // write success reply
            writeResponse(out, sequenceId, "success", 0, successCodec, result);

            stats.addSuccessTime(nanosSince(start));
        }
        catch (Exception e) {
            ExceptionProcessor exceptionCodec = exceptionCodecs.get(e.getClass());
            if (exceptionCodec != null) {
                // write application exception response
                writeResponse(out, sequenceId, "exception", exceptionCodec.getId(), exceptionCodec.getCodec(), e);
                stats.addErrorTime(nanosSince(start));
            }
            else {
                // unexpected exception
                TApplicationException applicationException = new TApplicationException(INTERNAL_ERROR, "Internal error processing " + method.getName());
                applicationException.initCause(e);
                stats.addErrorTime(nanosSince(start));
                throw applicationException;
            }
        }
    }

    private Object invokeMethod(Object[] args)
            throws Exception
    {
        long start = System.nanoTime();
        try {
            Object response = method.invoke(service, args);
            stats.addInvokeTime(nanosSince(start));
            return response;
        }
        catch (Throwable e) {
            // strip off the InvocationTargetException wrapper if present
            if (e instanceof InvocationTargetException) {
                InvocationTargetException invocationTargetException = (InvocationTargetException) e;
                if (invocationTargetException.getTargetException() != null) {
                    e = invocationTargetException.getTargetException();
                }
            }

            // rethrow Exceptions or Errors
            Throwables.propagateIfPossible(e, Exception.class);

            // Wrap random extensions of Throwable in a runtime exception
            throw Throwables.propagate(e);
        }
    }

    private Object[] readArguments(TProtocol in)
            throws Exception
    {
        long start = System.nanoTime();

        try {
            Object[] args = new Object[method.getParameterTypes().length];
            TProtocolReader reader = new TProtocolReader(in);

            reader.readStructBegin();
            while (reader.nextField()) {
                short fieldId = reader.getFieldId();

                ThriftCodec<?> codec = parameterCodecs.get(fieldId);
                if (codec == null) {
                    // unknown field
                    reader.skipFieldData();
                }

                args[fieldId - 1] = reader.readField(codec);
            }
            reader.readStructEnd();

            stats.addReadTime(nanosSince(start));
            return args;
        }
        catch (TProtocolException e) {
            // TProtocolException is the only recoverable exception
            // Other exceptions may have left the input stream in corrupted state so we must
            // tear down the socket.
            throw new TApplicationException(TApplicationException.PROTOCOL_ERROR, e.getMessage());
        }
    }

    private <T> void writeResponse(TProtocol out, int sequenceId, String responseFieldName, int responseFieldId, ThriftCodec<T> responseCodec, T result)
            throws Exception
    {
        long start = System.nanoTime();

        out.writeMessageBegin(new TMessage(name, TMessageType.REPLY, sequenceId));

        TProtocolWriter writer = new TProtocolWriter(out);
        writer.writeStructBegin(resultStructName);
        writer.writeField(responseFieldName, (short) responseFieldId, responseCodec, result);
        writer.writeStructEnd();

        out.writeMessageEnd();
        out.getTransport().flush();

        stats.addWriteTime(nanosSince(start));
    }

    private static final class ExceptionProcessor
    {
        private final short id;
        private final ThriftCodec<Object> codec;

        private ExceptionProcessor(short id, ThriftCodec<?> coded)
        {
            this.id = id;
            this.codec = (ThriftCodec<Object>) coded;
        }

        public short getId()
        {
            return id;
        }

        public ThriftCodec<Object> getCodec()
        {
            return codec;
        }
    }
}
