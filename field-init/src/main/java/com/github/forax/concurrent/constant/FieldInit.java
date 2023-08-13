package com.github.forax.concurrent.constant;

import java.lang.invoke.MethodHandles;
import java.util.Objects;

public sealed interface FieldInit permits FieldInitImpl {
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

  <T> T get(Object instance, String fieldName);
  default double getBoolean(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default byte getByte(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default short getShort(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default char getChar(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default int getInt(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default long getLong(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default float getFloat(Object instance, String fieldName) {
    return get(instance, fieldName);
  }
  default double getDouble(Object instance, String fieldName) {
    return get(instance, fieldName);
  }

  static FieldInit ofStatic(MethodHandles.Lookup lookup) {
    Objects.requireNonNull(lookup);
    var staticMH = FieldInitImpl.createStaticMH(lookup);
    return new FieldInitImpl(null, staticMH);
  }

  static FieldInit ofInstance(MethodHandles.Lookup lookup) {
    Objects.requireNonNull(lookup);
    var instanceMH = FieldInitImpl.createInstanceMH(lookup);
    return new FieldInitImpl(instanceMH, null);
  }

  static FieldInit of(MethodHandles.Lookup lookup) {
    Objects.requireNonNull(lookup);
    var instanceMH = FieldInitImpl.createInstanceMH(lookup);
    var staticMH = FieldInitImpl.createStaticMH(lookup);
    return new FieldInitImpl(instanceMH, staticMH);
  }
}
