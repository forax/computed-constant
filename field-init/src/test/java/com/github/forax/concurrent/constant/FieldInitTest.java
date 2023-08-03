package com.github.forax.concurrent.constant;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static java.lang.invoke.MethodHandles.lookup;
import static org.junit.jupiter.api.Assertions.*;

public class FieldInitTest {

  @Nested
  public class StaticField {
    @Test
    public void get() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static String X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return "text";
        }
      }
      assertEquals("text", Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void getByte() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static byte X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return (byte) 42;
        }
      }
      assertEquals((byte) 42, Foo.FIELD_INIT.getByte("X"));
    }

    @Test
    public void getChar() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static char X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return 'A';
        }
      }
      assertEquals('A', Foo.FIELD_INIT.getChar("X"));
    }

    @Test
    public void getShort() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static short X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return (short) 42;
        }
      }
      assertEquals((short) 42, Foo.FIELD_INIT.getShort("X"));
    }

    @Test
    public void getInt() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static int X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return 42;
        }
      }
      assertEquals(42, Foo.FIELD_INIT.getInt("X"));
    }

    @Test
    public void getFloat() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static double X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return 42.f;
        }
      }
      assertEquals(42.f, Foo.FIELD_INIT.getFloat("X"));
    }

    @Test
    public void getLong() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static long X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return 42L;
        }
      }
      assertEquals(42L, Foo.FIELD_INIT.getLong("X"));
    }

    @Test
    public void getDouble() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static double X;

        private static Object $staticFieldInit$(String fieldName) {
          assertEquals("X", fieldName);
          return 42.;
        }
      }
      assertEquals(42., Foo.FIELD_INIT.getDouble("X"));
    }

    @Test
    public void getSeveral() {
      class Bar {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static String A;
        private static int B;

        private static Object $staticFieldInit$(String fieldName) {
          return switch (fieldName) {
            case "A" -> "hello";
            case "B" -> 42;
            default -> throw new AssertionError("unknown field " + fieldName);
          };
        }
      }
      assertAll(
          () -> assertEquals("hello", Bar.FIELD_INIT.<String>get("A")),
          () -> assertEquals(42, Bar.FIELD_INIT.getInt("B"))
      );
    }

    @Test
    public void fieldNotPresent() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());

        private static Object $staticFieldInit$(String fieldName) {
          throw new AssertionError();
        }
      }
      assertThrows(NoSuchFieldError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void fieldDeclaredNotStatic() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        String X;

        private static Object $staticFieldInit$(String fieldName) {
          throw new AssertionError();
        }
      }
      assertThrows(NoSuchFieldError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void fieldDeclaredFinal() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        final String X = "";

        private static Object $staticFieldInit$(String fieldName) {
          throw new AssertionError();
        }
      }
      assertThrows(NoSuchFieldError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void fieldDeclaredNotPrivate() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        static String X;

        private static Object $staticFieldInit$(String fieldName) {
          throw new AssertionError();
        }
      }
      assertThrows(NoSuchFieldError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void fieldDeclaredStaticFinal() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        final String X = "";

        private static Object $staticFieldInit$(String fieldName) {
          throw new AssertionError();
        }
      }
      assertThrows(NoSuchFieldError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void noMethodStaticFieldInit() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static int X;
      }
      assertThrows(NoSuchMethodError.class, () -> Foo.FIELD_INIT.get("X"));
    }

    @Test
    public void methodStaticFieldInitFailsWithAnException() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static int X;

        private static Object $staticFieldInit$(String fieldName) {
          throw new RuntimeException("oops");
        }
      }
      var e = assertThrows(ExceptionInInitializerError.class, () -> Foo.FIELD_INIT.get("X"));
      assertSame(RuntimeException.class, e.getCause().getClass());
      var e2 = assertThrows(ExceptionInInitializerError.class, () -> Foo.FIELD_INIT.get("X"));
      assertSame(e, e2);
    }

    @Test
    public void methodStaticFieldInitFailsWithAnError() {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static int X;

        private static Object $staticFieldInit$(String fieldName) {
          throw new Error("oops");
        }
      }
      var e = assertThrows(Error.class, () -> Foo.FIELD_INIT.get("X"));
      var e2 = assertThrows(Error.class, () -> Foo.FIELD_INIT.get("X"));
      assertSame(e, e2);
    }


    private void testWithALotOfThreads() throws InterruptedException {
      class Foo {
        private static final FieldInit FIELD_INIT = FieldInit.ofStatic(lookup());
        private static Thread constant;

        private static Object $staticFieldInit$(String fieldName) {
          return switch (fieldName) {
            case "constant" -> Thread.currentThread();
            default -> throw new AssertionError("unknown " + fieldName);
          };
        }
      }

      var threadCount = 100;
      var results = new Thread[threadCount];
      var threads = IntStream.range(0, threadCount)
          .mapToObj(i -> new Thread(() -> {
            results[i] = Foo.FIELD_INIT.get("constant");
          }))
          .toList();
      for(var thread : threads) {
        thread.start();
      }
      for(var thread : threads) {
        thread.join();
      }
      var expected = Foo.FIELD_INIT.get("constant");
      for(var result: results) {
        assertSame(expected, result);
      }
    }

    @Test
    public void aLotOfThreads() throws InterruptedException {
      //for(var i = 0; i < 1_000; i++) {
        testWithALotOfThreads();
      //}
    }
  }


}