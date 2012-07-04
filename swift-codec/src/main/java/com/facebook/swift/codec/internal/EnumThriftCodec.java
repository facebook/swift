/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.swift.codec.internal;

import com.facebook.swift.codec.ThriftCodec;
import com.facebook.swift.codec.metadata.ThriftEnumMetadata;
import com.facebook.swift.codec.metadata.ThriftType;
import com.google.common.base.Preconditions;
import org.apache.thrift.protocol.TProtocol;

import javax.annotation.concurrent.Immutable;

/**
 * EnumThriftCodec is a codec for Java enum types.  An enum is encoded as an I32 in Thrift, and this
 * class handles converting this vale to a Java enum constant.
 */
@Immutable
public class EnumThriftCodec<T extends Enum<T>> implements ThriftCodec<T>
{
    private final ThriftType type;
    private final ThriftEnumMetadata<T> enumMetadata;

    public EnumThriftCodec(ThriftType type)
    {
        this.type = type;
        enumMetadata = (ThriftEnumMetadata<T>) type.getEnumMetadata();
    }

    @Override
    public ThriftType getType()
    {
        return type;
    }

    @Override
    public T read(TProtocol protocol)
            throws Exception
    {
        int enumValue = protocol.readI32();
        if (enumValue >= 0) {
            if (enumMetadata.hasExplicitThriftValue()) {
                T enumConstant = enumMetadata.getByEnumValue().get(enumValue);
                if (enumConstant != null) {
                    return enumConstant;
                }
            }
            else {
                T[] enumConstants = enumMetadata.getEnumClass().getEnumConstants();
                if (enumValue < enumConstants.length) {
                    return enumConstants[enumValue];
                }
            }
        }
        throw new IllegalArgumentException(
                String.format(
                        "Enum %s does not have a value for %s",
                        enumMetadata.getEnumClass(),
                        enumValue
                )
        );
    }

    @Override
    public void write(T enumConstant, TProtocol protocol)
            throws Exception
    {
        Preconditions.checkNotNull(enumConstant, "enumConstant is null");

        int enumValue;
        if (enumMetadata.hasExplicitThriftValue()) {
            enumValue = enumMetadata.getByEnumConstant().get(enumConstant);
        }
        else {
            enumValue = enumConstant.ordinal();
        }
        protocol.writeI32(enumValue);
    }
}
