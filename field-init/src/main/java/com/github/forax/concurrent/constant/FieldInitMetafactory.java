package com.github.forax.concurrent.constant;

import com.github.forax.concurrent.constant.FieldInitImpl.Computed;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;

public class FieldInitMetafactory {
  private static final class FieldInitData {
    private final ConcurrentHashMap<String, Computed> computedMap = new ConcurrentHashMap<>();
    private MethodHandle staticFieldInit;  // cached

    public MethodHandle staticFieldInit(MethodHandles.Lookup lookup) {
      var staticFieldInit = this.staticFieldInit;
      if (staticFieldInit != null) {
        return staticFieldInit;
      }
      return this.staticFieldInit = FieldInitImpl.findStaticFieldInit(lookup);
    }
  }

  private static final ClassValue<FieldInitData> FIELD_INIT_DATA_CLASS_VALUE = new ClassValue<>() {
    @Override
    protected FieldInitData computeValue(Class<?> type) {
      return new FieldInitData();
    }
  };

  public static Object staticFieldInit(MethodHandles.Lookup lookup, String name, Class<?> type) throws Throwable {
    if ((lookup.lookupModes() & (MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.ORIGINAL)) == 0) {
      throw new IllegalAccessException("wrong lookup access mode " + lookup.lookupModes());
    }

    var fieldInitData = FIELD_INIT_DATA_CLASS_VALUE.get(lookup.lookupClass());
    var staticFieldInit = fieldInitData.staticFieldInit(lookup);

    return FieldInitImpl.computeIfUnbound(fieldInitData.computedMap, name, staticFieldInit, null);
  }
}
