/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.swift.codec;

import javax.annotation.concurrent.Immutable;

@Immutable
@ThriftStruct("Bonk")
public class BonkConstructor {
  private final String message;
  private final int type;

  @ThriftConstructor
  public BonkConstructor(String message, int type) {
    this.message = message;
    this.type = type;
  }

  @ThriftField(1)
  public String getMessage() {
    return message;
  }

  @ThriftField(2)
  public int getType() {
    return type;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    BonkConstructor that = (BonkConstructor) o;

    if (type != that.type) {
      return false;
    }
    if (message != null ? !message.equals(that.message) : that.message != null) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = message != null ? message.hashCode() : 0;
    result = 31 * result + type;
    return result;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder();
    sb.append("BonkConstructor");
    sb.append("{message='").append(message).append('\'');
    sb.append(", type=").append(type);
    sb.append('}');
    return sb.toString();
  }
}
