/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.nativebridge;

import org.graalvm.jniutils.JNICalls;
import org.graalvm.jniutils.JNI.JByteArray;
import org.graalvm.jniutils.JNI.JClass;
import org.graalvm.jniutils.JNI.JMethodID;
import org.graalvm.jniutils.JNI.JNIEnv;
import org.graalvm.jniutils.JNI.JObject;
import org.graalvm.jniutils.JNI.JThrowable;
import org.graalvm.jniutils.JNI.JValue;
import org.graalvm.jniutils.JNIExceptionWrapper;
import org.graalvm.jniutils.JNIExceptionWrapper.ExceptionHandler;
import org.graalvm.jniutils.JNIExceptionWrapper.ExceptionHandlerContext;
import org.graalvm.jniutils.JNIMethodScope;
import org.graalvm.jniutils.JNIUtil;
import org.graalvm.nativebridge.BinaryOutput.ByteArrayBinaryOutput;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

import static org.graalvm.jniutils.JNIUtil.GetStaticMethodID;
import static org.graalvm.nativeimage.c.type.CTypeConversion.toCString;

/**
 * This exception is used to transfer a local exception over the boundary. The local exception is
 * marshaled, passed over the boundary as a {@link ForeignException}, unmarshalled, and re-thrown.
 */
@SuppressWarnings("serial")
public final class ForeignException extends RuntimeException {

    static final byte UNDEFINED = 0;
    static final byte HOST_TO_GUEST = 1;
    static final byte GUEST_TO_HOST = 2;
    private static final ThreadLocal<ForeignException> pendingException = new ThreadLocal<>();
    private static final JNIMethodResolver CreateForeignException = new JNIMethodResolver("createForeignException", "([B)Ljava/lang/Throwable;");
    private static final JNIMethodResolver ToByteArray = new JNIMethodResolver("toByteArray", "(Lorg/graalvm/nativebridge/ForeignException;)[B");
    private static final JNIMethodResolver GetStackOverflowErrorClass = new JNIMethodResolver("getStackOverflowErrorClass", "()Ljava/lang/Class;");

    /**
     * Pre-allocated {@code ForeignException} for double failure.
     */
    private static final ForeignException MARSHALLING_FAILED = new ForeignException(null, UNDEFINED, false);
    private static volatile JNICalls jniCalls;

    private final byte kind;
    private final byte[] rawData;

    private ForeignException(byte[] rawData, byte kind, boolean writableStackTrace) {
        super(null, null, true, writableStackTrace);
        this.rawData = rawData;
        this.kind = kind;
    }

    /**
     * Re-throws this exception in host using a JNI API. This method is intended to be called by the
     * code generated by the bridge processor. It's still possible to use it by the hand-written
     * code, but it's recommended to use the bridge processor.
     */
    public void throwUsingJNI(JNIEnv env) {
        JNIUtil.Throw(env, callCreateForeignException(env, JNIUtil.createHSArray(env, rawData)));
    }

    /**
     * Unmarshalls the foreign exception transferred by this {@link ForeignException} and re-throws
     * it. This method is intended to be called by the code generated by the bridge processor. It's
     * still possible to use it by the hand-written code, but it's recommended to use the bridge
     * processor.
     *
     * @param isolate the receiver isolate
     * @param marshaller the marshaller to unmarshal the exception
     */
    public RuntimeException throwOriginalException(Isolate<?> isolate, BinaryMarshaller<? extends Throwable> marshaller) {
        try {
            if (rawData == null) {
                throw new RuntimeException("Failed to marshall foreign throwable.");
            }
            BinaryInput in = BinaryInput.create(rawData);
            Throwable t = marshaller.read(isolate, in);
            throw ForeignException.silenceException(RuntimeException.class, t);
        } finally {
            clearPendingException();
        }
    }

