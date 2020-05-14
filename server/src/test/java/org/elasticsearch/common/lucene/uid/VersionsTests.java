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

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.elasticsearch.Version;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.index.ElasticsearchDirectoryReader;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.mapper.VersionFieldMapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class VersionsTests extends ESTestCase {

    public static DirectoryReader reopen(DirectoryReader reader) throws IOException {
        return reopen(reader, true);
    }

    public static DirectoryReader reopen(DirectoryReader reader, boolean newReaderExpected) throws IOException {
        DirectoryReader newReader = DirectoryReader.openIfChanged(reader);
        if (newReader != null) {
            reader.close();
        } else {
            assertFalse(newReaderExpected);
        }
        return newReader;
    }

    public void testVersions() throws Exception {
        VersionsAndSeqNoResolver resolver = new VersionsAndSeqNoResolver(() -> 0);
        Directory dir = newDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(Lucene.STANDARD_ANALYZER));
        DirectoryReader directoryReader = ElasticsearchDirectoryReader.wrap(DirectoryReader.open(writer), new ShardId("foo", "_na_", 1));
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()), nullValue());

        Document doc = new Document();
        doc.add(new Field(IdFieldMapper.NAME, "1", IdFieldMapper.Defaults.FIELD_TYPE));
        doc.add(new NumericDocValuesField(VersionFieldMapper.NAME, 1));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        writer.updateDocument(new Term(IdFieldMapper.NAME, "1"), doc);
        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()).version, equalTo(1L));

        doc = new Document();
        Field uid = new Field(IdFieldMapper.NAME, "1", IdFieldMapper.Defaults.FIELD_TYPE);
        Field version = new NumericDocValuesField(VersionFieldMapper.NAME, 2);
        doc.add(uid);
        doc.add(version);
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        writer.updateDocument(new Term(IdFieldMapper.NAME, "1"), doc);
        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()).version, equalTo(2L));

        // test reuse of uid field
        doc = new Document();
        version.setLongValue(3);
        doc.add(uid);
        doc.add(version);
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        writer.updateDocument(new Term(IdFieldMapper.NAME, "1"), doc);

        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()).version, equalTo(3L));

        writer.deleteDocuments(new Term(IdFieldMapper.NAME, "1"));
        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()), nullValue());
        directoryReader.close();
        writer.close();
        dir.close();
        assertThat(resolver.cacheSize(), equalTo(0));
    }

    public void testNestedDocuments() throws IOException {
        VersionsAndSeqNoResolver resolver = new VersionsAndSeqNoResolver(() -> 0);
        Directory dir = newDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(Lucene.STANDARD_ANALYZER));

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 4; ++i) {
            // Nested
            Document doc = new Document();
            doc.add(new Field(IdFieldMapper.NAME, "1", IdFieldMapper.Defaults.NESTED_FIELD_TYPE));
            docs.add(doc);
        }
        // Root
        Document doc = new Document();
        doc.add(new Field(IdFieldMapper.NAME, "1", IdFieldMapper.Defaults.FIELD_TYPE));
        NumericDocValuesField version = new NumericDocValuesField(VersionFieldMapper.NAME, 5L);
        doc.add(version);
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        docs.add(doc);

        writer.updateDocuments(new Term(IdFieldMapper.NAME, "1"), docs);
        DirectoryReader directoryReader = ElasticsearchDirectoryReader.wrap(DirectoryReader.open(writer), new ShardId("foo", "_na_", 1));
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()).version, equalTo(5L));

        version.setLongValue(6L);
        writer.updateDocuments(new Term(IdFieldMapper.NAME, "1"), docs);
        version.setLongValue(7L);
        writer.updateDocuments(new Term(IdFieldMapper.NAME, "1"), docs);
        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()).version, equalTo(7L));

        writer.deleteDocuments(new Term(IdFieldMapper.NAME, "1"));
        directoryReader = reopen(directoryReader);
        assertThat(resolver.loadDocIdAndVersion(directoryReader, new Term(IdFieldMapper.NAME, "1"), randomBoolean()), nullValue());
        directoryReader.close();
        writer.close();
        dir.close();
    }

    /** Test that version map cache works, is evicted on close, etc */
    public void testCache() throws Exception {
        VersionsAndSeqNoResolver resolver = new VersionsAndSeqNoResolver(() -> 0);
        Directory dir = newDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(Lucene.STANDARD_ANALYZER));
        Document doc = new Document();
        doc.add(new Field(IdFieldMapper.NAME, "6", IdFieldMapper.Defaults.FIELD_TYPE));
        doc.add(new NumericDocValuesField(VersionFieldMapper.NAME, 87));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        writer.addDocument(doc);
        DirectoryReader reader = DirectoryReader.open(writer);
        // should increase cache size by 1
        assertEquals(87, resolver.loadDocIdAndVersion(reader, new Term(IdFieldMapper.NAME, "6"), randomBoolean()).version);
        assertThat(resolver.cacheSize(), equalTo(1));
        // should be cache hit
        assertEquals(87, resolver.loadDocIdAndVersion(reader, new Term(IdFieldMapper.NAME, "6"), randomBoolean()).version);
        assertThat(resolver.cacheSize(), equalTo(1));

        reader.close();
        writer.close();
        // core should be evicted from the map
        assertThat(resolver.cacheSize(), equalTo(0));
        dir.close();
    }

    /** Test that version map cache behaves properly with a filtered reader */
    public void testCacheFilterReader() throws Exception {
        VersionsAndSeqNoResolver resolver = new VersionsAndSeqNoResolver(() -> 0);

        Directory dir = newDirectory();
        IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(Lucene.STANDARD_ANALYZER));
        Document doc = new Document();
        doc.add(new Field(IdFieldMapper.NAME, "6", IdFieldMapper.Defaults.FIELD_TYPE));
        doc.add(new NumericDocValuesField(VersionFieldMapper.NAME, 87));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
        doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
        writer.addDocument(doc);
        DirectoryReader reader = DirectoryReader.open(writer);
        assertEquals(87, resolver.loadDocIdAndVersion(reader, new Term(IdFieldMapper.NAME, "6"), randomBoolean()).version);
        assertThat(resolver.cacheSize(), equalTo(1));
        // now wrap the reader
        DirectoryReader wrapped = ElasticsearchDirectoryReader.wrap(reader, new ShardId("bogus", "_na_", 5));
        assertEquals(87, resolver.loadDocIdAndVersion(wrapped, new Term(IdFieldMapper.NAME, "6"), randomBoolean()).version);
        // same size map: core cache key is shared
        assertThat(resolver.cacheSize(), equalTo(1));

        reader.close();
        writer.close();
        // core should be evicted from the map
        assertThat(resolver.cacheSize(), equalTo(0));
        dir.close();
    }

    public void testLuceneVersionOnUnknownVersions() {
        // between two known versions, should use the lucene version of the previous version
        Version version = VersionUtils.getPreviousVersion(Version.CURRENT);
        assertEquals(Version.fromId(version.id + 100).luceneVersion, version.luceneVersion);

        // too old version, major should be the oldest supported lucene version minus 1
        version = Version.fromString("5.2.1");
        assertEquals(VersionUtils.getFirstVersion().luceneVersion.major - 1, version.luceneVersion.major);

        // future version, should be the same version as today
        version = Version.fromId(Version.CURRENT.id + 100);
        assertEquals(Version.CURRENT.luceneVersion, version.luceneVersion);
    }

    public void testPruneInactiveCachedEntries() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final Directory dir = newDirectory();
        final IndexWriter writer = new IndexWriter(dir, newIndexWriterConfig());
        Set<String> docIds = IntStream.rangeClosed(0, between(1, 100)).mapToObj(Integer::toString).collect(Collectors.toSet());
        for (String docId : docIds) {
            Document doc = new Document();
            doc.add(new Field(IdFieldMapper.NAME, docId, IdFieldMapper.Defaults.FIELD_TYPE));
            doc.add(new NumericDocValuesField(VersionFieldMapper.NAME, randomNonNegativeLong()));
            doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
            doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
            writer.addDocument(doc);
            if (randomInt(100) < 10) {
                writer.flush();
            }
        }
        Set<DirectoryReader> readers = new HashSet<>();
        DirectoryReader reader = DirectoryReader.open(writer);
        readers.add(reader);
        final VersionsAndSeqNoResolver resolver = new VersionsAndSeqNoResolver(clock::get);
        Map<IndexReader.CacheKey, Long> lastAccessedTimePerThread = new HashMap<>();
        int iters = between(10, 100);
        for (int i = 0; i < iters; i++) {
            clock.addAndGet(randomIntBetween(0, 10));
            if (randomBoolean()) {
                Document doc = new Document();
                doc.add(new Field(IdFieldMapper.NAME, randomFrom(docIds), IdFieldMapper.Defaults.FIELD_TYPE));
                doc.add(new NumericDocValuesField(VersionFieldMapper.NAME, randomNonNegativeLong()));
                doc.add(new NumericDocValuesField(SeqNoFieldMapper.NAME, randomNonNegativeLong()));
                doc.add(new NumericDocValuesField(SeqNoFieldMapper.PRIMARY_TERM_NAME, randomLongBetween(1, Long.MAX_VALUE)));
                writer.addDocument(doc);
                reader = DirectoryReader.open(writer);
                assertTrue(readers.add(reader));
            }
            clock.addAndGet(randomIntBetween(0, 10));
            if (randomBoolean()) {
                assertNotNull(resolver.loadDocIdAndSeqNo(reader, new Term(IdFieldMapper.NAME, randomFrom(docIds))));
            } else {
                assertNotNull(resolver.loadDocIdAndVersion(reader, new Term(IdFieldMapper.NAME, randomFrom(docIds)),
                    randomBoolean()));
            }
            lastAccessedTimePerThread.put(reader.getReaderCacheHelper().getKey(), clock.get());
            if (randomBoolean()) {
                for (DirectoryReader closingReader : randomSubsetOf(readers)) {
                    if (closingReader != reader) {
                        closingReader.close();
                        readers.remove(closingReader);
                        lastAccessedTimePerThread.remove(closingReader.getReaderCacheHelper().getKey());
                    }
                }
            }
            if (randomBoolean()) {
                clock.addAndGet(randomIntBetween(0, 10));
                long inactiveInterval = randomIntBetween(0, 10);
                long lastPrunedTime = clock.get() - inactiveInterval;
                resolver.pruneInactiveCachedEntries(TimeValue.timeValueMillis(inactiveInterval));
                lastAccessedTimePerThread.values().removeIf(timestamp -> timestamp <= lastPrunedTime);
            }
            assertThat(resolver.cacheSize(), equalTo(lastAccessedTimePerThread.size()));
        }
        IOUtils.close(readers);
        IOUtils.close(writer, dir);
    }
}
