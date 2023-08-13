package com.github.forax.concurrent.constant.condenser;

import com.github.forax.concurrent.constant.ComputedConstant;
import com.github.forax.concurrent.constant.ComputedConstantMetafactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.invoke.MethodType.methodType;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_INTERFACE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTSTATIC;

/**
 * Optimize the ComputedConstant declared in static fields by removing the creation of the computed constant
 * instances from {@code <clinit>}. To keep things simple, this rewriter do only one pass on the code
 * so using this rewriter should be pretty fast.
 * <p>
 * The peak performance should be the same, the rewriter only make the code more lazy.
 * If all computed constants are used, the total initialization cost may be bigger after rewriting.
 * <p>
 * This rewriter detects two bytecode snippets
 * <ul>
 *  <li>storing a computed constant into a static field inside a {@code <clinit>}
 *   <pre>
 *    invokedynamic #39,  0             // InvokeDynamic #0:get:()Ljava/util/function/Supplier;
 *    invokestatic  #42                 // InterfaceMethod com/github/forax/concurrent/constant/ComputedConstant.of:(Ljava/util/function/Supplier;)Lcom/github/forax/concurrent/constant/ComputedConstant;
 *    putstatic     #7                  // Field TEXT:Lcom/github/forax/concurrent/constant/ComputedConstant;
 *   </pre>
 *
 *   In this case, the snippet is removed and an entry inside a new generated method $staticInit$ that calls
 *   the supplier implementation is created.
 *
 *  <li>loading a computed constant from a static field + invoking the method get/orElse/orElseThrow on
 *      the computed constant
 *   <pre>
 *    getstatic     #7                  // Field TEXT:Lcom/github/forax/concurrent/constant/ComputedConstant;
 *    invokeinterface #13,  1           // InterfaceMethod com/github/forax/concurrent/constant/ComputedConstant.get:()Ljava/lang/Object;
 *   </pre>
 *
 *   In this case, the snippet is transformed to an invokedynamic with a constant dynamic as parameter that initialize
 *   the value. If getstatic is used but it is not followed by a call to get/orElse/orElseThrow, then
 *   a shim computed constant is materialized.
 * </ul>
 *
 * There are several conditions where the rewriter will give and not rewrite the class
 * <ul>
 *   <li>a static field containing a compute constant is not declared final
 *   <li>a static field containing a compute constant is initialized outside of {@code <clinit>}
 *   <li>a static field containing a compute constant is not initialized by a call ComputedConstant.of()
 *       with a non constant Supplier (the lambda captures values)
 *   <li>a static field containing a compute constant is not initialized by the result of ComputedConstant.of()
 * </ul>
 * Those conditions ensure that all static final fields containing a compute constant are correctly removed
 * from {@code <clinit>}.
 */
public class ComputedConstantRewriter {

  public static final String LAMBDA_META_FACTORY = LambdaMetafactory.class.getName().replace('.', '/');

  sealed interface Constant {
    Consumer<MethodVisitor> materialize();

    record StaticField(String owner, String name, Consumer<MethodVisitor> materialize) implements Constant {}
    record PresetSupplier(Handle lambdaImplementation, Consumer<MethodVisitor> materialize) implements Constant {
      PresetSupplier andThen(Consumer<MethodVisitor> materialize) {
        return new PresetSupplier(lambdaImplementation, this.materialize.andThen(materialize));
      }
    }
  }

  private static final String COMPUTED_CONSTANT_DESCRIPTOR = ComputedConstant.class.descriptorString();
  private static final String COMPUTED_CONSTANT_INTERNAL_NAME = ComputedConstant.class.getName().replace('.', '/');
  private static final String SUPPLIER_INTERNAL_NAME = Supplier.class.getName().replace('.', '/');

  private static final MethodHandleInfo OF, GET, OR_ELSE, OR_ELSE_THROW;
  private static final MethodHandleInfo OF_SHIM, METHOD_HANDLES_LOOKUP;

  private static final Handle CONSTANT_METHOD_BSM;
  private static final Handle CONSTANT_STATE_BSM;

