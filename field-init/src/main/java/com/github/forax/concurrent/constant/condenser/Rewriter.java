package com.github.forax.concurrent.constant.condenser;

import com.github.forax.concurrent.constant.FieldInit;
import com.github.forax.concurrent.constant.FieldInitMetafactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static java.lang.invoke.MethodType.methodType;
import static org.objectweb.asm.Opcodes.ACONST_NULL;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;

public class Rewriter {
  private static final String FIELD_INIT_INTERNAL_NAME = FieldInit.class.getName().replace('.', '/');
  private static final String FIELD_INIT_METAFACTORY_INTERNAL_NAME = FieldInitMetafactory.class.getName().replace('.', '/');
  private static final Set<String> GET_NAMES;

  private static final String OF_STATIC;
  private static final String OF_STATIC_DESCRIPTOR;
  private static final String OF_NOT_IMPLEMENTED;

  private static final Handle BSM;

  static {
    var lookup = MethodHandles.lookup();
    MethodHandleInfo get, getBoolean, getByte, getShort, getChar, getInt, getLong, getFloat, getDouble;
    try {
      get = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "get", methodType(Object.class, String.class)));
      getBoolean = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getBoolean", methodType(double.class, String.class)));
      getByte = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getByte", methodType(byte.class, String.class)));
      getShort = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getShort", methodType(short.class, String.class)));
      getChar = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getChar", methodType(char.class, String.class)));
      getInt = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getInt", methodType(int.class, String.class)));
      getLong = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getLong", methodType(long.class, String.class)));
      getFloat = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getFloat", methodType(float.class, String.class)));
      getDouble = lookup.revealDirect(lookup.findVirtual(FieldInit.class, "getDouble", methodType(double.class, String.class)));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    GET_NAMES = Set.of(
        get.getName() + get.getMethodType().descriptorString(),
        getBoolean.getName() + getBoolean.getMethodType().descriptorString(),
        getByte.getName() + getByte.getMethodType().descriptorString(),
        getShort.getName() + getShort.getMethodType().descriptorString(),
        getChar.getName() + getChar.getMethodType().descriptorString(),
        getInt.getName() + getInt.getMethodType().descriptorString(),
        getLong.getName() + getLong.getMethodType().descriptorString(),
        getFloat.getName() + getFloat.getMethodType().descriptorString(),
        getDouble.getName() + getDouble.getMethodType().descriptorString()
    );

    MethodHandleInfo ofStatic, ofNotImplemented;
    try {
      ofStatic = lookup.revealDirect(lookup.findStatic(FieldInit.class, "ofStatic",
          methodType(FieldInit.class, Lookup.class)));
      ofNotImplemented = lookup.revealDirect(lookup.findStatic(FieldInitMetafactory.class, "ofNotImplemented",
          ofStatic.getMethodType()));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
    OF_STATIC = ofStatic.getName();
    OF_STATIC_DESCRIPTOR = ofStatic.getMethodType().descriptorString();
    OF_NOT_IMPLEMENTED = ofNotImplemented.getName();

    MethodHandleInfo bsm;
    try {
      bsm = lookup.revealDirect(lookup.findStatic(FieldInitMetafactory.class, "staticFieldInit",
          methodType(Object.class, Lookup.class, String.class, Class.class)));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    BSM = new Handle(H_INVOKESTATIC,
        bsm.getDeclaringClass().getName().replace('.', '/'),
        bsm.getName(),
        bsm.getMethodType().descriptorString(),
        false);
  }


  public static Optional<byte[]> transform(byte[] classFile) {
    var reader = new ClassReader(classFile);
    var writer = new ClassWriter(reader, 0);

    if (reader.getClassName().equals(FIELD_INIT_INTERNAL_NAME)) {
      // skip it for now, given that default get* method use a non-constant name
      return Optional.empty();
    }

    var classVisitor = new ClassVisitor(ASM9, writer) {
      private String currentClass;
      private boolean changed;

      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        currentClass = name;
        super.visit(version, access, name, signature, superName, interfaces);
      }

      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        var mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(ASM9, mv) {
          private String constant;

          @Override
          public void visitIntInsn(int opcode, int operand) {
            constant = null;
            super.visitIntInsn(opcode, operand);
          }

          @Override
          public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            constant = null;
            super.visitFieldInsn(opcode, owner, name, descriptor);
          }

          @Override
          public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            constant = null;
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
          }

          @Override
          public void visitIincInsn(int varIndex, int increment) {
            constant = null;
            super.visitIincInsn(varIndex, increment);
          }

          @Override
          public void visitJumpInsn(int opcode, Label label) {
            constant = null;
            super.visitJumpInsn(opcode, label);
          }

          @Override
          public void visitInsn(int opcode) {
            constant = null;
            super.visitInsn(opcode);
          }

          @Override
          public void visitTypeInsn(int opcode, String type) {
            constant = null;
            super.visitTypeInsn(opcode, type);
          }

          @Override
          public void visitVarInsn(int opcode, int varIndex) {
            constant = null;
            super.visitVarInsn(opcode, varIndex);
          }

          @Override
          public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            constant = null;
            super.visitLookupSwitchInsn(dflt, keys, labels);
          }

          @Override
          public void visitTableSwitchInsn(int min, int max, Label _default, Label... labels) {
            constant = null;
            super.visitTableSwitchInsn(min, max, _default, labels);
          }

          @Override
          public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            constant = null;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
          }

          @Override
          public void visitLdcInsn(Object value) {
            if (value instanceof String s) {
              constant = s;
              super.visitLdcInsn(s);
              return;
            }
            constant = null;
            super.visitLdcInsn(value);
          }

          @Override
          public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == INVOKEINTERFACE &&
                owner.equals(FIELD_INIT_INTERNAL_NAME) &&
                GET_NAMES.contains(name + descriptor)) {

              if (constant == null) {
                throw new IllegalStateException("no field name found while parsing " + currentClass + '.' + name + descriptor);
              }

              super.visitInsn(POP);  // pop the String
              super.visitInsn(POP);  // pop the FieldInit
              var fieldDescriptor = MethodTypeDesc.ofDescriptor(descriptor).returnType().descriptorString();
              super.visitLdcInsn(new ConstantDynamic(constant, fieldDescriptor, BSM));
              changed = true;
              return;
            }
            constant = null;
            if (opcode == INVOKESTATIC &&
                owner.equals(FIELD_INIT_INTERNAL_NAME) &&
                name.equals(OF_STATIC) &&
                descriptor.equals(OF_STATIC_DESCRIPTOR)) {
              super.visitMethodInsn(opcode, FIELD_INIT_METAFACTORY_INTERNAL_NAME, OF_NOT_IMPLEMENTED, descriptor, false);
              changed = true;
              return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
          }
        };
      }
    };
    reader.accept(classVisitor, 0);

    if (!classVisitor.changed) {
      return Optional.empty();
    }
    return Optional.of(writer.toByteArray());
  }

  private static void rewriteAll(Path directory) throws IOException {
    try(var stream = Files.walk(directory)) {
      for(var path: (Iterable<Path>) stream::iterator) {
        if (!path.toString().endsWith(".class")) {
          continue;
        }
        var content = Files.readAllBytes(path);
        var newContent = transform(content);
        if (newContent.isPresent()) {
          Files.write(path, newContent.orElseThrow());
          System.out.println(path + " rewritten");
        }
      }
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("""
        Rewriter directory
          rewrite all classes in the directory (recursively) to condense FieldInit usages
        """);
      System.exit(1);
      return;
    }
    var directory = Path.of(args[0]);
    System.out.println("rewrite " + directory);
    rewriteAll(directory);
  }
}