    /**
     * Merges foreign stack trace marshalled in the {@link ForeignException} with local
     * {@link ForeignException}'s stack trace. This is a helper method for throwable marshallers to
     * merge local and foreign stack traces. Typical usage looks like this:
     *
     * <pre>
     * final class DefaultThrowableMarshaller implements BinaryMarshaller&lt;Throwable&gt; {
     *     private final BinaryMarshaller&lt;StackTraceElement[]&gt; stackTraceMarshaller = MyJNIConfig.getDefault().lookupMarshaller(StackTraceElement[].class);
     *
     *     &#64;Override
     *     public Throwable read(BinaryInput in) {
     *         String foreignExceptionClassName = in.readUTF();
     *         String foreignExceptionMessage = in.readUTF();
     *         StackTraceElement[] foreignExceptionStack = stackTraceMarshaller.read(in);
     *         RuntimeException exception = new RuntimeException(foreignExceptionClassName + ": " + foreignExceptionMessage);
     *         exception.setStackTrace(ForeignException.mergeStackTrace(foreignExceptionStack));
     *         return exception;
     *     }
     *
     *     &#64;Override
     *     public void write(BinaryOutput out, Throwable object) {
     *         out.writeUTF(object.getClass().getName());
     *         out.writeUTF(object.getMessage());
     *         stackTraceMarshaller.write(out, object.getStackTrace());
     *     }
     * }
     * </pre>
     *
     * @param foreignExceptionStack the stack trace marshalled into the {@link ForeignException}
     * @return the stack trace combining both local and foreign stack trace elements
     */
    public static StackTraceElement[] mergeStackTrace(Isolate<?> isolate, StackTraceElement[] foreignExceptionStack) {
        if (foreignExceptionStack.length == 0) {
            // Exception has no stack trace, nothing to merge.
            return foreignExceptionStack;
        }
        ForeignException localException = pendingException.get();
        if (localException != null) {
            StackMerger merger;
            if (isolate instanceof ProcessIsolate) {
                merger = ProcessIsolateStack::mergeStackTraces;
            } else if (isolate instanceof NativeIsolate) {
                merger = JNIExceptionWrapper::mergeStackTraces;
            } else if (isolate instanceof HSIsolate) {
                merger = JNIExceptionWrapper::mergeStackTraces;
            } else {
                throw new IllegalArgumentException("Unsupported isolate type: " + isolate);
            }
            return switch (localException.kind) {
                case HOST_TO_GUEST -> merger.merge(localException.getStackTrace(), foreignExceptionStack, false);
                case GUEST_TO_HOST -> merger.merge(foreignExceptionStack, localException.getStackTrace(), true);
                default -> throw new IllegalStateException("Unsupported kind " + localException.kind);
            };
        } else {
            return foreignExceptionStack;
        }
    }

    /**
     * Creates a {@link ForeignException} by marshaling the {@code exception} using
     * {@code marshaller}. This method is intended to be called by the code generated by the bridge
     * processor. It's still possible to use it by the hand-written code, but it's recommended to
     * use the bridge processor.
     *
     * @param exception the exception that should be passed over the boundary
     * @param marshaller the marshaller to marshall the exception
     */
    public static ForeignException forThrowable(Throwable exception, BinaryMarshaller<? super Throwable> marshaller) {
        try {
            ByteArrayBinaryOutput out = ByteArrayBinaryOutput.create(marshaller.inferSize(exception));
            marshaller.write(out, exception);
            return new ForeignException(out.getArray(), UNDEFINED, false);
        } catch (ThreadDeath td) {
            throw td;
        } catch (Throwable t) {
            // Exception marshalling failed, prevent exception propagation from CEntryPoint that
            // may cause process crash.
            return MARSHALLING_FAILED;
        }
    }

    /**
     * Returns the {@link JNICalls} transferring the {@link ForeignException} thrown in the HotSpot
     * to an isolate. This method is intended to be called by the code generated by the bridge
     * processor. It's still possible to use it by the hand-written code, but it's recommended to
     * use the bridge processor.
     */
    public static JNICalls getJNICalls() {
        JNICalls res = jniCalls;
        if (res == null) {
            synchronized (ForeignException.class) {
                res = jniCalls;
                if (res == null) {
                    JNIMethodScope scope = JNIMethodScope.scopeOrNull();
                    JNIEnv env = scope != null ? scope.getEnv() : WordFactory.nullPointer();
                    res = createJNICalls(env);
                    if (scope != null) {
                        jniCalls = res;
                    }
                }
            }
        }
        return res;
    }

    /**
     * Opens a {@link JNIMethodScope} for a call from HotSpot to a native method. On the first
     * invocation, it loads any host exceptions that may be thrown by JNI APIs during native-to-Java
     * transitions. This ensures proper exception handling when a JNI API exception occurs within
     * the thread's yellow zone, where it cannot be inspected via a host call.
     *
     * @since 24.2
     */
    public static JNIMethodScope openJNIMethodScope(String scopeName, JNIEnv env) {
        if (jniCalls == null) {
            synchronized (ForeignException.class) {
                if (jniCalls == null) {
                    jniCalls = createJNICalls(env);
                }
            }
        }
        return new JNIMethodScope(scopeName, env);
    }

    byte[] toByteArray() {
        return rawData;
    }

    static ForeignException create(byte[] rawData, byte kind) {
        ForeignException exception = new ForeignException(rawData, kind, true);
        pendingException.set(exception);
        return exception;
    }

    private static void clearPendingException() {
        pendingException.set(null);
    }