  static {
    var lookup = MethodHandles.lookup();

    try {
      OF = lookup.revealDirect(lookup.findStatic(ComputedConstant.class, "of",
          methodType(ComputedConstant.class, Supplier.class)));
      METHOD_HANDLES_LOOKUP = lookup.revealDirect(lookup.findStatic(MethodHandles.class, "lookup",
          methodType(Lookup.class)));
      OF_SHIM  = lookup.revealDirect(lookup.findStatic(ComputedConstantMetafactory.class, "ofShim",
          methodType(ComputedConstant.class, Lookup.class, Class.class, String.class)));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    try {
      GET = lookup.revealDirect(lookup.findVirtual(ComputedConstant.class, "get",
          methodType(Object.class)));
      OR_ELSE = lookup.revealDirect(lookup.findVirtual(ComputedConstant.class, "orElse",
          methodType(Object.class, Object.class)));
      OR_ELSE_THROW  = lookup.revealDirect(lookup.findVirtual(ComputedConstant.class, "orElseThrow",
          methodType(Object.class, Supplier.class)));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    MethodHandleInfo constantMethodBSM, constantStateBSM;
    try {
      constantMethodBSM = lookup.revealDirect(lookup.findStatic(ComputedConstantMetafactory.class, "constantMethod",
          methodType(CallSite.class, Lookup.class, String.class, MethodType.class, Object.class)));
      constantStateBSM = lookup.revealDirect(lookup.findStatic(ComputedConstantMetafactory.class, "constantState",
          methodType(Object.class, Lookup.class, String.class, Class.class, MethodHandle.class)));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }

    CONSTANT_METHOD_BSM = new Handle(H_INVOKESTATIC,
        constantMethodBSM.getDeclaringClass().getName().replace('.', '/'),
        constantMethodBSM.getName(),
        constantMethodBSM.getMethodType().descriptorString(),
        false);

    CONSTANT_STATE_BSM = new Handle(H_INVOKESTATIC,
        constantStateBSM.getDeclaringClass().getName().replace('.', '/'),
        constantStateBSM.getName(),
        constantStateBSM.getMethodType().descriptorString(),
        false);
  }


  // helpers

  private static int opcode(MethodHandleInfo methodHandleInfo) {
    return switch (methodHandleInfo.getReferenceKind()) {
      case H_INVOKESTATIC -> INVOKESTATIC;
      default -> throw new AssertionError(methodHandleInfo);
    };
  }

  private static void genMethod(MethodVisitor mv, MethodHandleInfo methodHandleInfo) {
    mv.visitMethodInsn(opcode(methodHandleInfo),
        methodHandleInfo.getDeclaringClass().getName().replace('.', '/'),
        methodHandleInfo.getName(),
        methodHandleInfo.getMethodType().descriptorString(),
        methodHandleInfo.getDeclaringClass().isInterface());
  }

  private static final class AnalysisException extends RuntimeException {
    private AnalysisException(String message) {
      super(message);
    }
  }

  private static final class ComputedConstantClassRewriter extends ClassVisitor {
    private String currentClass;
    private boolean currentClassIsInterface;
    private boolean changed;

    private final HashMap<String, Constant.PresetSupplier> presetSupplierMap = new HashMap<>();

    private ComputedConstantClassRewriter(ClassVisitor cv) {
      super(ASM9, cv);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      currentClass = name;
      currentClassIsInterface = (access & ACC_INTERFACE ) != 0;
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
      if ((access & ACC_STATIC) != 0 && descriptor.equals(COMPUTED_CONSTANT_DESCRIPTOR)) {
        if ((access & ACC_FINAL) == 0) {
          throw new AnalysisException("static field " + name + " is not declared final");
        }
        // remove static final field !
        changed = true;
        return null;
      }
      return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
      var mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      //System.out.println("-> " + currentClass + " method " + name + descriptor);
      var inStaticBlock = name.equals("<clinit>");
      return new MethodVisitor(ASM9, mv) {
        private Constant constant;

        private void materializeIfNecessary() {
          if (constant != null) {
            //System.err.println("materialize " + constant + " in method " + currentClass + "." + name + descriptor);
            constant.materialize().accept(super.mv);
            constant = null;
          }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
          materializeIfNecessary();
          super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
          if (opcode == GETSTATIC && descriptor.equals(COMPUTED_CONSTANT_DESCRIPTOR)) {
            constant = new Constant.StaticField(owner, name, mv -> {
              genMethod(mv, METHOD_HANDLES_LOOKUP);
              mv.visitLdcInsn(Type.getObjectType(owner));
              mv.visitLdcInsn(name);
              genMethod(mv, OF_SHIM);
              changed = true;
            });
            return;
          }
          if (opcode == PUTSTATIC &&
              owner.equals(currentClass) &&
              descriptor.equals(ComputedConstant.class.descriptorString())) {
            if (constant instanceof Constant.PresetSupplier presetSupplier) {
              if (!inStaticBlock) {
                throw new AnalysisException("static field " + name + " initialized outside of <clinit>");
              }
              var implementation = presetSupplier.lambdaImplementation;
              if (!implementation.getDesc().startsWith("()")) {
                throw new AnalysisException("the supplier sent as parameter of ComputeConstant.of() captures values");
              }
              if (implementation.getTag() != H_INVOKESTATIC) {
                throw new AssertionError("unexpected kind for the de-sugared lambda method body");
              }
              presetSupplierMap.put(name, presetSupplier);
              constant = null;
              changed = true;
              return;
            } else {
              throw new AnalysisException("no constant supplier available for static field " + name);
            }
          }
          materializeIfNecessary();
          super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
          if (bootstrapMethodHandle.getOwner().equals(LAMBDA_META_FACTORY) &&
              bootstrapMethodHandle.getName().equals("metafactory")) {
            var returnType = Type.getReturnType(descriptor).getInternalName();
            if (returnType.equals(SUPPLIER_INTERNAL_NAME)) {
              var implementation = (Handle) bootstrapMethodArguments[1];

              constant = new Constant.PresetSupplier(implementation, mv -> mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments));
              return;
            }
          }
          materializeIfNecessary();
          super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
          materializeIfNecessary();
          super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
          materializeIfNecessary();
          super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitInsn(int opcode) {
          materializeIfNecessary();
          super.visitInsn(opcode);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
          materializeIfNecessary();
          super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
          materializeIfNecessary();
          super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
          materializeIfNecessary();
          super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label _default, Label... labels) {
          materializeIfNecessary();
          super.visitTableSwitchInsn(min, max, _default, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
          materializeIfNecessary();
          super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitLdcInsn(Object value) {
          materializeIfNecessary();
          super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
          if (opcode == INVOKEINTERFACE &&
              owner.equals(COMPUTED_CONSTANT_INTERNAL_NAME)) {
            if (name.equals(GET.getName()) || name.equals(OR_ELSE.getName()) || name.equals(OR_ELSE_THROW.getName())) {
              if (constant instanceof Constant.StaticField constantStaticField && constantStaticField.owner.equals(currentClass)) {
                var staticInit = new Handle(H_INVOKESTATIC, currentClass, "$staticInit$", "(Ljava/lang/String;)Ljava/lang/Object;", currentClassIsInterface);
                var condy = new ConstantDynamic(constantStaticField.name, "Ljava/lang/Object;", CONSTANT_STATE_BSM, staticInit);

                super.visitInvokeDynamicInsn(name, descriptor, CONSTANT_METHOD_BSM, condy);
                changed = true;
                constant = null;
                return;
              }
            }
          }
          if (opcode == INVOKESTATIC &&
              name.equals(OF.getName()) &&
              owner.equals(COMPUTED_CONSTANT_INTERNAL_NAME)) {
            if (constant instanceof Constant.PresetSupplier presetSupplier) {
              constant = presetSupplier.andThen(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface));
              return;
            }
          }
          materializeIfNecessary();
          super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitEnd() {
          if (!presetSupplierMap.isEmpty()) {
            var mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, "$staticInit$", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);

            var groupByHashMap = presetSupplierMap.entrySet().stream()
                .collect(Collectors.groupingBy(entry -> entry.getKey().hashCode(), LinkedHashMap::new, Collectors.toList()));
            var keys = groupByHashMap.keySet().stream()
                .mapToInt(k -> k)
                .toArray();
            var caseLabels = new Label[keys.length];
            Arrays.setAll(caseLabels, __ -> new Label());
            var defaultLabel = new Label();
            mv.visitLookupSwitchInsn(defaultLabel, keys, caseLabels);

            for(var i = 0; i < keys.length; i++) {
              mv.visitLabel(caseLabels[i]);
              var nextLabel = new Label();
              Label previousLabel = null;
              for (var entry : groupByHashMap.get(keys[i])) {
                if (previousLabel != null) {
                  mv.visitLabel(previousLabel);
                }
                mv.visitVarInsn(ALOAD, 0);
                mv.visitLdcInsn(entry.getKey());
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, nextLabel);

                var implementationHandle = entry.getValue().lambdaImplementation;
                mv.visitMethodInsn(INVOKESTATIC, implementationHandle.getOwner(), implementationHandle.getName(), implementationHandle.getDesc(), currentClassIsInterface);
                mv.visitInsn(ARETURN);

                previousLabel = nextLabel;
                nextLabel = new Label();
              }
              mv.visitLabel(previousLabel);
              mv.visitJumpInsn(GOTO, defaultLabel);  // maybe avoid the last GOTO ?
            }

            mv.visitLabel(defaultLabel);
            mv.visitTypeInsn(NEW, "java/lang/AssertionError");
            mv.visitInsn(DUP);

            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AssertionError", "<init>", "(Ljava/lang/Object;)V", false);
            mv.visitInsn(ATHROW);

            mv.visitMaxs(2, 1);
            mv.visitEnd();
            changed = true;
          }
          super.visitEnd();
        }
      };
    }
  }

  public static Optional<byte[]> transform(byte[] classFile) {
    var reader = new ClassReader(classFile);
    var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

    var classRewriter = new ComputedConstantClassRewriter(writer);
    try {
      reader.accept(classRewriter, 0);
    } catch (AnalysisException e) {
      System.err.println("analysis error: " + e.getMessage());
      return Optional.empty();
    }

    if (!classRewriter.changed) {
      return Optional.empty();
    }
    return Optional.of(writer.toByteArray());
  }

  private static void rewriteAll(Path directory) throws IOException {
    try(var stream = Files.walk(directory)) {
      for(var path: (Iterable<Path>) stream::iterator) {
        if (path.toString().contains("condenser")) {
          continue;
        }
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
