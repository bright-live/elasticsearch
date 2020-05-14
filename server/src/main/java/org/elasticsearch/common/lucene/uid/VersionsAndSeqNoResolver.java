/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.lucene.uid;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/** Utility class to resolve the Lucene doc ID, version, seqNo and primaryTerms for a given uid. */
public final class VersionsAndSeqNoResolver {

    private static class PerThreadLookupCache {
        private final Map<Thread, PerThreadIDVersionAndSeqNoLookup[]> perThreadPool = ConcurrentCollections.newConcurrentMap();
        private volatile long lastUsedInMillis;

        PerThreadIDVersionAndSeqNoLookup[] get(IndexReader reader, String uidField, long timeInMillis) throws IOException {
            this.lastUsedInMillis = timeInMillis;
            final PerThreadIDVersionAndSeqNoLookup[] reused = perThreadPool.get(Thread.currentThread());
            if (reused != null) {
                return reused;
            }
            final PerThreadIDVersionAndSeqNoLookup[] newState = new PerThreadIDVersionAndSeqNoLookup[reader.leaves().size()];
            for (LeafReaderContext leaf : reader.leaves()) {
                newState[leaf.ord] = new PerThreadIDVersionAndSeqNoLookup(leaf.reader(), uidField);
            }
            final PerThreadIDVersionAndSeqNoLookup[] existing = perThreadPool.put(Thread.currentThread(), newState);
            assert existing == null;
            return newState;
        }
    }

    private final ConcurrentMap<IndexReader.CacheKey, PerThreadLookupCache> lookupCaches =
        ConcurrentCollections.newConcurrentMapWithAggressiveConcurrency();
    private final LongSupplier timeInMillisSupplier;

    public VersionsAndSeqNoResolver(LongSupplier timeInMillisSupplier) {
        this.timeInMillisSupplier = timeInMillisSupplier;
    }

    private PerThreadIDVersionAndSeqNoLookup[] getLookupState(IndexReader reader, String uidField) throws IOException {
        final IndexReader.CacheHelper cacheHelper = reader.getReaderCacheHelper();
        PerThreadLookupCache lookupPool = lookupCaches.get(cacheHelper.getKey());
        if (lookupPool == null) {
            lookupPool = new PerThreadLookupCache();
            final PerThreadLookupCache existingPool = lookupCaches.putIfAbsent(cacheHelper.getKey(), lookupPool);
            if (existingPool != null) {
                lookupPool = existingPool;
            } else {
                cacheHelper.addClosedListener(lookupCaches::remove);
            }
        }
        return lookupPool.get(reader, uidField, timeInMillisSupplier.getAsLong());
    }

    /** Wraps an {@link LeafReaderContext}, a doc ID <b>relative to the context doc base</b> and a version. */
    public static class DocIdAndVersion {
        public final int docId;
        public final long version;
        public final long seqNo;
        public final long primaryTerm;
        public final LeafReader reader;
        public final int docBase;

        public DocIdAndVersion(int docId, long version, long seqNo, long primaryTerm, LeafReader reader, int docBase) {
            this.docId = docId;
            this.version = version;
            this.seqNo = seqNo;
            this.primaryTerm = primaryTerm;
            this.reader = reader;
            this.docBase = docBase;
        }
    }

    /** Wraps an {@link LeafReaderContext}, a doc ID <b>relative to the context doc base</b> and a seqNo. */
    public static class DocIdAndSeqNo {
        public final int docId;
        public final long seqNo;
        public final LeafReaderContext context;

        DocIdAndSeqNo(int docId, long seqNo, LeafReaderContext context) {
            this.docId = docId;
            this.seqNo = seqNo;
            this.context = context;
        }
    }


    /**
     * Load the internal doc ID and version for the uid from the reader, returning<ul>
     * <li>null if the uid wasn't found,
     * <li>a doc ID and a version otherwise
     * </ul>
     */
    public DocIdAndVersion loadDocIdAndVersion(IndexReader reader, Term term, boolean loadSeqNo) throws IOException {
        final PerThreadIDVersionAndSeqNoLookup[] lookups = getLookupState(reader, term.field());
        List<LeafReaderContext> leaves = reader.leaves();
        // iterate backwards to optimize for the frequently updated documents
        // which are likely to be in the last segments
        for (int i = leaves.size() - 1; i >= 0; i--) {
            final LeafReaderContext leaf = leaves.get(i);
            PerThreadIDVersionAndSeqNoLookup lookup = lookups[leaf.ord];
            DocIdAndVersion result = lookup.lookupVersion(term.bytes(), loadSeqNo, leaf);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Loads the internal docId and sequence number of the latest copy for a given uid from the provided reader.
     * The result is either null or the live and latest version of the given uid.
     */
    public DocIdAndSeqNo loadDocIdAndSeqNo(IndexReader reader, Term term) throws IOException {
        final PerThreadIDVersionAndSeqNoLookup[] lookups = getLookupState(reader, term.field());
        final List<LeafReaderContext> leaves = reader.leaves();
        // iterate backwards to optimize for the frequently updated documents
        // which are likely to be in the last segments
        for (int i = leaves.size() - 1; i >= 0; i--) {
            final LeafReaderContext leaf = leaves.get(i);
            final PerThreadIDVersionAndSeqNoLookup lookup = lookups[leaf.ord];
            final DocIdAndSeqNo result = lookup.lookupSeqNo(term.bytes(), leaf);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public int cacheSize() {
        return lookupCaches.size();
    }

    /**
     * When an IndexReader is no longer used (because we refresh or close the shard), then its key will be removed
     * from the lookupCaches. This cache is being invalidated during the indexing and eventually clean up when the
     * indexing stops (we flush when shards become idle). However, if we cache some lookups after the indexing stops
     * (see Engine#getFromSearcher), then we might keep these entries until we close the shard. To protect against
     * this situation, we periodically prune inactive cache entries.
     */
    public void pruneInactiveCachedEntries(TimeValue maxInactiveInterval) {
        final long maxTimeToPruneInMillis = timeInMillisSupplier.getAsLong() - maxInactiveInterval.millis();
        final Iterator<Map.Entry<IndexReader.CacheKey, PerThreadLookupCache>> iterator = lookupCaches.entrySet().iterator();
        while (iterator.hasNext()) {
            final PerThreadLookupCache entry = iterator.next().getValue();
            if (entry.lastUsedInMillis <= maxTimeToPruneInMillis) {
                iterator.remove();
            }
        }
    }
}
