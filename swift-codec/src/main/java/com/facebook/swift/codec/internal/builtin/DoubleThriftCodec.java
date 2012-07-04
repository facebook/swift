/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.swift.codec.internal.builtin;

import com.facebook.swift.codec.ThriftCodec;
import com.facebook.swift.codec.metadata.ThriftType;
import com.google.common.base.Preconditions;
import org.apache.thrift.protocol.TProtocol;

import javax.annotation.concurrent.Immutable;

@Immutable
public class DoubleThriftCodec implements ThriftCodec<Double>
{
    @Override
    public ThriftType getType()
    {
        return ThriftType.DOUBLE;
    }

    @Override
    public Double read(TProtocol protocol)
            throws Exception
    {
        Preconditions.checkNotNull(protocol, "protocol is null");
        return protocol.readDouble();
    }

    @Override
    public void write(Double value, TProtocol protocol)
            throws Exception
    {
        Preconditions.checkNotNull(value, "value is null");
        Preconditions.checkNotNull(protocol, "protocol is null");
        protocol.writeDouble(value);
    }
}
