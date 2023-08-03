package com.github.forax.concurrent.constant;

import sun.misc.Unsafe;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

record IndexedComputedConstant<V>(List<Object> states, int index, IntFunction<? extends V> mapper, ReentrantLock lock) implements ComputedConstant<V> {
  // states can be unbound (null), a computed value (anything), an error (State.Error) or null (NullObject.NULL)
  sealed interface State {
    record Error(Throwable throwable) {}
    enum NullObject implements State {
      NULL
    }
  }

  private static final Unsafe UNSAFE;
  private static final long ELEMENTS_OFFSET, ELEMENTS_BASE_OFFSET, ELEMENT_INDEX_SCALE;
  static {
    try {
      var theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafeField.setAccessible(true);
      UNSAFE = (Unsafe) theUnsafeField.get(null);

      var elementsField = Stream.of((Object) null).toList().getClass().getDeclaredField("elements");
      @Deprecated
      var elementsOffset = UNSAFE.objectFieldOffset(elementsField);
      ELEMENTS_OFFSET = elementsOffset;

      ELEMENTS_BASE_OFFSET = UNSAFE.arrayBaseOffset(Object[].class);
      ELEMENT_INDEX_SCALE = UNSAFE.arrayIndexScale(Object[].class);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new AssertionError(e);
    }
  }

  private static Object wrap(Object state) {
    return state == null ? State.NullObject.NULL : state;
  }

  private Object computeIfUnbound() {
    var states = this.states;
    var elements = (Object[]) UNSAFE.getObject(states, ELEMENTS_OFFSET);

    lock.lock();
    try {
      var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
      if (state == null) {
        try {
          state = wrap(mapper.apply(index));
        } catch (Throwable t) {
          state = new State.Error(t);
        }
        UNSAFE.putObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE, state);
      }
      return state;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isBinding() {
    var states = this.states;
    var elements = UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    return state == null && lock.isLocked();
  }
  @Override
  public boolean isBound() {
    var states = this.states;
    var elements = UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    return state != null;
  }
  @Override
  public boolean isError() {
    var states = this.states;
    var elements = UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    return state instanceof State.Error;
  }
  @Override
  public boolean isUnbound() {
    var states = this.states;
    var elements = UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    return state == null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public V get() {
    var states = this.states;
    var elements = (Object[]) UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    if (state == null) {
      state = computeIfUnbound();
    }
    if (state == State.NullObject.NULL) {
      return null;
    }
    if (state instanceof State.Error error) {
      throw rethrow(error.throwable);
    }
    return (V) state;
  }

  @SuppressWarnings("unchecked")
  private static <X extends Throwable> RuntimeException rethrow(Throwable throwable) throws X {
    throw (X) throwable;
  }

  @Override
  @SuppressWarnings("unchecked")
  public V orElse(V other) {
    var states = this.states;
    var elements = (Object[]) UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    if (state == null) {
      state = computeIfUnbound();
    }
    if (state == State.NullObject.NULL) {
      return null;
    }
    if (state instanceof State.Error) {
      return other;
    }
    return (V) state;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <X extends Throwable> V orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
    var states = this.states;
    var elements = (Object[]) UNSAFE.getObject(states, ELEMENTS_OFFSET);
    var state = UNSAFE.getObjectVolatile(elements, ELEMENTS_BASE_OFFSET + index * ELEMENT_INDEX_SCALE);
    if (state == null) {
      state = computeIfUnbound();
    }
    if (state == State.NullObject.NULL) {
      return null;
    }
    if (state instanceof State.Error) {
      throw exceptionSupplier.get();
    }
    return (V) state;
  }
}