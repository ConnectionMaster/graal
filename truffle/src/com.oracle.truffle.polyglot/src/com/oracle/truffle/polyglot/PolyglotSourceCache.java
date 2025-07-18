/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.polyglot;

import static com.oracle.truffle.polyglot.EngineAccessor.LANGUAGE;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.graalvm.options.OptionDescriptors;
import org.graalvm.polyglot.SandboxPolicy;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.polyglot.PolyglotImpl.VMObject;

final class PolyglotSourceCache {

    private final StrongCache strongCache;
    private final WeakCache weakCache;
    private SourceCacheListener sourceCacheListener;

    PolyglotSourceCache(ReferenceQueue<Source> deadSourcesQueue, TracingSourceCacheListener sourceCacheListener, SourceCacheStatisticsListener sourceCacheStatisticsListener) {
        this.sourceCacheListener = new SourceCacheListenerDispatch(sourceCacheListener, sourceCacheStatisticsListener);
        this.weakCache = new WeakCache(deadSourcesQueue);
        this.strongCache = new StrongCache();
    }

    void patch(SourceCacheListener listener, SourceCacheStatisticsListener statisticsListener) {
        this.sourceCacheListener = new SourceCacheListenerDispatch(listener, statisticsListener);
    }

    CallTarget parseCached(ParseOrigin origin, PolyglotLanguageContext context, Source source, String[] argumentNames) {
        CallTarget target;
        if (source.isCached()) {
            Cache strong = this.strongCache;
            boolean useStrong = context.getEngine().storeEngine;
            if (useStrong || !strong.isEmpty()) {
                target = strong.lookup(origin, context, source, argumentNames, useStrong);
                if (target != null) {
                    // target found in strong cache
                    return target;
                } else {
                    // fallback to weak cache.
                }
            }
            target = weakCache.lookup(origin, context, source, argumentNames, true);
        } else {
            long parseStart = System.currentTimeMillis();
            target = parseImpl(origin, context, argumentNames, source);
            sourceCacheListener.onCacheMiss(source, target, SourceCacheListener.CacheType.UNCACHED, parseStart);
        }
        return target;
    }

    void listCachedSources(PolyglotImpl polyglot, Collection<Object> source) {
        strongCache.listSources(polyglot, source);
        weakCache.listSources(polyglot, source);
    }

    void cleanupStaleEntries() {
        WeakCache.cleanupStaleEntries(weakCache.deadSources, sourceCacheListener);
    }

    private static CallTarget parseImpl(ParseOrigin origin, PolyglotLanguageContext context, String[] argumentNames, Source source) {
        validateSource(context, source);
        CallTarget parsedTarget = LANGUAGE.parse(context.requireEnv(), source, context.getLanguageInstance().parseSourceOptions(origin, source, null), null, argumentNames);
        if (parsedTarget == null) {
            throw new IllegalStateException(String.format("Parsing resulted in a null CallTarget for %s.", source));
        }
        return parsedTarget;
    }

