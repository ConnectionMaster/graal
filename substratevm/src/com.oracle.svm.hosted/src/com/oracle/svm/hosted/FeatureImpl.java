/*
 * Copyright (c) 2013, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.Feature.DuringAnalysisAccess;
import org.graalvm.nativeimage.hosted.FieldValueTransformer;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.impl.ConfigurationCondition;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.ObjectScanner;
import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.heap.ImageHeapConstant;
import com.oracle.graal.pointsto.heap.ImageHeapScanner;
import com.oracle.graal.pointsto.infrastructure.SubstitutionProcessor;
import com.oracle.graal.pointsto.meta.AnalysisElement;
import com.oracle.graal.pointsto.meta.AnalysisElement.ElementNotification;
import com.oracle.graal.pointsto.meta.AnalysisElement.MethodOverrideReachableNotification;
import com.oracle.graal.pointsto.meta.AnalysisElement.SubtypeReachableNotification;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMetaAccess;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.graal.pointsto.meta.ObjectReachableCallback;
import com.oracle.svm.common.meta.MultiMethod;
import com.oracle.svm.core.LinkerInvocation;
import com.oracle.svm.core.annotate.Delete;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.hub.ClassForNameSupport;
import com.oracle.svm.core.meta.SharedField;
import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.meta.SharedType;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.ameta.FieldValueInterceptionSupport;
import com.oracle.svm.hosted.analysis.Inflation;
import com.oracle.svm.hosted.c.NativeLibraries;
import com.oracle.svm.hosted.code.CEntryPointData;
import com.oracle.svm.hosted.code.CompileQueue.CompileTask;
import com.oracle.svm.hosted.image.AbstractImage;
import com.oracle.svm.hosted.image.AbstractImage.NativeImageKind;
import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMetaAccess;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.option.HostedOptionProvider;
import com.oracle.svm.util.ReflectionUtil;

import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.phases.util.Providers;
import jdk.vm.ci.meta.MetaAccessProvider;

@SuppressWarnings("deprecation")
public class FeatureImpl {

    public abstract static class FeatureAccessImpl implements Feature.FeatureAccess {

        protected final FeatureHandler featureHandler;
        protected final ImageClassLoader imageClassLoader;
        protected final DebugContext debugContext;

        FeatureAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, DebugContext debugContext) {
            this.featureHandler = featureHandler;
            this.imageClassLoader = imageClassLoader;
            this.debugContext = debugContext;
        }

        public ImageClassLoader getImageClassLoader() {
            return imageClassLoader;
        }

        @Override
        public Class<?> findClassByName(String className) {
            return imageClassLoader.findClass(className).get();
        }

        public <T> List<Class<? extends T>> findSubclasses(Class<T> baseClass) {
            return imageClassLoader.findSubclasses(baseClass, false);
        }

        public List<Class<?>> findAnnotatedClasses(Class<? extends Annotation> annotationClass) {
            return imageClassLoader.findAnnotatedClasses(annotationClass, false);
        }

        public List<Method> findAnnotatedMethods(Class<? extends Annotation> annotationClass) {
            return imageClassLoader.findAnnotatedMethods(annotationClass);
        }

        public List<Field> findAnnotatedFields(Class<? extends Annotation> annotationClass) {
            return imageClassLoader.findAnnotatedFields(annotationClass);
        }

        public FeatureHandler getFeatureHandler() {
            return featureHandler;
        }

        public DebugContext getDebugContext() {
            return debugContext;
        }

        @Override
        public List<Path> getApplicationClassPath() {
            return imageClassLoader.applicationClassPath();
        }

        @Override
        public List<Path> getApplicationModulePath() {
            return imageClassLoader.applicationModulePath();
        }

        @Override
        public ClassLoader getApplicationClassLoader() {
            return imageClassLoader.getClassLoader();
        }
    }

    public static class IsInConfigurationAccessImpl extends FeatureAccessImpl implements Feature.IsInConfigurationAccess {
        IsInConfigurationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
        }
    }

    public static class AfterRegistrationAccessImpl extends FeatureAccessImpl implements Feature.AfterRegistrationAccess {
        private final MetaAccessProvider metaAccess;
        private Pair<Method, CEntryPointData> mainEntryPoint;

        public AfterRegistrationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, MetaAccessProvider metaAccess, Pair<Method, CEntryPointData> mainEntryPoint,
                        DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
            this.metaAccess = metaAccess;
            this.mainEntryPoint = mainEntryPoint;
        }

        public MetaAccessProvider getMetaAccess() {
            return metaAccess;
        }

        public void setMainEntryPoint(Pair<Method, CEntryPointData> mainEntryPoint) {
            this.mainEntryPoint = mainEntryPoint;
        }

        public Pair<Method, CEntryPointData> getMainEntryPoint() {
            return mainEntryPoint;
        }
    }

    abstract static class AnalysisAccessBase extends FeatureAccessImpl {

        protected final Inflation bb;

        AnalysisAccessBase(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
            this.bb = bb;
        }

        public BigBang getBigBang() {
            return bb;
        }

        public AnalysisUniverse getUniverse() {
            return bb.getUniverse();
        }

        public AnalysisMetaAccess getMetaAccess() {
            return bb.getMetaAccess();
        }

        public boolean isReachable(Class<?> clazz) {
            return isReachable(getMetaAccess().lookupJavaType(clazz));
        }

        public boolean isReachable(AnalysisType type) {
            return type.isReachable();
        }

        public boolean isReachable(Field field) {
            return isReachable(getMetaAccess().lookupJavaField(field));
        }

        public boolean isReachable(AnalysisField field) {
            return field.isAccessed();
        }

        public boolean isReachable(Executable method) {
            return isReachable(getMetaAccess().lookupJavaMethod(method));
        }

        public boolean isReachable(AnalysisMethod method) {
            return method.isReachable();
        }

        public Set<Class<?>> reachableSubtypes(Class<?> baseClass) {
            return reachableSubtypes(getMetaAccess().lookupJavaType(baseClass)).stream()
                            .map(AnalysisType::getJavaClass).collect(Collectors.toCollection(HashSet::new));
        }

        Set<AnalysisType> reachableSubtypes(AnalysisType baseType) {
            return AnalysisUniverse.reachableSubtypes(baseType);
        }

        public Set<Executable> reachableMethodOverrides(Executable baseMethod) {
            return reachableMethodOverrides(getMetaAccess().lookupJavaMethod(baseMethod)).stream()
                            .map(AnalysisMethod::getJavaMethod)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        Set<AnalysisMethod> reachableMethodOverrides(AnalysisMethod baseMethod) {
            return baseMethod.collectMethodImplementations(true);
        }

        public void rescanObject(Object obj) {
            getUniverse().getHeapScanner().rescanObject(obj);
        }

        public void rescanField(Object receiver, Field field) {
            getUniverse().getHeapScanner().rescanField(receiver, field);
        }

        public void rescanRoot(Field field) {
            getUniverse().getHeapScanner().rescanRoot(field);
        }

        public Field findField(String declaringClassName, String fieldName) {
            return findField(imageClassLoader.findClassOrFail(declaringClassName), fieldName);
        }

        public Field findField(Class<?> declaringClass, String fieldName) {
            return ReflectionUtil.lookupField(declaringClass, fieldName);
        }

        public void ensureInitialized(String className) {
            try {
                imageClassLoader.forName(className, true);
            } catch (ClassNotFoundException e) {
                throw VMError.shouldNotReachHere(e);
            }
        }
    }

    public static class DuringSetupAccessImpl extends AnalysisAccessBase implements Feature.DuringSetupAccess {

        public DuringSetupAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, debugContext);
        }

        @Override
        public void registerObjectReplacer(Function<Object, Object> replacer) {
            getUniverse().registerObjectReplacer(replacer);
        }

        /**
         * Register an object replacer which may return an ImageHeapConstant. Note only one replacer
         * can be triggered for a given object; otherwise an error will be thrown. Too, if the
         * object should not be replaced then {@code null} should be returned.
         */
        public void registerObjectToConstantReplacer(Function<Object, ImageHeapConstant> replacer) {
            getUniverse().registerObjectToConstantReplacer(replacer);
        }

        /**
         * Register a callback that is executed when an object of the specified type or any of its
         * subtypes is marked as reachable. The callback is executed before the object is added to
         * the shadow heap. A callback may throw an {@link UnsupportedFeatureException} to reject an
         * object based on specific validation rules. This will stop the image build and report how
         * the object was reached.
         *
         * @since 24.0
         */
        public <T> void registerObjectReachableCallback(Class<T> clazz, ObjectReachableCallback<T> callback) {
            getMetaAccess().lookupJavaType(clazz).registerObjectReachableCallback(callback);
        }

        @Override
        public <T> void registerObjectReachabilityHandler(Consumer<T> callback, Class<T> clazz) {
            ObjectReachableCallback<T> wrapper = (access, obj, reason) -> callback.accept(obj);
            getMetaAccess().lookupJavaType(clazz).registerObjectReachableCallback(wrapper);
        }

        public void registerSubstitutionProcessor(SubstitutionProcessor substitution) {
            getUniverse().registerFeatureSubstitution(substitution);
        }

        public void registerNativeSubstitutionProcessor(SubstitutionProcessor substitution) {
            getUniverse().registerFeatureNativeSubstitution(substitution);
        }

        /**
         * Registers a listener that is notified for every class that is identified as reachable by
         * the analysis. The newly discovered class is passed as a parameter to the listener.
         * <p>
         * The listener is called during the analysis, at similar times as
         * {@link Feature#duringAnalysis}. The first argument is a {@link DuringAnalysisAccess
         * access} object that allows to query the analysis state. If the handler performs changes
         * the analysis universe, e.g., makes new types or methods reachable, it needs to call
         * {@link DuringAnalysisAccess#requireAnalysisIteration()} to trigger a new iteration of the
         * analysis.
         *
         * @since 19.0
         */
        public void registerClassReachabilityListener(BiConsumer<DuringAnalysisAccess, Class<?>> listener) {
            getHostVM().registerClassReachabilityListener(listener);
        }

        public SVMHost getHostVM() {
            return bb.getHostVM();
        }
    }

    public static class BeforeAnalysisAccessImpl extends AnalysisAccessBase implements Feature.BeforeAnalysisAccess {

        private final NativeLibraries nativeLibraries;
        private final ClassForNameSupport classForNameSupport;
        private final Map<Consumer<DuringAnalysisAccess>, ElementNotification> reachabilityNotifications = new ConcurrentHashMap<>();

        public BeforeAnalysisAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, NativeLibraries nativeLibraries,
                        DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, debugContext);
            this.nativeLibraries = nativeLibraries;
            this.classForNameSupport = ClassForNameSupport.currentLayer();
        }

        public NativeLibraries getNativeLibraries() {
            return nativeLibraries;
        }

        @Override
        public void registerAsUsed(Class<?> clazz) {
            registerAsUsed(getMetaAccess().lookupJavaType(clazz), "registered from Feature API");
        }

        public void registerAsUsed(Class<?> clazz, Object reason) {
            registerAsUsed(getMetaAccess().lookupJavaType(clazz), reason);
        }

        public void registerAsUsed(AnalysisType aType, Object reason) {
            aType.registerAsReachable(reason);
        }

        @Override
        public void registerAsInHeap(Class<?> clazz) {
            registerAsInHeap(getMetaAccess().lookupJavaType(clazz), "registered from Feature API");
        }

        public void registerAsInHeap(Class<?> clazz, Object reason) {
            registerAsInHeap(getMetaAccess().lookupJavaType(clazz), reason);
        }

        public void registerAsInHeap(AnalysisType aType, Object reason) {
            aType.registerAsInstantiated(reason);
        }

        @Override
        public void registerAsUnsafeAllocated(Class<?> clazz) {
            registerAsUnsafeAllocated(getMetaAccess().lookupJavaType(clazz));
        }

        public void registerAsUnsafeAllocated(AnalysisType aType) {
            if (aType.isAbstract()) {
                throw UserError.abort("Cannot register an abstract class as instantiated: " + aType.toJavaName(true));
            }
            aType.registerAsUnsafeAllocated("From feature");
            classForNameSupport.registerUnsafeAllocated(ConfigurationCondition.alwaysTrue(), aType.getJavaClass());
        }

        @Override
        public void registerAsAccessed(Field field) {
            registerAsAccessed(getMetaAccess().lookupJavaField(field), "registered from Feature API");
        }

        public void registerAsAccessed(AnalysisField aField, Object reason) {
            aField.registerAsAccessed(reason);
        }

        public void registerAsRead(Field field, Object reason) {
            registerAsRead(getMetaAccess().lookupJavaField(field), reason);
        }

        public void registerAsRead(AnalysisField aField, Object reason) {
            aField.registerAsRead(reason);
        }

        @Override
        public void registerAsUnsafeAccessed(Field field) {
            registerAsUnsafeAccessed(getMetaAccess().lookupJavaField(field), "registered from Feature API");
        }

        public void registerAsUnsafeAccessed(Field field, Object reason) {
            registerAsUnsafeAccessed(getMetaAccess().lookupJavaField(field), reason);
        }

        public boolean registerAsUnsafeAccessed(AnalysisField aField, Object reason) {
            assert !AnnotationAccess.isAnnotationPresent(aField, Delete.class);
            return aField.registerAsUnsafeAccessed(reason);
        }

        public void registerAsRoot(Executable method, boolean invokeSpecial, String reason, MultiMethod.MultiMethodKey... otherRoots) {
            bb.addRootMethod(method, invokeSpecial, reason, otherRoots);
        }

        public void registerAsRoot(AnalysisMethod aMethod, boolean invokeSpecial, String reason, MultiMethod.MultiMethodKey... otherRoots) {
            bb.addRootMethod(aMethod, invokeSpecial, reason, otherRoots);
        }

        public void registerAsRoot(AnalysisMethod aMethod, boolean invokeSpecial, ObjectScanner.ScanReason reason, MultiMethod.MultiMethodKey... otherRoots) {
            bb.addRootMethod(aMethod, invokeSpecial, reason, otherRoots);
        }

        public void registerUnsafeFieldsRecomputed(Class<?> clazz) {
            getMetaAccess().lookupJavaType(clazz).registerUnsafeFieldsRecomputed();
        }

        public SVMHost getHostVM() {
            return bb.getHostVM();
        }

        public void registerHierarchyForReflectiveInstantiation(Class<?> c) {
            findSubclasses(c).stream().filter(clazz -> !Modifier.isAbstract(clazz.getModifiers())).forEach(clazz -> RuntimeReflection.registerForReflectiveInstantiation(clazz));
        }

        @Override
        public void registerReachabilityHandler(Consumer<DuringAnalysisAccess> callback, Object... elements) {
            /*
             * All callback->notification pairs are tracked by the reachabilityNotifications map to
             * prevent registering the same callback multiple times. The notifications are also
             * tracked by each AnalysisElement, i.e., each trigger, and are removed as soon as they
             * are notified.
             */
            ElementNotification notification = reachabilityNotifications.computeIfAbsent(callback, ElementNotification::new);

            if (notification.isNotified()) {
                /* Already notified from an earlier registration, nothing to do. */
                return;
            }

            for (Object trigger : elements) {
                AnalysisElement analysisElement = switch (trigger) {
                    case Class<?> clazz -> getMetaAccess().lookupJavaType(clazz);
                    case Field field -> getMetaAccess().lookupJavaField(field);
                    case Executable executable -> getMetaAccess().lookupJavaMethod(executable);
                    default -> throw UserError.abort("'registerReachabilityHandler' called with an element that is not a Class, Field, or Executable: %s",
                                    trigger.getClass().getTypeName());
                };

                analysisElement.registerReachabilityNotification(notification);
                if (analysisElement.isTriggered()) {
                    /*
                     * Element already triggered, just notify the callback. At this point we could
                     * just notify the callback and bail out, but, for debugging, it may be useful
                     * to execute the notification for each trigger. Note that although the
                     * notification can be shared between multiple triggers the notification
                     * mechanism ensures that the callback itself is only executed once.
                     */
                    analysisElement.notifyReachabilityCallback(getUniverse(), notification);
                }
            }
        }

        @Override
        public void registerMethodOverrideReachabilityHandler(BiConsumer<DuringAnalysisAccess, Executable> callback, Executable baseMethod) {
            AnalysisMethod baseAnalysisMethod = getMetaAccess().lookupJavaMethod(baseMethod);
            MethodOverrideReachableNotification notification = new MethodOverrideReachableNotification(callback);
            baseAnalysisMethod.registerOverrideReachabilityNotification(notification);

            /*
             * Notify for already reachable overrides. When a new override becomes reachable all
             * installed reachability callbacks in the supertypes declaring the method are
             * triggered.
             */
            for (AnalysisMethod override : reachableMethodOverrides(baseAnalysisMethod)) {
                notification.notifyCallback(getUniverse(), override);
            }
        }

        @Override
        public void registerSubtypeReachabilityHandler(BiConsumer<DuringAnalysisAccess, Class<?>> callback, Class<?> baseClass) {
            AnalysisType baseType = getMetaAccess().lookupJavaType(baseClass);
            SubtypeReachableNotification notification = new SubtypeReachableNotification(callback);
            baseType.registerSubtypeReachabilityNotification(notification);

            /*
             * Notify for already reachable subtypes. When a new type becomes reachable all
             * installed reachability callbacks in the supertypes are triggered.
             */
            for (AnalysisType subtype : reachableSubtypes(baseType)) {
                notification.notifyCallback(getUniverse(), subtype);
            }
        }

        @Override
        public void registerClassInitializerReachabilityHandler(Consumer<DuringAnalysisAccess> callback, Class<?> clazz) {
            /*
             * In our current static analysis implementations, there is no difference between the
             * reachability of a class and the reachability of its class initializer.
             */
            registerReachabilityHandler(callback, clazz);
        }

        @Override
        public void registerFieldValueTransformer(Field field, FieldValueTransformer transformer) {
            FieldValueInterceptionSupport.singleton().registerFieldValueTransformer(field, transformer);
        }

        /**
         * Registers a method as having an analysis-opaque return value. This designation limits the
         * type-flow analysis performed on the method's return value.
         */
        public void registerOpaqueMethodReturn(Method method) {
            AnalysisMethod aMethod = bb.getMetaAccess().lookupJavaMethod(method);
            VMError.guarantee(aMethod.getAllMultiMethods().size() == 1, "Opaque method return called for method with >1 multimethods: %s ", method);
            aMethod.setOpaqueReturn();
        }
    }

    public static class DuringAnalysisAccessImpl extends BeforeAnalysisAccessImpl implements Feature.DuringAnalysisAccess {

        private boolean requireAnalysisIteration;

        public DuringAnalysisAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, NativeLibraries nativeLibraries, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, nativeLibraries, debugContext);
        }

        @Override
        public void requireAnalysisIteration() {
            requireAnalysisIteration = true;
        }

        public boolean getAndResetRequireAnalysisIteration() {
            boolean result = requireAnalysisIteration;
            requireAnalysisIteration = false;
            return result;
        }

    }

    public static class ConcurrentAnalysisAccessImpl extends DuringAnalysisAccessImpl {

        public ConcurrentAnalysisAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, NativeLibraries nativeLibraries, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, nativeLibraries, debugContext);
        }

        @Override
        public void requireAnalysisIteration() {
            if (bb.executorIsStarted()) {
                throw VMError.shouldNotReachHere("Calling DuringAnalysisAccessImpl.requireAnalysisIteration() is not necessary when running the reachability handlers concurrently during analysis.");
            }
            /*
             * While it may seem wrong that the concurrent analysis accessor can request an
             * additional analysis iteration this is necessary because the concurrent reachability
             * callbacks can be forced to run synchronously in the single-threaded "during analysis"
             * phase when elements are marked as reachable from Feature.duringAnalysis hooks.
             */
            super.requireAnalysisIteration();
        }

    }

    public static class AfterAnalysisAccessImpl extends AnalysisAccessBase implements Feature.AfterAnalysisAccess {
        public AfterAnalysisAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, debugContext);
        }
    }

    public static class OnAnalysisExitAccessImpl extends AnalysisAccessBase implements Feature.OnAnalysisExitAccess {
        public OnAnalysisExitAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, Inflation bb, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, bb, debugContext);
        }
    }

    public static class BeforeUniverseBuildingAccessImpl extends FeatureAccessImpl implements Feature.BeforeUniverseBuildingAccess {
        protected final HostedMetaAccess hMetaAccess;

        BeforeUniverseBuildingAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, DebugContext debugContext, HostedMetaAccess hMetaAccess) {
            super(featureHandler, imageClassLoader, debugContext);
            this.hMetaAccess = hMetaAccess;
        }

        public HostedMetaAccess getMetaAccess() {
            return hMetaAccess;
        }
    }

    public static class CompilationAccessImpl extends FeatureAccessImpl implements Feature.CompilationAccess {

        protected final AnalysisUniverse aUniverse;
        protected final HostedUniverse hUniverse;
        protected final NativeImageHeap heap;
        protected final RuntimeConfiguration runtimeConfiguration;
        protected final NativeLibraries nativeLibraries;

        CompilationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, AnalysisUniverse aUniverse, HostedUniverse hUniverse, NativeImageHeap heap, DebugContext debugContext,
                        RuntimeConfiguration runtimeConfiguration, NativeLibraries nativeLibraries) {
            super(featureHandler, imageClassLoader, debugContext);
            this.aUniverse = aUniverse;
            this.hUniverse = hUniverse;
            this.heap = heap;
            this.runtimeConfiguration = runtimeConfiguration;
            this.nativeLibraries = nativeLibraries;
        }

        public NativeLibraries getNativeLibraries() {
            return nativeLibraries;
        }

        @Override
        public long objectFieldOffset(Field field) {
            return objectFieldOffset(getMetaAccess().lookupJavaField(field));
        }

        public long objectFieldOffset(HostedField hField) {
            int result = hField.getLocation();
            assert result > 0 : Assertions.errorMessage(hField, hField.getLocation());
            return result;
        }

        @Override
        public void registerAsImmutable(Object object) {
            heap.registerAsImmutable(object);
        }

        @Override
        public void registerAsImmutable(Object root, Predicate<Object> includeObject) {
            heap.registerAsImmutable(root, includeObject);
        }

        public HostedMetaAccess getMetaAccess() {
            return (HostedMetaAccess) getProviders().getMetaAccess();
        }

        public Providers getProviders() {
            return runtimeConfiguration.getProviders();
        }

        public HostedUniverse getUniverse() {
            return hUniverse;
        }

        public Collection<? extends SharedType> getTypes() {
            return hUniverse.getTypes();
        }

        public Collection<? extends SharedField> getFields() {
            return hUniverse.getFields();
        }

        public Collection<? extends SharedMethod> getMethods() {
            return hUniverse.getMethods();
        }

        public ImageHeapScanner getHeapScanner() {
            return aUniverse.getHeapScanner();
        }
    }

    public static class BeforeCompilationAccessImpl extends CompilationAccessImpl implements Feature.BeforeCompilationAccess {

        public BeforeCompilationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, AnalysisUniverse aUniverse, HostedUniverse hUniverse,
                        NativeImageHeap heap, DebugContext debugContext, RuntimeConfiguration runtimeConfiguration, NativeLibraries nativeLibraries) {
            super(featureHandler, imageClassLoader, aUniverse, hUniverse, heap, debugContext, runtimeConfiguration, nativeLibraries);
        }

        public RuntimeConfiguration getRuntimeConfiguration() {
            return runtimeConfiguration;
        }
    }

    public static class AfterCompilationAccessImpl extends CompilationAccessImpl implements Feature.AfterCompilationAccess {
        private final Map<HostedMethod, CompileTask> compilations;
        private final NativeImageCodeCache codeCache;

        public AfterCompilationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, AnalysisUniverse aUniverse, HostedUniverse hUniverse,
                        Map<HostedMethod, CompileTask> compilations, NativeImageCodeCache codeCache, NativeImageHeap heap, DebugContext debugContext,
                        RuntimeConfiguration runtimeConfiguration, NativeLibraries nativeLibraries) {
            super(featureHandler, imageClassLoader, aUniverse, hUniverse, heap, debugContext, runtimeConfiguration, nativeLibraries);
            this.compilations = compilations;
            this.codeCache = codeCache;
        }

        public Collection<CompileTask> getCompilationTasks() {
            return compilations.values();
        }

        public Map<HostedMethod, CompileTask> getCompilations() {
            return compilations;
        }

        public NativeImageCodeCache getCodeCache() {
            return codeCache;
        }

        public NativeImageHeap getHeap() {
            return heap;
        }
    }

    public static class BeforeHeapLayoutAccessImpl extends CompilationAccessImpl implements Feature.BeforeHeapLayoutAccess {
        public BeforeHeapLayoutAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, AnalysisUniverse aUniverse, HostedUniverse hUniverse, NativeImageHeap heap,
                        DebugContext debugContext, RuntimeConfiguration runtimeConfiguration, NativeLibraries nativeLibraries) {
            super(featureHandler, imageClassLoader, aUniverse, hUniverse, heap, debugContext, runtimeConfiguration, nativeLibraries);
        }
    }

    public static class AfterHeapLayoutAccessImpl extends FeatureAccessImpl implements Feature.AfterHeapLayoutAccess {
        protected final HostedMetaAccess hMetaAccess;
        protected final NativeImageHeap heap;

        public AfterHeapLayoutAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, NativeImageHeap heap, HostedMetaAccess hMetaAccess, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
            this.heap = heap;
            this.hMetaAccess = hMetaAccess;
        }

        public HostedMetaAccess getMetaAccess() {
            return hMetaAccess;
        }

        public NativeImageHeap getHeap() {
            return heap;
        }
    }

    public static class BeforeImageWriteAccessImpl extends FeatureAccessImpl implements Feature.BeforeImageWriteAccess {
        private List<Function<LinkerInvocation, LinkerInvocation>> linkerInvocationTransformers = null;

        protected final String imageName;
        protected final AbstractImage image;
        protected final RuntimeConfiguration runtimeConfig;
        protected final AnalysisUniverse aUniverse;
        protected final HostedUniverse hUniverse;
        protected final HostedOptionProvider optionProvider;
        protected final HostedMetaAccess hMetaAccess;

        BeforeImageWriteAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, String imageName, AbstractImage image, RuntimeConfiguration runtimeConfig,
                        AnalysisUniverse aUniverse, HostedUniverse hUniverse, HostedOptionProvider optionProvider, HostedMetaAccess hMetaAccess, DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
            this.imageName = imageName;
            this.image = image;
            this.runtimeConfig = runtimeConfig;
            this.aUniverse = aUniverse;
            this.hUniverse = hUniverse;
            this.optionProvider = optionProvider;
            this.hMetaAccess = hMetaAccess;
        }

        public String getImageName() {
            return imageName;
        }

        public AbstractImage getImage() {
            return image;
        }

        public String getOutputFilename() {
            return image.getImageKind().getOutputFilename(imageName);
        }

        public RuntimeConfiguration getRuntimeConfiguration() {
            return runtimeConfig;
        }

        public HostedUniverse getHostedUniverse() {
            return hUniverse;
        }

        public HostedMetaAccess getHostedMetaAccess() {
            return hMetaAccess;
        }

        public Iterable<Function<LinkerInvocation, LinkerInvocation>> getLinkerInvocationTransformers() {
            if (linkerInvocationTransformers == null) {
                return Collections.emptyList();
            }
            return linkerInvocationTransformers;
        }

        public void registerLinkerInvocationTransformer(Function<LinkerInvocation, LinkerInvocation> transformer) {
            if (linkerInvocationTransformers == null) {
                linkerInvocationTransformers = new ArrayList<>();
            }
            linkerInvocationTransformers.add(transformer);
        }
    }

    public static class AfterAbstractImageCreationAccessImpl extends FeatureAccessImpl implements InternalFeature.AfterAbstractImageCreationAccess {
        protected final AbstractImage abstractImage;
        protected final SubstrateBackend substrateBackend;

        AfterAbstractImageCreationAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, DebugContext debugContext, AbstractImage abstractImage,
                        SubstrateBackend substrateBackend) {
            super(featureHandler, imageClassLoader, debugContext);
            this.abstractImage = abstractImage;
            this.substrateBackend = substrateBackend;
        }

        public AbstractImage getImage() {
            return abstractImage;
        }

        public SubstrateBackend getSubstrateBackend() {
            return substrateBackend;
        }
    }

    public static class AfterImageWriteAccessImpl extends FeatureAccessImpl implements Feature.AfterImageWriteAccess {
        private final HostedUniverse hUniverse;
        protected final LinkerInvocation linkerInvocation;
        protected final Path tempDirectory;
        protected final NativeImageKind imageKind;

        AfterImageWriteAccessImpl(FeatureHandler featureHandler, ImageClassLoader imageClassLoader, HostedUniverse hUniverse, LinkerInvocation linkerInvocation, Path tempDirectory,
                        NativeImageKind imageKind,
                        DebugContext debugContext) {
            super(featureHandler, imageClassLoader, debugContext);
            this.hUniverse = hUniverse;
            this.linkerInvocation = linkerInvocation;
            this.tempDirectory = tempDirectory;
            this.imageKind = imageKind;
        }

        public HostedUniverse getUniverse() {
            return hUniverse;
        }

        @Override
        public Path getImagePath() {
            if (linkerInvocation == null) {
                /* Some backends might not use native-linking */
                return null;
            }
            return linkerInvocation.getOutputFile();
        }

        public Path getTempDirectory() {
            return tempDirectory;
        }

        public NativeImageKind getImageKind() {
            return imageKind;
        }

        public List<String> getImageSymbols(boolean onlyGlobal) {
            return linkerInvocation.getImageSymbols(onlyGlobal);
        }
    }
}
