package com.fithub.forax.concurrent.constant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

public final class ComputedConstantTest {

  @Nested
  class ComputedConstants {
    @Test
    public void get() {
      var constant = ComputedConstant.of(() -> 42);
      assertEquals(42, constant.get());
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void getFail() {
      var constant = ComputedConstant.of(() -> { throw null; });
      assertThrows(NullPointerException.class, constant::get);
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertTrue(constant.isError())
      );
    }

    @Test
    public void getFailSameException() {
      var constant = ComputedConstant.of(() -> { throw null; });
      var e1 = assertThrows(NullPointerException.class, constant::get);
      var e2 = assertThrows(NullPointerException.class, constant::get);
      assertSame(e1, e2);
    }

    @Test
    public void getNull() {
      var constant = ComputedConstant.of(() -> null);
      assertNull(constant.get());
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void orElse() {
      var constant = ComputedConstant.of(() -> 21);
      assertEquals(21, constant.orElse(42));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void orElseFail() {
      var constant = ComputedConstant.of(() -> { throw null; });
      assertEquals(42, constant.orElse(42));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertTrue(constant.isError())
      );
    }

    @Test
    public void orElseThrow() {
      var constant = ComputedConstant.of(() -> 21);
      assertEquals(21, constant.orElseThrow(RuntimeException::new));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void getOrElseNull() {
      var constant = ComputedConstant.of(() -> null);
      assertNull(constant.orElse(42));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void orElseThrowFail() {
      var constant = ComputedConstant.of(() -> { throw null; });
      assertThrows(RuntimeException.class, () -> constant.orElseThrow(RuntimeException::new));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertTrue(constant.isError())
      );
    }

    @Test
    public void getOrElseThrowNull() {
      var constant = ComputedConstant.of(() -> null);
      assertNull(constant.orElseThrow(RuntimeException::new));
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void defaultState() {
      var constant = ComputedConstant.of(() -> 42);
      assertAll(
          () -> assertTrue(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertFalse(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    @Test
    public void isBinding() throws InterruptedException {
      var constant = ComputedConstant.of(() -> {
        try {
          Thread.sleep(1_000);
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
        return 42;
      });
      Thread thread = new Thread(() -> {
        assertEquals(42, constant.get());
      });
      thread.start();
      Thread.sleep(1);
      assertTrue(constant.isBinding());
      thread.join();
    }

    @Test
    public void map() {
      var constant = ComputedConstant.of(() -> 42);
      var constant2 = constant.map(i -> i * 2);
      assertEquals(84, constant2.get());
      assertAll(
          () -> assertFalse(constant.isUnbound()),
          () -> assertFalse(constant.isBinding()),
          () -> assertTrue(constant.isBound()),
          () -> assertFalse(constant.isError())
      );
    }

    private void testWithALotOfThreads() throws InterruptedException {
      var threadCount = 100;
      var constant = ComputedConstant.of(Thread::currentThread);
      var results = new Thread[threadCount];
      var threads = IntStream.range(0, threadCount)
          .mapToObj(i -> new Thread(() -> {
            results[i] = constant.get();
          }))
          .toList();
      for(var thread : threads) {
        thread.start();
      }
      for(var thread : threads) {
        thread.join();
      }
      var expected = constant.get();
      for(var result: results) {
        assertSame(expected, result);
      }
    }

    @Test
    public void aLotOfThreads() throws InterruptedException {
      for(var i = 0; i < 1_000; i++) {
        testWithALotOfThreads();
      }
    }
  }

  @Nested
  class ComputedConstantList {
    @Test
    public void ofList() {
      var count = 1_000;
      var list = ComputedConstant.ofList(count, i -> i);
      for(var i = 0; i < count; i++) {
        assertEquals(i, list.get(i).get());
      }
    }
  }
}