    private static void validateSource(PolyglotLanguageContext context, Source source) {
        if (!source.hasBytes() && !source.hasCharacters()) {
            throw PolyglotEngineException.illegalArgument(String.format("Error evaluating the source. The source does not specify characters nor bytes."));
        }
        String mimeType = source.getMimeType();
        Set<String> mimeTypes = context.language.cache.getMimeTypes();
        if (mimeType != null && !mimeTypes.contains(mimeType)) {
            throw PolyglotEngineException.illegalArgument(String.format("Error evaluating the source. The language %s does not support MIME type %s. Supported MIME types are %s.",
                            source.getLanguage(), mimeType, mimeTypes));
        }
        String activeMimeType = mimeType;
        if (activeMimeType == null) {
            activeMimeType = context.language.cache.getDefaultMimeType();
        }

        boolean expectCharacters = activeMimeType != null ? context.language.cache.isCharacterMimeType(activeMimeType) : true;
        if (mimeType != null && source.hasCharacters() != expectCharacters) {
            if (source.hasBytes()) {
                throw PolyglotEngineException.illegalArgument(
                                String.format("Error evaluating the source. MIME type '%s' is character based for language '%s' but the source contents are byte based.", mimeType,
                                                source.getLanguage()));
            } else {
                throw PolyglotEngineException.illegalArgument(
                                String.format("Error evaluating the source. MIME type '%s' is byte based for language '%s' but the source contents are character based.", mimeType,
                                                source.getLanguage()));
            }
        }

        if (source.hasCharacters() != expectCharacters) {
            Set<String> binaryMimeTypes = new HashSet<>();
            Set<String> characterMimeTypes = new HashSet<>();
            for (String supportedMimeType : mimeTypes) {
                if (context.language.cache.isCharacterMimeType(supportedMimeType)) {
                    characterMimeTypes.add(supportedMimeType);
                } else {
                    binaryMimeTypes.add(supportedMimeType);
                }
            }
            if (expectCharacters) {
                if (binaryMimeTypes.isEmpty()) {
                    throw PolyglotEngineException.illegalArgument(String.format(
                                    "Error evaluating the source. The language %s only supports character based sources but a binary based source was provided.",
                                    source.getLanguage()));
                } else {
                    throw PolyglotEngineException.illegalArgument(String.format(
                                    "Error evaluating the source. The language %s expects character based sources by default but a binary based source was provided. " +
                                                    "Provide a binary based source instead or specify a MIME type for the source. " +
                                                    "Available MIME types for binary based sources are %s.",
                                    source.getLanguage(), binaryMimeTypes));
                }
            } else {
                if (characterMimeTypes.isEmpty()) {
                    throw PolyglotEngineException.illegalArgument(String.format(
                                    "Error evaluating the source. The language %s only supports binary based sources but a character based source was provided.",
                                    source.getLanguage()));
                } else {
                    throw PolyglotEngineException.illegalArgument(String.format(
                                    "Error evaluating the source. The language %s expects character based sources by default but a binary based source was provided. " +
                                                    "Provide a character based source instead or specify a MIME type for the source. " +
                                                    "Available MIME types for character based sources are %s.",
                                    source.getLanguage(), characterMimeTypes));
                }
            }
        }
    }

    static Map<String, OptionValuesImpl> parseSourceOptions(PolyglotEngineImpl engine, Map<String, String> options, String groupOnly, SandboxPolicy policy, boolean allowExperimentalOptions) {
        if (options.isEmpty()) {
            // fast-path
            return Map.of();
        }
        Map<String, OptionValuesImpl> optionsById = new LinkedHashMap<>();
        for (String optionKey : options.keySet()) {
            String group = PolyglotEngineImpl.parseOptionGroup(optionKey);
            if (groupOnly != null && !groupOnly.equals(group)) {
                continue;
            }
            String optionValue = options.get(optionKey);
            try {
                VMObject object = findComponentForSourceOption(engine, optionKey, group);
                String id;
                OptionDescriptors descriptors;
                if (object instanceof PolyglotLanguage) {
                    PolyglotLanguage language = (PolyglotLanguage) object;
                    id = language.getId();
                    descriptors = language.getSourceOptionsInternal();
                } else if (object instanceof PolyglotInstrument) {
                    PolyglotInstrument instrument = (PolyglotInstrument) object;
                    id = instrument.getId();
                    descriptors = instrument.getSourceOptionsInternal();
                } else {
                    throw new AssertionError("invalid vm object");
                }

                OptionValuesImpl targetOptions = optionsById.get(id);
                if (targetOptions == null) {
                    targetOptions = new OptionValuesImpl(descriptors, policy, false, true);
                    optionsById.put(id, targetOptions);
                }

                targetOptions.put(optionKey, optionValue, allowExperimentalOptions, engine::getAllSourceOptions);
            } catch (PolyglotEngineException e) {
                throw PolyglotEngineException.illegalArgument(String.format("Failed to parse source option '%s=%s': %s",
                                optionKey, optionValue, e.e.getMessage()), e.e);
            }
        }
        return optionsById;
    }

    private static VMObject findComponentForSourceOption(PolyglotEngineImpl engine, final String optionKey, String group) {
        PolyglotLanguage language = engine.idToLanguage.get(group);
        if (language == null) {
            PolyglotInstrument instrument = engine.idToInstrument.get(group);
            if (instrument != null) {
                return instrument;
            }
            throw OptionValuesImpl.failNotFound(engine.getAllSourceOptions(), optionKey);
        }
        return language;
    }

    enum ParseOrigin {

