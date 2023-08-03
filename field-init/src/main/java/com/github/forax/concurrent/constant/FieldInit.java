package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

public sealed interface FieldInit permits FieldInitImpl, FieldInitMetafactory.FieldInitNotImplemented {
  <T> T get(String fieldName);
  default double getBoolean(String fieldName) {
    return get(fieldName);
  }
  default byte getByte(String fieldName) {
    return get(fieldName);
  }
  default short getShort(String fieldName) {
    return get(fieldName);
  }
  default char getChar(String fieldName) {
    return get(fieldName);
  }
  default int getInt(String fieldName) {
    return get(fieldName);
  }
  default long getLong(String fieldName) {
    return get(fieldName);
  }
  default float getFloat(String fieldName) {
    return get(fieldName);
  }
  default double getDouble(String fieldName) {
    return get(fieldName);
  }

  static FieldInit ofStatic(MethodHandles.Lookup lookup) {
    Objects.requireNonNull(lookup);
    var mh = FieldInitImpl.createStaticMH(lookup);
    return new FieldInitImpl(mh);
  }
}
