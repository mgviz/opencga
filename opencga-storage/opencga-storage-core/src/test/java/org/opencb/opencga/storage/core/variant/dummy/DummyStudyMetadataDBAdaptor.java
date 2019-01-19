/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.core.variant.dummy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.adaptors.StudyMetadataDBAdaptor;
import org.opencb.opencga.storage.core.metadata.models.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Created on 28/11/16.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class DummyStudyMetadataDBAdaptor implements StudyMetadataDBAdaptor {

    public static Map<String, StudyConfiguration> STUDY_CONFIGURATIONS_BY_NAME = new ConcurrentHashMap<>();
    public static Map<Integer, StudyConfiguration> STUDY_CONFIGURATIONS_BY_ID = new ConcurrentHashMap<>();
    public static Map<Integer, StudyMetadata> STUDY_METADATA_MAP = new ConcurrentHashMap<>();
    public static Map<Integer, Map<Integer, FileMetadata>> FILE_METADATA_MAP = new ConcurrentHashMap<>();
    public static Map<Integer, Map<Integer, SampleMetadata>> SAMPLE_METADATA_MAP = new ConcurrentHashMap<>();
    public static Map<Integer, Map<Integer, CohortMetadata>> COHORT_METADATA_MAP = new ConcurrentHashMap<>();
    public static Map<Integer, Map<Integer, BatchFileTask>> TASK_METADATA_MAP = new ConcurrentHashMap<>();

    private static Map<Integer, Lock> LOCK_STUDIES = new ConcurrentHashMap<>();
    private static AtomicInteger NUM_PRINTS = new AtomicInteger();

    @Override
    public List<String> getStudyNames(QueryOptions options) {
        return new ArrayList<>(STUDY_CONFIGURATIONS_BY_NAME.keySet());
    }

    @Override
    public List<Integer> getStudyIds(QueryOptions options) {
        return new ArrayList<>(STUDY_CONFIGURATIONS_BY_ID.keySet());
    }

    @Override
    public Map<String, Integer> getStudies(QueryOptions options) {
        return STUDY_CONFIGURATIONS_BY_NAME.values().stream().collect(Collectors.toMap(studyConfiguration -> studyConfiguration.getName(), studyConfiguration1 -> studyConfiguration1.getId()));
    }

    @Override
    public QueryResult<StudyConfiguration> getStudyConfiguration(String studyName, Long time, QueryOptions options) {
        if (STUDY_CONFIGURATIONS_BY_NAME.containsKey(studyName)) {
            return new QueryResult<>("", 0, 1, 1, "", "", Collections.singletonList(STUDY_CONFIGURATIONS_BY_NAME.get(studyName).newInstance()));
        } else {
            return new QueryResult<>("", 0, 0, 0, "", "", Collections.emptyList());
        }
    }

    @Override
    public QueryResult<StudyConfiguration> getStudyConfiguration(int studyId, Long timeStamp, QueryOptions options) {
        if (STUDY_CONFIGURATIONS_BY_ID.containsKey(studyId)) {
            return new QueryResult<>("", 0, 1, 1, "", "", Collections.singletonList(STUDY_CONFIGURATIONS_BY_ID.get(studyId).newInstance()));
        } else {
            return new QueryResult<>("", 0, 0, 0, "", "", Collections.emptyList());
        }
    }

    @Override
    public QueryResult updateStudyConfiguration(StudyConfiguration studyConfiguration, QueryOptions options) {
        STUDY_CONFIGURATIONS_BY_ID.put(studyConfiguration.getId(), studyConfiguration.newInstance());
        STUDY_CONFIGURATIONS_BY_NAME.put(studyConfiguration.getName(), studyConfiguration.newInstance());

        return new QueryResult();

    }

    @Override
    public synchronized long lockStudy(int studyId, long lockDuration, long timeout, String lockName) throws InterruptedException, TimeoutException {
        if (!LOCK_STUDIES.containsKey(studyId)) {
            LOCK_STUDIES.put(studyId, new ReentrantLock());
        }
        LOCK_STUDIES.get(studyId).tryLock(timeout, TimeUnit.MILLISECONDS);

        return studyId;
    }

    @Override
    public void unLockStudy(int studyId, long lockId, String lockName) {
        LOCK_STUDIES.get(studyId).unlock();
    }

    @Override
    public StudyMetadata getStudyMetadata(int id, Long timeStamp) {
        return STUDY_METADATA_MAP.get(id);
    }

    @Override
    public void updateStudyMetadata(StudyMetadata sm) {
        STUDY_METADATA_MAP.put(sm.getId(), sm);
    }

    @Override
    public LinkedHashSet<Integer> getIndexedFiles(int studyId) {
        return new LinkedHashSet<>(FILE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values()
                .stream()
                .filter(FileMetadata::isIndexed)
                .map(FileMetadata::getId)
                .collect(Collectors.toList()));
    }

    @Override
    public Iterator<FileMetadata> fileIterator(int studyId) {
        return FILE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values().iterator();
    }

//    @Override
//    public void updateIndexedFiles(int studyId, LinkedHashSet<Integer> indexedFiles) {
//
//    }

    @Override
    public FileMetadata getFileMetadata(int studyId, int fileId, Long timeStamp) {
        return FILE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).get(fileId);
    }

    @Override
    public void updateFileMetadata(int studyId, FileMetadata file, Long timeStamp) {
        FILE_METADATA_MAP.computeIfAbsent(studyId, s -> new ConcurrentHashMap<>()).put(file.getId(), file);
    }

    @Override
    public Integer getFileId(int studyId, String fileName) {
        return FILE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values()
                .stream()
                .filter(f->f.getName().equals(fileName))
                .map(FileMetadata::getId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public SampleMetadata getSampleMetadata(int studyId, int sampleId, Long timeStamp) {
        return SAMPLE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).get(sampleId);
    }

    @Override
    public void updateSampleMetadata(int studyId, SampleMetadata sample, Long timeStamp) {
        SAMPLE_METADATA_MAP.computeIfAbsent(studyId, s -> new ConcurrentHashMap<>()).put(sample.getId(), sample);
    }

    @Override
    public Iterator<SampleMetadata> sampleMetadataIterator(int studyId) {
        return SAMPLE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values().iterator();
    }

    @Override
    public Integer getSampleId(int studyId, String sampleName) {
        return SAMPLE_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values()
                .stream()
                .filter(f->f.getName().equals(sampleName))
                .map(SampleMetadata::getId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public CohortMetadata getCohortMetadata(int studyId, int cohortId, Long timeStamp) {
        return COHORT_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).get(cohortId);
    }

    @Override
    public void updateCohortMetadata(int studyId, CohortMetadata cohort, Long timeStamp) {
        COHORT_METADATA_MAP.computeIfAbsent(studyId, s -> new ConcurrentHashMap<>()).put(cohort.getId(), cohort);
    }

    @Override
    public Integer getCohortId(int studyId, String cohortName) {
        return COHORT_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values()
                .stream()
                .filter(f->f.getName().equals(cohortName))
                .map(CohortMetadata::getId)
                .findFirst()
                .orElse(null);
    }

    @Override
    public Iterator<CohortMetadata> cohortIterator(int studyId) {
        return COHORT_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values().iterator();
    }

    @Override
    public BatchFileTask getTask(int studyId, int taskId, Long timeStamp) {
        return TASK_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).get(taskId);
    }

    @Override
    public Iterator<BatchFileTask> taskIterator(int studyId, boolean reversed) {
        TreeSet<BatchFileTask> t;
        if (reversed) {
            t = new TreeSet<>(Comparator.comparingInt(BatchFileTask::getId).reversed());
        } else {
            t = new TreeSet<>(Comparator.comparingInt(BatchFileTask::getId));
        }

        t.addAll(TASK_METADATA_MAP.getOrDefault(studyId, Collections.emptyMap()).values());
        return t.iterator();
    }

    @Override
    public void updateTask(int studyId, BatchFileTask task, Long timeStamp) {
        TASK_METADATA_MAP.computeIfAbsent(studyId, s -> new ConcurrentHashMap<>()).put(task.getId(), task);
    }

    public static void writeAll(Path path) {
        ObjectMapper objectMapper = new ObjectMapper(new JsonFactory()).configure(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS, true);
        String prefix = "storage_configuration_" + NUM_PRINTS.incrementAndGet() + "_";
        for (StudyConfiguration studyConfiguration : DummyStudyMetadataDBAdaptor.STUDY_CONFIGURATIONS_BY_NAME.values()) {
            try (OutputStream os = new FileOutputStream(path.resolve(prefix + studyConfiguration.getName() + ".json").toFile())) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(os, studyConfiguration);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static void clear() {
        STUDY_CONFIGURATIONS_BY_NAME.clear();
        STUDY_CONFIGURATIONS_BY_ID.clear();
        STUDY_METADATA_MAP.clear();
        FILE_METADATA_MAP.clear();
        SAMPLE_METADATA_MAP.clear();
        COHORT_METADATA_MAP.clear();
        TASK_METADATA_MAP.clear();
        LOCK_STUDIES.clear();
    }

    public static synchronized void writeAndClear(Path path) {
        writeAll(path);
        clear();
    }

    @Override
    public void close() {
    }
}