        /**
         * The parsing request originates from an internal language parse request. E.g.
         * TruffleLanguage.Env#parseInternal.
         */
        LANGUAGE,

        /**
         * The parsing request originates from an internal instrument parse request. E.g.
         * TruffleInstrument.Env#parseInternal.
         */
        INSTRUMENT,

        /**
         * The parsing request originates from the embedder, like with Context.eval.
         */
        EMBEDDING

    }

    private abstract static class Cache {

        abstract boolean isEmpty();

        abstract CallTarget lookup(ParseOrigin origin, PolyglotLanguageContext context, Source source, String[] argumentNames, boolean parse);

        abstract void listSources(PolyglotImpl polyglot, Collection<Object> source);
    }

    static class StrongCacheValue {

        final CallTarget target;
        final AtomicLong hits = new AtomicLong();

        StrongCacheValue(CallTarget target) {
            this.target = target;
        }

    }

    private final class StrongCache extends Cache {

        private final ConcurrentHashMap<SourceKey, StrongCacheValue> sourceCache = new ConcurrentHashMap<>();

        @Override
        CallTarget lookup(ParseOrigin origin, PolyglotLanguageContext context, Source source, String[] argumentNames, boolean parse) {
            SourceKey key = new SourceKey(source, argumentNames);
            StrongCacheValue value = sourceCache.get(key);
            if (value == null) {
                if (parse) {
                    long parseStart = System.currentTimeMillis();
                    try {
                        value = new StrongCacheValue(parseImpl(origin, context, argumentNames, source));
                        StrongCacheValue prevValue = sourceCache.putIfAbsent(key, value);
                        if (prevValue != null) {
                            value = prevValue;
                        }
                        sourceCacheListener.onCacheMiss(source, value.target, SourceCacheListener.CacheType.STRONG, parseStart);
                    } catch (Throwable t) {
                        sourceCacheListener.onCacheFail(context.context.layer, source, SourceCacheListener.CacheType.STRONG, parseStart, t);
                        throw t;
                    }
                } else {
                    return null;
                }
            } else {
                value.hits.incrementAndGet();
                sourceCacheListener.onCacheHit(source, value.target, SourceCacheListener.CacheType.STRONG, value.hits.get());
            }
            return value.target;
        }

        @Override
        boolean isEmpty() {
            return sourceCache.isEmpty();
        }

        @Override
        void listSources(PolyglotImpl polyglot, Collection<Object> sources) {
            for (SourceKey key : sourceCache.keySet()) {
                sources.add(PolyglotImpl.getOrCreatePolyglotSource(polyglot, (Source) key.key));
            }
        }

    }

    private final class WeakCache extends Cache {

        private final ConcurrentHashMap<WeakSourceKey, WeakCacheValue> sourceCache = new ConcurrentHashMap<>();
        private final ReferenceQueue<Source> deadSources;
        private final WeakReference<WeakCache> cacheRef;

        WeakCache(ReferenceQueue<Source> deadSources) {
            this.deadSources = deadSources;
            this.cacheRef = new WeakReference<>(this);
        }

        @Override
        CallTarget lookup(ParseOrigin origin, PolyglotLanguageContext context, Source source, String[] argumentNames, boolean parse) {
            cleanupStaleEntries(deadSources, sourceCacheListener);
            Object sourceId = EngineAccessor.SOURCE.getSourceIdentifier(source);
            Source sourceValue = EngineAccessor.SOURCE.copySource(source);
            WeakSourceKey ref = new WeakSourceKey(new SourceKey(sourceId, argumentNames), source, cacheRef, deadSources);
            WeakCacheValue value = sourceCache.get(ref);
            if (value == null) {
                if (parse) {
                    long parseStart = System.currentTimeMillis();
                    try {
                        value = new WeakCacheValue(parseImpl(origin, context, argumentNames, sourceValue), sourceValue);
                        WeakCacheValue prev = sourceCache.putIfAbsent(ref, value);
                        if (prev != null) {
                            /*
                             * Parsed twice -> discard the one not in the cache.
                             */
                            value = prev;
                        }
                        sourceCacheListener.onCacheMiss(source, value.target, SourceCacheListener.CacheType.WEAK, parseStart);
                    } catch (Throwable t) {
                        sourceCacheListener.onCacheFail(context.context.layer, source, SourceCacheListener.CacheType.WEAK, parseStart, t);
                        throw t;
                    }
                } else {
                    return null;
                }
            } else {
                value.hits.incrementAndGet();
                sourceCacheListener.onCacheHit(source, value.target, SourceCacheListener.CacheType.WEAK, value.hits.get());
            }
            return value.target;
        }

