/*
 * Copyright (C) 2012 Facebook, Inc.
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
package com.facebook.swift.codec;

@ThriftStruct("Bonk")
public class BonkMethod
{
    private String message;
    private int type;

    public BonkMethod()
    {
    }

    public BonkMethod(String message, int type)
    {
        this.message = message;
        this.type = type;
    }

    @ThriftField(1)
    public String getMessage()
    {
        return message;
    }

    @ThriftField(2)
    public int getType()
    {
        return type;
    }

    @ThriftField
    public void setData(String message, int type)
    {
        this.message = message;
        this.type = type;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BonkMethod bonkBean = (BonkMethod) o;

        if (type != bonkBean.type) {
            return false;
        }
        if (message != null ? !message.equals(bonkBean.message) : bonkBean.message != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = message != null ? message.hashCode() : 0;
        result = 31 * result + type;
        return result;
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append("BonkMethod");
        sb.append("{message='").append(message).append('\'');
        sb.append(", type=").append(type);
        sb.append('}');
        return sb.toString();
    }
}