    @SuppressWarnings({"unchecked", "unused"})
    private static <T extends Throwable> T silenceException(Class<T> type, Throwable t) throws T {
        throw (T) t;
    }

    private static JNICalls createJNICalls(JNIEnv env) {
        JClass stackOverflowError = env.isNonNull() ? JNIUtil.NewGlobalRef(env, callGetStackOverflowErrorClass(env), StackOverflowError.class.getName()) : WordFactory.nullPointer();
        return JNICalls.createWithExceptionHandler(new ForeignExceptionHandler(stackOverflowError));
    }

    private static JThrowable callCreateForeignException(JNIEnv env, JByteArray rawValue) {
        JValue args = StackValue.get(1, JValue.class);
        args.addressOf(0).setJObject(rawValue);
        return JNICalls.getDefault().callStaticJObject(env, CreateForeignException.getEntryPoints(env), CreateForeignException.resolve(env), args);
    }

    private static JByteArray callToByteArray(JNIEnv env, JObject p0) {
        JValue args = StackValue.get(1, JValue.class);
        args.addressOf(0).setJObject(p0);
        return JNICalls.getDefault().callStaticJObject(env, ToByteArray.getEntryPoints(env), ToByteArray.resolve(env), args);
    }

    private static JClass callGetStackOverflowErrorClass(JNIEnv env) {
        return JNICalls.getDefault().callStaticJObject(env, GetStackOverflowErrorClass.getEntryPoints(env), GetStackOverflowErrorClass.resolve(env), WordFactory.nullPointer());
    }

    private static final class JNIMethodResolver implements JNICalls.JNIMethod {

        private final String methodName;
        private final String methodSignature;
        private volatile JClass entryPointsClass;
        private volatile JMethodID methodId;

        JNIMethodResolver(String methodName, String methodSignature) {
            this.methodName = methodName;
            this.methodSignature = methodSignature;
        }

        JNIMethodResolver resolve(JNIEnv jniEnv) {
            JMethodID res = methodId;
            if (res.isNull()) {
                JClass entryPointClass = getEntryPoints(jniEnv);
                try (CTypeConversion.CCharPointerHolder name = toCString(methodName); CTypeConversion.CCharPointerHolder sig = toCString(methodSignature)) {
                    res = GetStaticMethodID(jniEnv, entryPointClass, name.get(), sig.get());
                    if (res.isNull()) {
                        throw new InternalError("No such method: " + methodName);
                    }
                    methodId = res;
                }
            }
            return this;
        }

        JClass getEntryPoints(JNIEnv env) {
            JClass res = entryPointsClass;
            if (res.isNull()) {
                res = JNIClassCache.lookupClass(env, ForeignExceptionEndPoints.class);
                entryPointsClass = res;
            }
            return res;
        }

        @Override
        public JMethodID getJMethodID() {
            return methodId;
        }

        @Override
        public String getDisplayName() {
            return methodName;
        }
    }

    private static final class ForeignExceptionHandler implements ExceptionHandler {

        private static final StackOverflowError JNI_STACK_OVERFLOW_ERROR = new StackOverflowError("Stack overflow in transition from Native to Java.") {
            @Override
            @SuppressWarnings("sync-override")
            public Throwable fillInStackTrace() {
                return this;
            }
        };

        private final JClass stackOverflowError;

        ForeignExceptionHandler(JClass stackOverflowError) {
            this.stackOverflowError = stackOverflowError;
        }

        @Override
        public void handleException(ExceptionHandlerContext context) {
            JNIEnv env = context.getEnv();
            JThrowable exception = context.getThrowable();
            if (stackOverflowError.isNonNull() && JNIUtil.IsSameObject(env, stackOverflowError, JNIUtil.GetObjectClass(env, exception))) {
                /*
                 * The JavaVM lacks sufficient stack space to transition to `_thread_in_Java`.
                 * Calling a JNI API results in a `StackOverflowError` being set as a pending
                 * exception. We throw a pre-allocated `StackOverflowError`. Unlike a
                 * `StackOverflowError` occurring in user code, the one thrown by a VM transition is
                 * not wrapped in a `ForeignException`. This distinction can be used to identify the
                 * situation.
                 */
                throw JNI_STACK_OVERFLOW_ERROR;
            } else if (ForeignException.class.getName().equals(context.getThrowableClassName())) {
                byte[] marshalledData = JNIUtil.createArray(env, callToByteArray(env, exception));
                throw ForeignException.create(marshalledData, ForeignException.GUEST_TO_HOST);
            } else {
                context.throwJNIExceptionWrapper();
            }
        }
    }

    @FunctionalInterface
    private interface StackMerger {
        StackTraceElement[] merge(StackTraceElement[] hostStack, StackTraceElement[] isolateStack, boolean originatedInHost);
    }
}