        @Override
        boolean isEmpty() {
            return sourceCache.isEmpty();
        }

        @Override
        void listSources(PolyglotImpl polyglot, Collection<Object> sources) {
            cleanupStaleEntries(deadSources, sourceCacheListener);
            for (WeakCacheValue value : sourceCache.values()) {
                sources.add(PolyglotImpl.getOrCreatePolyglotSource(polyglot, value.source));
            }
        }

        /**
         * This is deliberately a static method, because the dead sources queue is
         * {@link PolyglotEngineImpl#getDeadSourcesQueue() shared} among all weak caches on a
         * particular polyglot engine, and so the elements of {@link WeakCache#deadSources the
         * queue} may {@link WeakSourceKey#cacheRef refer} to different weak caches.
         */
        private static void cleanupStaleEntries(ReferenceQueue<Source> deadSourcesQueue, SourceCacheListener cacheListener) {
            WeakSourceKey sourceRef;
            while ((sourceRef = (WeakSourceKey) deadSourcesQueue.poll()) != null) {
                WeakCache cache = sourceRef.cacheRef.get();
                WeakCacheValue value = cache != null ? cache.sourceCache.remove(sourceRef) : null;
                if (value != null) {
                    cacheListener.onCacheEvict(value.source, value.target, SourceCacheListener.CacheType.WEAK, value.hits.get());
                }
            }
        }

    }

    static class WeakCacheValue {

        final CallTarget target;
        final Source source;
        final AtomicLong hits = new AtomicLong();

        WeakCacheValue(CallTarget target, Source source) {
            this.target = target;
            this.source = source;
        }

    }

    private static final class SourceKey {

        private final Object key;
        private final String[] arguments;

        SourceKey(Object key, String[] arguments) {
            this.key = key;
            this.arguments = arguments != null && arguments.length == 0 ? null : arguments;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + key.hashCode();
            result = prime * result + Arrays.hashCode(arguments);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SourceKey) {
                SourceKey other = (SourceKey) obj;
                return key.equals(other.key) && Arrays.equals(arguments, other.arguments);
            } else {
                return false;
            }
        }

    }

    private static final class WeakSourceKey extends WeakReference<Source> {

        final SourceKey key;
        final WeakReference<WeakCache> cacheRef;

        WeakSourceKey(SourceKey key, Source value, WeakReference<WeakCache> cacheRef, ReferenceQueue<? super Source> q) {
            super(value, q);
            this.cacheRef = cacheRef;
            this.key = key;
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof WeakSourceKey) {
                WeakSourceKey other = (WeakSourceKey) obj;
                return key.equals(other.key);
            } else {
                return false;
            }
        }
    }

    private static final class SourceCacheListenerDispatch implements SourceCacheListener {
        private final SourceCacheListener[] listeners;

        SourceCacheListenerDispatch(SourceCacheListener... listeners) {
            this.listeners = Arrays.stream(listeners).filter(Objects::nonNull).toArray(SourceCacheListener[]::new);
        }

        @Override
        public void onCacheHit(Source source, CallTarget target, CacheType cacheType, long hits) {
            for (SourceCacheListener l : listeners) {
                l.onCacheHit(source, target, cacheType, hits);
            }
        }

        @Override
        public void onCacheMiss(Source source, CallTarget target, CacheType cacheType, long startTime) {
            for (SourceCacheListener l : listeners) {
                l.onCacheMiss(source, target, cacheType, startTime);
            }
        }

        @Override
        public void onCacheFail(PolyglotSharingLayer sharingLayer, Source source, CacheType cacheType, long startTime, Throwable throwable) {
            for (SourceCacheListener l : listeners) {
                l.onCacheFail(sharingLayer, source, cacheType, startTime, throwable);
            }
        }

        @Override
        public void onCacheEvict(Source source, CallTarget target, CacheType cacheType, long hits) {
            for (SourceCacheListener l : listeners) {
                l.onCacheEvict(source, target, cacheType, hits);
            }
        }
    }
}
