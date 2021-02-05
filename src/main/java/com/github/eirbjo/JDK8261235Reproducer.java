package com.github.eirbjo;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.Type.getInternalName;

public class JDK8261235Reproducer {


    /**
     * Instrument jaxen's Verifier class, load it and execute the isXMLLetter
     * method until C1 crashes during compilation
     */
    public static void main(String[] args) throws Throwable {

        final String className = "org.jaxen.saxpath.base.Verifier";

        byte[] instrumentedBytes = getInstrumentedBytes(className);

        // Write file to disk for later inspection
        Files.write(Path.of("Verifier.class"), instrumentedBytes);


        // Load class, execute method
        final ClassLoader classLoader = new ByteArrayClassLoader(className, instrumentedBytes);

        final Class<?> klass = classLoader.loadClass(className);

        final Method method = klass.getDeclaredMethod("isXMLLetter", char.class);
        method.setAccessible(true);

        final MethodHandle methodHandle = MethodHandles.lookup().unreflect(method);

        for (int i = 0; i < 100_000_00; i++) {
            boolean isXml = (boolean) methodHandle.invokeExact((char)i);
        }
    }

    // This is called from instrumented code for each line at the end of the method
    public static void countVisits(int num) throws Throwable {

    }

    private static byte[] getInstrumentedBytes(String className) throws IOException {
        String resourceName = className.replace('.', '/') + ".class";

        try (InputStream is = JDK8261235Reproducer.class.getClassLoader().getResource(resourceName).openStream()) {
            ClassReader cr = new ClassReader(is);

            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

            ClassVisitor instrumenter = new InstrumentingVisitor(cw);


            cr.accept(instrumenter, ClassReader.EXPAND_FRAMES);

            return cw.toByteArray();
        }
    }


    private static class InstrumentingVisitor extends ClassVisitor {


        public InstrumentingVisitor(ClassVisitor visitor) {
            super(Opcodes.ASM9, visitor);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            return new FirstPassVisitor(super.visitMethod(access, name, descriptor, signature, exceptions), access, name, descriptor, signature, exceptions);
        }
    }

    /**
     * The first pass collects line numbers needed for the actual instrumentation
     * in {@link SecondPassVisitor}
     */
    private static class FirstPassVisitor extends MethodNode {

        private final MethodVisitor visitor;

        private final Map<Integer, Integer> lines = new HashMap<>();

        public FirstPassVisitor(MethodVisitor visitor, int access, String name, String descriptor, String signature, String[] exceptions) {
            super(Opcodes.ASM9, access, name, descriptor, signature, exceptions);
            this.visitor = visitor;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            lines.computeIfAbsent(line, l -> lines.size());
        }

        @Override
        public void visitEnd() {
            accept(new SecondPassVisitor(visitor, access, name, desc, lines));
        }

    }

    /**
     * At methods entry, initialize local variables for each line in the method
     * At each line number, increment the counter by 1
     * Replace each *RETURN with a GOTO to the end of the method
     * At the GOTO target, report each counter using the countVisits method,
     * then *RETURN from the method
     */
    private static class SecondPassVisitor extends AdviceAdapter {
        private final Map<Integer, Integer> lines;
        private Map<Integer, Integer> locals = new HashMap<>();
        private final Label commitLabel;

        public SecondPassVisitor(MethodVisitor methodVisitor, int access, String name, String descriptor, Map<Integer, Integer> lines) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.lines = lines;
            commitLabel = new Label();
        }

        @Override
        protected void onMethodEnter() {
            super.onMethodEnter();

            // Initalize the locals used for counting
            for (Integer line : lines.keySet()) {
                mv.visitInsn(ICONST_0);
                final int local = newLocal(Type.INT_TYPE);
                locals.put(line, local);
                mv.visitVarInsn(ISTORE, local);
            }
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            if (!locals.isEmpty()) {
                // Count that this line was executed
                mv.visitIincInsn(locals.get(line), 1);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if(opcode >= IRETURN && opcode <= RETURN) {
                // Jump to common target for line count reporting
                super.visitJumpInsn(GOTO, commitLabel);
            } else {
                super.visitInsn(opcode);
            }
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            final Type returnType = Type.getReturnType(methodDesc);

            mv.visitLabel(commitLabel);

            // Do the counting
            countLines();

            // Return from the method
            mv.visitInsn(getReturnInstr(returnType));

            super.visitMaxs(maxStack, maxLocals);
        }

        private int getReturnInstr(Type returnType) {
            switch (returnType.getSort()) {
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    return IRETURN;
                case Type.LONG:
                    return LRETURN;
                case Type.DOUBLE:
                    return DRETURN;
                case Type.FLOAT:
                    return FRETURN;
                case Type.VOID:
                    return RETURN;
                default:
                    return ARETURN;
            }
        }

        private void countLines() {

            for (Integer line : lines.keySet()) {
                mv.visitVarInsn(ILOAD, locals.get(line));
                mv.visitMethodInsn(INVOKESTATIC, getInternalName(JDK8261235Reproducer.class), "countVisits", MethodType.methodType(void.class, int.class).toMethodDescriptorString(), false);
            }

        }
    }




    private static class ByteArrayClassLoader extends ClassLoader {
        private final String className;
        private final byte[] bytes;

        public ByteArrayClassLoader(String className, byte[] bytes) {
            super(ByteArrayClassLoader.class.getClassLoader());
            this.className = className;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (className.equals(name)) {
                return defineClass(name, bytes, 0, bytes.length);
            } else {
                return getParent().loadClass(name);
            }
        }
    }
}
