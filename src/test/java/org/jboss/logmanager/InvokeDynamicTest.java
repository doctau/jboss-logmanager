package org.jboss.logmanager;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_7;

import java.lang.invoke.MethodType;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.Assert;
import org.testng.annotations.Test;

public class InvokeDynamicTest {
    static {
        System.setProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager");
    }


    private static long counter = 0;
    public static void message() {
        counter += 1;
        if (counter % (1024 * 1024 * 1024) == 0)
            System.out.println("Counter increment: " + counter);
    }

    @Test
    public void testIndy1() throws Exception {
        Runnable instance = createDynamicRunnable();
        final Logger logger = Logger.getLogger("test.invoke.dynamic");

        logger.setLevel(Level.ALL);
        instance.run();
        Assert.assertEquals(counter, 1);

        logger.setLevel(Level.OFF);
        instance.run();
        Assert.assertEquals(counter, 1);

        logger.setLevel(Level.ALL);
        instance.run();
        Assert.assertEquals(counter, 2);

    }


    // this is to allow performance testing.
    // it invokes the method in a loop after setting the logging level appropriately
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading -XX:+LogCompilation -XX:+PrintAssembly org.jboss.logmanager.InvokeDynamicTest <level>");
            System.exit(1);
        }

        final Logger logger = Logger.getLogger("test.invoke.dynamic");
        logger.setLevel(Level.parse(args[0]));

        performLoop();
    }

    private static void performLoop() throws Exception {
        Runnable instance = createDynamicRunnable();
        while (true) {
            instance.run();
        }
    }



    private static Runnable createDynamicRunnable() throws Exception {
        TestClassLoader loader = new TestClassLoader(InvokeDynamicTest.class.getClassLoader());
        byte[] bytes = createBytes("testIndy1/IndyTest", "test.invoke.dynamic", Level.FINER);
        Class <?> klass = loader.defineClass(bytes);
        return (Runnable) klass.newInstance();
    }

    static class TestClassLoader extends ClassLoader {
        public TestClassLoader(ClassLoader parent) {
            super(parent);
        }
        public Class<?> defineClass(byte[] b) throws ClassFormatError {
            return super.defineClass(b, 0, b.length);
        }
    }


    private static final String VOID_METHOD_TYPE = MethodType.methodType(Void.TYPE).toMethodDescriptorString();
    private static final String BOOLEAN_METHOD_TYPE = MethodType.methodType(Boolean.TYPE).toMethodDescriptorString();

    private static byte[] createBytes(String name, String category, Level finer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_7, ACC_PUBLIC + ACC_SUPER, name, null, "java/lang/Object", new String[]{ "java/lang/Runnable"});

        addConstructor(cw);
        addTestMethod(cw, category, finer);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addConstructor(ClassWriter cw) {
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", VOID_METHOD_TYPE, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", VOID_METHOD_TYPE, false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(100, 100);
        ctor.visitEnd();
    }

    private static void addTestMethod(ClassWriter cw, String category, Level level) {
        MethodVisitor meth = cw.visitMethod(ACC_PUBLIC, "run", VOID_METHOD_TYPE, null, null);
        meth.visitCode();
        Handle bootstrapMethod = new Handle(H_INVOKESTATIC, "org/jboss/logmanager/LogManager", "bootstrapLoggingEnabled",
                LogManager.BOOTSTRAP_METHOD_TYPE.toMethodDescriptorString());
        Label label = new Label();

        meth.visitInvokeDynamicInsn("unused", BOOLEAN_METHOD_TYPE, bootstrapMethod, category, level.getName());
        meth.visitJumpInsn(Opcodes.IFEQ, label);
        meth.visitMethodInsn(INVOKESTATIC, InvokeDynamicTest.class.getName().replace('.', '/'), "message", VOID_METHOD_TYPE, false);
        meth.visitLabel(label);
        meth.visitInsn(RETURN);
        meth.visitMaxs(100, 100);
        meth.visitEnd();
    }
}
