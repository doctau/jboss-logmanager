package org.jboss.logmanager;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public class IndyLogging
{
    private static final AtomicLong loggerCount = new AtomicLong();
    public static DefiningClassLoader classloader = new DefiningClassLoader();
    
    private final LoggerNode node;
    private Constructor<Logger> ctor;
    private SwitchPoint switchpoint;

    public IndyLogging(LoggerNode node) {
        this.node = node;
        this.switchpoint = new SwitchPoint();
    }



    public synchronized Constructor<Logger> getLoggerConstructor()
    {
        if (ctor != null)
            return ctor;

        final Class<Logger> klass;
        if (USE_INDY_LOGGER) {
            klass = createIndyLoggerClass();
        } else {
            klass = Logger.class;
        }

        try {
            ctor =  klass.getDeclaredConstructor(LoggerNode.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        }
        return ctor;
    }

    
    private Class<Logger> createIndyLoggerClass()
    {
        String loggerCategory = node.getFullName();

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        int version = 52; // Java 8
        String suffix = "_Synthetic" + loggerCount.getAndIncrement() + "_" + loggerCategory.replace('.', '_');
        String parentClass = Type.getInternalName(Logger.class);

        cw.visit(version, Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_SUPER,
                        Type.getInternalName(getClass()) + suffix,
                        null,
                        parentClass,
                        null);
        
        // add constructor
        addForwardingConstructor(cw, parentClass);

        String voidStringDesc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(String.class));
        

        addMethod(cw, loggerCategory, "config", Level.CONFIG.getName(), voidStringDesc);
        //entering ss;
        //entering sso;
        //entering sso[];
        //exiting ss;
        //existing sso;
        addMethod(cw, loggerCategory, "fine", Level.FINE.getName(), voidStringDesc);
        addMethod(cw, loggerCategory, "finer", Level.FINER.getName(), voidStringDesc);
        addMethod(cw, loggerCategory, "finest", Level.FINEST.getName(), voidStringDesc);
        addMethod(cw, loggerCategory, "info", Level.INFO.getName(), voidStringDesc);
        //isloggable
        //log(Level, *)
        addMethod(cw, loggerCategory, "severe", Level.SEVERE.getName(), voidStringDesc);
        //throwing sst
        addMethod(cw, loggerCategory, "warning", Level.WARNING.getName(), voidStringDesc);

        cw.visitEnd();
        return (Class<Logger>) classloader.defineClass(getClass().getName() + suffix, cw.toByteArray());
    }


    private void addForwardingConstructor(ClassWriter cw, String parent)
    {
        String desc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(LoggerNode.class), Type.getType(String.class));
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); // automatically computer
        mv.visitEnd();
    }

    private void addMethod(ClassWriter cw, String loggerCategory, String methodName, String level, String voidStringDesc)
    {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, voidStringDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, Type.getInternalName(getClass()),
                        "indyLoggingBootstrap", BOOTSTRAP_METHOD_DESCRIPTOR);
        mv.visitInvokeDynamicInsn(loggerCategory, voidStringDesc, bsm, level);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0); // automatically computer
        mv.visitEnd();
    }

    private static final String BOOTSTRAP_METHOD_DESCRIPTOR = Type.getMethodDescriptor(
                    Type.getObjectType("java/lang/invoke/CallSite"), // return
                    Type.getObjectType("java/lang/invoke/MethodHandles$Lookup"), // caller
                    Type.getObjectType("java/lang/String"), //invokedName == category name
                    Type.getObjectType("java/lang/invoke/MethodType"), // invokedType
                    Type.getObjectType("java/lang/String") //level
                    );



    public static CallSite indyLoggingBootstrap(final MethodHandles.Lookup caller, final String categoryName,
                    final MethodType invokedType, final String levelName) {
        try {
            // would be better to use non-internals, but it's not exposed
            IndyLogging indy = LogContext.getLogContext().getRootLoggerNode().getOrCreate(categoryName).getIndyLogging();
            Level level = Level.parse(levelName);
            return indy.createCallSite(invokedType, level);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public CallSite createCallSite(final MethodType invokedType, final Level level) {
        MutableCallSite cs = new MutableCallSite(invokedType);
        
        //
        Class<?>[] paramTypes = invokedType.parameterArray();
        Class<?> returnType = invokedType.returnType();
        if (returnType == Boolean.TYPE) {
            if (paramTypes.length == 0) {
                cs.setTarget(FALLBACK_HANDLE.bindTo(this).bindTo(cs).bindTo(level));
                indyFallback(cs, level);
            } else {
                throw new IllegalArgumentException("createCallSite does not know how to handle " + invokedType.toMethodDescriptorString());
            }
        } else {
            throw new IllegalArgumentException("createCallSite does not know how to handle " + invokedType.toMethodDescriptorString());
        }
        return cs;
    }


    boolean indyFallback(final CallSite cs, final Level level) {
        int effectiveLevel = node.getEffectiveLevel();
        boolean enabled = level.intValue() >= effectiveLevel && effectiveLevel != Logger.OFF_INT;

        // By using MethodHandles.constant, the JVM can inline this into the caller
        // It can potentially then remove the if branch, and when false all logging
        MethodHandle fallbackMH = cs.getTarget(); // points to this method
        cs.setTarget(switchpoint.guardWithTest(enabled ? TRUE_HANDLE : FALSE_HANDLE, fallbackMH));
        System.out.println("Setting logger" + node.getFullName() + " for " + level.getName() + " to " + (enabled ? "enabled" : "disabled"));
        return enabled;
    }

    public synchronized void notifyLevelChange()
    {
        // by creating a new switchpoint and invalidating the old one, it will re-run fallback()
        // to recompute the constant
        SwitchPoint oldSwitchPoint = this.switchpoint;
        this.switchpoint = new SwitchPoint();
        SwitchPoint.invalidateAll(new SwitchPoint[] {oldSwitchPoint});
    }

    private static final boolean USE_INDY_LOGGER;
    private static final MethodHandle FALLBACK_HANDLE;
    private static final MethodHandle TRUE_HANDLE;
    private static final MethodHandle FALSE_HANDLE;
    static {
        try {
            boolean enabled = Boolean.getBoolean("org.jboss.logging.USE_INDY_LOGGER");
            if (enabled) {
                // detect asm
                USE_INDY_LOGGER = true;
            } else {
                USE_INDY_LOGGER = false;
            }
            
            if (USE_INDY_LOGGER) {
                FALLBACK_HANDLE = MethodHandles.lookup().findVirtual(IndyLogging.class, "indyFallback",
                        MethodType.methodType(Boolean.TYPE, CallSite.class, Level.class));
    
                TRUE_HANDLE = MethodHandles.constant(Boolean.TYPE, true);
                FALSE_HANDLE = MethodHandles.constant(Boolean.TYPE, false);
            } else {
                FALLBACK_HANDLE = null;
                TRUE_HANDLE = null;
                FALSE_HANDLE = null;
            }
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e.getMessage(), e);
        }
    }
}
