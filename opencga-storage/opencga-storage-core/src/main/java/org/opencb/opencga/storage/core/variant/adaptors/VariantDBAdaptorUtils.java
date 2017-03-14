/*
 * Copyright 2015-2016 OpenCB
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

package org.opencb.opencga.storage.core.variant.adaptors;

import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.core.Gene;
import org.opencb.biodata.models.core.Region;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.annotation.ConsequenceTypeMappings;
import org.opencb.cellbase.core.api.GeneDBAdaptor;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryParam;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.StudyConfigurationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor.VariantQueryParams.*;

/**
 * Created on 29/01/16 .
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantDBAdaptorUtils {

    public static final Pattern OPERATION_PATTERN = Pattern.compile("^([^=<>~!]*)(<=?|>=?|!=?|!?=?~|==?)([^=<>~!]+.*)$");
    private static final Pattern GENOTYPE_FILTER_PATTERN = Pattern.compile("(?<sample>[^,;]+):(?<gts>([^:;,]+,?)+)(?<op>[;,.])");

    public static final String OR = ",";
    public static final String AND = ";";
    public static final String IS = ":";
    public static final String STUDY_POP_FREQ_SEPARATOR = ":";

    public static final String NONE = "none";
    public static final String ALL = "all";

    private static final int GENE_EXTRA_REGION = 5000;
    private static Logger logger = LoggerFactory.getLogger(VariantDBAdaptorUtils.class);

    private VariantDBAdaptor adaptor;

    public enum QueryOperation {
        AND(VariantDBAdaptorUtils.AND),
        OR(VariantDBAdaptorUtils.OR);

        private final String separator;

        QueryOperation(String separator) {
            this.separator = separator;
        }

        public String separator() {
            return separator;
        }
    }

    public VariantDBAdaptorUtils(VariantDBAdaptor variantDBAdaptor) {
        adaptor = variantDBAdaptor;
    }

    /**
     * Check if the object query contains the value param, is not null and, if is an string or a list, is not empty.
     *
     * isValidParam(new Query(), PARAM) == false
     * isValidParam(new Query(PARAM.key(), null), PARAM) == false
     * isValidParam(new Query(PARAM.key(), ""), PARAM) == false
     * isValidParam(new Query(PARAM.key(), Collections.emptyList()), PARAM) == false
     * isValidParam(new Query(PARAM.key(), 5), PARAM) == true
     * isValidParam(new Query(PARAM.key(), "sdfas"), PARAM) == true
     *
     * @param query Query to parse
     * @param param QueryParam to check
     * @return If is valid or not
     */
    public static boolean isValidParam(Query query, QueryParam param) {
        Object value = query.getOrDefault(param.key(), null);
        return (value != null)
                && !(value instanceof String && ((String) value).isEmpty()
                || value instanceof Collection && ((Collection) value).isEmpty());
    }

    /**
     * Determines if the filter is negated.
     *
     * @param value Value to check
     * @return If the value is negated
     */
    public static boolean isNegated(String value) {
        return value.startsWith("!");
    }

    public static boolean isNoneOrAll(String value) {
        return value.equals(NONE) || value.equals(ALL);
    }

    /**
     * Determines if the given value is a known variant accession or not.
     *
     * @param value Value to check
     * @return      If is a known accession
     */
    public static boolean isVariantAccession(String value) {
        return value.startsWith("rs") || value.startsWith("VAR_");
    }

    /**
     * Determines if the given value is a known clinical accession or not.
     *
     * ClinVar accession starts with 'RCV'
     * COSMIC mutationId starts with 'COSM'
     *
     * @param value Value to check
     * @return      If is a known accession
     */
    public static boolean isClinicalAccession(String value) {
        return value.startsWith("RCV") || value.startsWith("COSM");
    }

    /**
     * Determines if the given value is a known gene accession or not.
     *
     * Human Phenotype Ontology (HPO) terms starts with 'HP:'
     * Online Mendelian Inheritance in Man (OMIM) terms starts with 'OMIM:'
     *
     * @param value Value to check
     * @return      If is a known accession
     */
    public static boolean isGeneAccession(String value) {
        return value.startsWith("HP:") || value.startsWith("OMIM:");
    }

    /**
     * Determines if the given value is a variant id or not.
     *
     * chr:pos:ref:alt
     *
     * @param value Value to check
     * @return      If is a variant id
     */
    public static boolean isVariantId(String value) {
        int count = StringUtils.countMatches(value, ':');
        return count == 3;
    }

    /**
     * Determines if the given value is a variant id or not.
     *
     * chr:pos:ref:alt
     *
     * @param value Value to check
     * @return      If is a variant id
     */
    public static Variant toVariant(String value) {
        Variant variant = null;
        if (isVariantId(value)) {
            if (value.contains(":")) {
                try {
                    variant = new Variant(value);
                } catch (IllegalArgumentException ignore) {
                    variant = null;
                    // TODO: Should this throw an exception?
                    logger.info("Wrong variant " + value, ignore);
                }
            }
        }
        return variant;
    }

    public StudyConfigurationManager getStudyConfigurationManager() {
        return adaptor.getStudyConfigurationManager();
    }

    public List<Integer> getStudyIds(QueryOptions options) {
        return getStudyConfigurationManager().getStudyIds(options);
    }

    /**
     * Get studyIds from a list of studies.
     * Replaces studyNames for studyIds.
     * Excludes those studies that starts with '!'
     *
     * @param studiesNames  List of study names or study ids
     * @param options       Options
     * @return              List of study Ids
     */
    public List<Integer> getStudyIds(List studiesNames, QueryOptions options) {
        return getStudyIds(studiesNames, getStudyConfigurationManager().getStudies(options));
    }

    /**
     * Get studyIds from a list of studies.
     * Replaces studyNames for studyIds.
     * Excludes those studies that starts with '!'
     *
     * @param studiesNames  List of study names or study ids
     * @param studies       Map of available studies. See {@link StudyConfigurationManager#getStudies}
     * @return              List of study Ids
     */
    public List<Integer> getStudyIds(List studiesNames, Map<String, Integer> studies) {
        List<Integer> studiesIds;
        if (studiesNames == null) {
            return Collections.emptyList();
        }
        studiesIds = new ArrayList<>(studiesNames.size());
        for (Object studyObj : studiesNames) {
            Integer studyId = getStudyId(studyObj, true, studies);
            if (studyId != null) {
                studiesIds.add(studyId);
            }
        }
        return studiesIds;
    }

    public Integer getStudyId(Object studyObj, QueryOptions options) {
        return getStudyId(studyObj, options, true);
    }

    public Integer getStudyId(Object studyObj, QueryOptions options, boolean skipNegated) {
        if (studyObj instanceof Integer) {
            return ((Integer) studyObj);
        } else if (studyObj instanceof String && StringUtils.isNumeric((String) studyObj)) {
            return Integer.parseInt((String) studyObj);
        } else {
            return getStudyId(studyObj, skipNegated, getStudyConfigurationManager().getStudies(options));
        }
    }

    public Integer getStudyId(Object studyObj, boolean skipNegated, Map<String, Integer> studies) {
        Integer studyId;
        if (studyObj instanceof Integer) {
            studyId = ((Integer) studyObj);
        } else {
            String studyName = studyObj.toString();
            if (isNegated(studyName)) { //Skip negated studies
                if (skipNegated) {
                    return null;
                } else {
                    studyName = studyName.substring(1);
                }
            }
            if (StringUtils.isNumeric(studyName)) {
                studyId = Integer.parseInt(studyName);
            } else {
                Integer value = studies.get(studyName);
                if (value == null) {
                    throw VariantQueryException.studyNotFound(studyName, studies.keySet());
                }
                studyId = value;
            }
        }
        if (!studies.containsValue(studyId)) {
            throw VariantQueryException.studyNotFound(studyId, studies.keySet());
        }
        return studyId;
    }

    public StudyConfiguration getDefaultStudyConfiguration(Query query, QueryOptions options) {
        final StudyConfiguration defaultStudyConfiguration;
        if (isValidParam(query, VariantDBAdaptor.VariantQueryParams.STUDIES)) {
            String value = query.getString(VariantDBAdaptor.VariantQueryParams.STUDIES.key());

            // Check that the study exists
            QueryOperation studiesOperation = checkOperator(value);
            List<String> studiesNames = splitValue(value, studiesOperation);
            List<Integer> studyIds = getStudyIds(studiesNames, options); // Non negated studyIds


            if (studyIds.size() == 1) {
                defaultStudyConfiguration = getStudyConfigurationManager().getStudyConfiguration(studyIds.get(0), null).first();
            } else {
                defaultStudyConfiguration = null;
            }

        } else {
            List<String> studyNames = getStudyConfigurationManager().getStudyNames(null);
            if (studyNames != null && studyNames.size() == 1) {
                defaultStudyConfiguration = getStudyConfigurationManager().getStudyConfiguration(studyNames.get(0), null).first();
            } else {
                defaultStudyConfiguration = null;
            }
        }
        return defaultStudyConfiguration;
    }

    /**
     * Given a study reference (name or id) and a default study, returns the associated StudyConfiguration.
     *
     * @param study     Study reference (name or id)
     * @param defaultStudyConfiguration Default studyConfiguration
     * @return          Assiciated StudyConfiguration
     * @throws    VariantQueryException is the study does not exists
     */
    public StudyConfiguration getStudyConfiguration(String study, StudyConfiguration defaultStudyConfiguration)
            throws VariantQueryException {
        StudyConfiguration studyConfiguration;
        if (StringUtils.isEmpty(study)) {
            studyConfiguration = defaultStudyConfiguration;
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(study, getStudyConfigurationManager().getStudyNames(null));
            }
        } else if (StringUtils.isNumeric(study)) {
            int studyInt = Integer.parseInt(study);
            if (defaultStudyConfiguration != null && studyInt == defaultStudyConfiguration.getStudyId()) {
                studyConfiguration = defaultStudyConfiguration;
            } else {
                studyConfiguration = getStudyConfigurationManager().getStudyConfiguration(studyInt, null).first();
            }
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(studyInt, getStudyConfigurationManager().getStudyNames(null));
            }
        } else {
            if (defaultStudyConfiguration != null && defaultStudyConfiguration.getStudyName().equals(study)) {
                studyConfiguration = defaultStudyConfiguration;
            } else {
                studyConfiguration = getStudyConfigurationManager().getStudyConfiguration(study, null).first();
            }
            if (studyConfiguration == null) {
                throw VariantQueryException.studyNotFound(study, getStudyConfigurationManager().getStudyNames(null));
            }
        }
        return studyConfiguration;
    }

    public List<Integer> getFileIds(List files, boolean skipNegated, StudyConfiguration defaultStudyConfiguration) {
        List<Integer> fileIds;
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        fileIds = new ArrayList<>(files.size());
        for (Object fileObj : files) {
            Integer fileId = getFileId(fileObj, skipNegated, defaultStudyConfiguration);
            if (fileId != null) {
                fileIds.add(fileId);
            }
        }
        return fileIds;
    }

    public Integer getFileId(Object fileObj, boolean skipNegated, StudyConfiguration defaultStudyConfiguration) {
        if (fileObj == null) {
            return null;
        } else if (fileObj instanceof Number) {
            return ((Number) fileObj).intValue();
        } else {
            String file = String.valueOf(fileObj);
            if (isNegated(file)) { //Skip negated studies
                if (skipNegated) {
                    return null;
                } else {
                    file = file.substring(1);
                }
            }
            if (file.contains(":")) {
                String[] studyFile = file.split(":");
                QueryResult<StudyConfiguration> queryResult = getStudyConfigurationManager().getStudyConfiguration(studyFile[0], null);
                if (queryResult.getResult().isEmpty()) {
                    throw VariantQueryException.studyNotFound(studyFile[0]);
                }
                return queryResult.first().getFileIds().get(studyFile[1]);
            } else {
                try {
                    return Integer.parseInt(file);
                } catch (NumberFormatException e) {
                    if (defaultStudyConfiguration != null) {
                        return defaultStudyConfiguration.getFileIds().get(file);
                    } else {
                        List<String> studyNames = getStudyConfigurationManager().getStudyNames(null);
                        throw new VariantQueryException("Unknown file \"" + file + "\". "
                                + "Please, specify the study belonging."
                                + (studyNames == null ? "" : " Available studies: " + studyNames));
                    }
                }
            }
        }
    }

    public int getSampleId(Object sampleObj, StudyConfiguration defaultStudyConfiguration) {
        int sampleId;
        if (sampleObj instanceof Number) {
            sampleId = ((Number) sampleObj).intValue();
        } else {
            String sampleStr = sampleObj.toString();
            if (StringUtils.isNumeric(sampleStr)) {
                sampleId = Integer.parseInt(sampleStr);
            } else {
                if (sampleStr.contains(":")) {  //Expect to be as <study>:<sample>
                    String[] split = sampleStr.split(":");
                    String study = split[0];
                    sampleStr= split[1];
                    StudyConfiguration sc;
                    if (defaultStudyConfiguration != null && study.equals(defaultStudyConfiguration.getStudyName())) {
                        sc = defaultStudyConfiguration;
                    } else {
                        QueryResult<StudyConfiguration> queryResult = getStudyConfigurationManager().getStudyConfiguration(study, null);
                        if (queryResult.getResult().isEmpty()) {
                            throw VariantQueryException.studyNotFound(study);
                        }
                        if (!queryResult.first().getSampleIds().containsKey(sampleStr)) {
                            throw VariantQueryException.sampleNotFound(sampleStr, study);
                        }
                        sc = queryResult.first();
                    }
                    sampleId = sc.getSampleIds().get(sampleStr);
                } else if (defaultStudyConfiguration != null) {
                    if (!defaultStudyConfiguration.getSampleIds().containsKey(sampleStr)) {
                        throw VariantQueryException.sampleNotFound(sampleStr, defaultStudyConfiguration.getStudyName());
                    }
                    sampleId = defaultStudyConfiguration.getSampleIds().get(sampleStr);
                } else {
                    //Unable to identify that sample!
                    List<String> studyNames = getStudyConfigurationManager().getStudyNames(null);
                    throw VariantQueryException.missingStudyForSample(sampleStr, studyNames);
                }
            }
        }
        return sampleId;
    }

    public List<Integer> getReturnedStudies(Query query, QueryOptions options) {
        Set<VariantField> returnedFields = VariantField.getReturnedFields(options);
        List<Integer> studyIds;
        if (!returnedFields.contains(VariantField.STUDIES)) {
            studyIds = Collections.emptyList();
        } else if (isValidParam(query, RETURNED_STUDIES)) {
            String returnedStudies = query.getString(VariantDBAdaptor.VariantQueryParams.RETURNED_STUDIES.key());
            if (NONE.equals(returnedStudies)) {
                studyIds = Collections.emptyList();
            } else if (ALL.equals(returnedStudies)) {
                studyIds = getStudyConfigurationManager().getStudyIds(options);
            } else {
                studyIds = getStudyIds(query.getAsList(VariantDBAdaptor.VariantQueryParams.RETURNED_STUDIES.key()), options);
            }
        } else if (isValidParam(query, STUDIES)) {
            String studies = query.getString(VariantDBAdaptor.VariantQueryParams.STUDIES.key());
            studyIds = getStudyIds(splitValue(studies, checkOperator(studies)), options);
            // if empty, all the studies
            if (studyIds.isEmpty()) {
                studyIds = getStudyConfigurationManager().getStudyIds(options);
            }
        } else {
            studyIds = getStudyConfigurationManager().getStudyIds(options);
        }
        return studyIds;
    }

    /**
     * Get list of returned files.
     *
     * Use {@link VariantDBAdaptor.VariantQueryParams#RETURNED_FILES} if defined.
     * If missing, get non negated values from {@link VariantDBAdaptor.VariantQueryParams#FILES}
     * If missing, get files from samples at {@link VariantDBAdaptor.VariantQueryParams#SAMPLES}
     *
     * Null for undefined returned files. If null, return ALL files.
     * Return NONE if empty list
     *
     *
     * @param query     Query with the QueryParams
     * @param options   Query options
     * @param fields    Returned fields
     * @return          List of fileIds to return.
     */
    public List<Integer> getReturnedFiles(Query query, QueryOptions options, Set<VariantField> fields) {
        List<Integer> returnedFiles;
        if (!fields.contains(VariantField.STUDIES_FILES)) {
            returnedFiles = Collections.emptyList();
        } else if (query.containsKey(RETURNED_FILES.key())) {
            String files = query.getString(RETURNED_FILES.key());
            if (files.equals(ALL)) {
                returnedFiles = null;
            } else if (files.equals(NONE)) {
                returnedFiles = Collections.emptyList();
            } else {
                returnedFiles = query.getAsIntegerList(RETURNED_FILES.key());
            }
        } else if (query.containsKey(FILES.key())) {
            String files = query.getString(FILES.key());
            returnedFiles = splitValue(files, checkOperator(files))
                    .stream()
                    .filter((value) -> !isNegated(value)) // Discard negated
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
            if (returnedFiles.isEmpty()) {
                returnedFiles = null;
            }
        } else {
            List<String> sampleNames = query.getAsStringList(VariantDBAdaptor.VariantQueryParams.SAMPLES.key());
            StudyConfiguration studyConfiguration = getDefaultStudyConfiguration(query, options);
            Set<Integer> returnedFilesSet = new LinkedHashSet<>();
            for (String sample : sampleNames) {
                Integer sampleId = getSampleId(sample, studyConfiguration);
                studyConfiguration.getSamplesInFiles().forEach((fileId, samples) -> {
                    if (samples.contains(sampleId)) {
                        returnedFilesSet.add(fileId);
                    }
                });
            }
            returnedFiles = new ArrayList<>(returnedFilesSet);
            if (returnedFiles.isEmpty()) {
                returnedFiles = null;
            }
        }
        return returnedFiles;
    }

    public static boolean isReturnedSamplesDefined(Query query, Set<VariantField> returnedFields) {
        if (getReturnedSamplesList(query, returnedFields) != null) {
            return true;
        } else if (isValidParam(query, FILES)) {
            String files = query.getString(FILES.key());
            return splitValue(files, checkOperator(files))
                    .stream()
                    .anyMatch((value) -> !isNegated(value)); // Discard negated
        }
        return false;
    }

    public Map<String, List<String>> getSamplesMetadata(Query query) {
        List<Integer> returnedStudies = getReturnedStudies(query, null);
        Function<Integer, StudyConfiguration> studyProvider = studyId -> getStudyConfigurationManager()
                .getStudyConfiguration(studyId, null).first();
        return getReturnedSamples(query, null, returnedStudies, studyProvider, (sc, s) -> s, StudyConfiguration::getStudyName);
    }

    public static Map<String, List<String>> getSamplesMetadata(Query query, StudyConfiguration studyConfiguration) {
        List<Integer> returnedStudies = Collections.singletonList(studyConfiguration.getStudyId());
        Function<Integer, StudyConfiguration> studyProvider = studyId -> studyConfiguration;
        return getReturnedSamples(query, null, returnedStudies, studyProvider, (sc, s) -> s, StudyConfiguration::getStudyName);
    }

    public Map<String, List<String>> getSamplesMetadata(Query query, QueryOptions options) {
        if (query.getBoolean(SAMPLES_METADATA.key(), false)) {
            if (VariantField.getReturnedFields(options).contains(VariantField.STUDIES)) {
                List<Integer> returnedStudies = getReturnedStudies(query, options);
                Function<Integer, StudyConfiguration> studyProvider = studyId -> getStudyConfigurationManager()
                        .getStudyConfiguration(studyId, options).first();
                return getReturnedSamples(query, options, returnedStudies, studyProvider, (sc, s) -> s, StudyConfiguration::getStudyName);
            } else {
                return Collections.emptyMap();
            }
        } else {
            return null;
        }
    }

    public Map<Integer, List<Integer>> getReturnedSamples(Query query, QueryOptions options) {
        List<Integer> returnedStudies = getReturnedStudies(query, options);
        return getReturnedSamples(query, options, returnedStudies, studyId -> getStudyConfigurationManager()
                .getStudyConfiguration(studyId, options).first());
    }

    public static Map<Integer, List<Integer>> getReturnedSamples(Query query, QueryOptions options,
                                                                 Collection<StudyConfiguration> studies) {
        Map<Integer, StudyConfiguration> map = studies.stream()
                .collect(Collectors.toMap(StudyConfiguration::getStudyId, Function.identity()));
        return getReturnedSamples(query, options, map.keySet(), map::get);
    }

    public static Map<Integer, List<Integer>> getReturnedSamples(Query query, QueryOptions options, Collection<Integer> studyIds,
                                                                               Function<Integer, StudyConfiguration> studyProvider) {
        return getReturnedSamples(query, options, studyIds, studyProvider, (sc, s) -> sc.getSampleIds().get(s),
                StudyConfiguration::getStudyId);
    }

    private static <T> Map<T, List<T>> getReturnedSamples(
            Query query, QueryOptions options, Collection<Integer> studyIds,
            Function<Integer, StudyConfiguration> studyProvider,
            BiFunction<StudyConfiguration, String, T> getSample, Function<StudyConfiguration, T> getStudyId) {

        List<Integer> fileIds = null;
        if (isValidParam(query, FILES)) {
            String files = query.getString(FILES.key());
            fileIds = splitValue(files, checkOperator(files))
                    .stream()
                    .filter((value) -> !isNegated(value)) // Discard negated
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        }

        List<String> returnedSamples = getReturnedSamplesList(query, options);
        LinkedHashSet<String> returnedSamplesSet = returnedSamples != null ? new LinkedHashSet<>(returnedSamples) : null;
        boolean returnAllSamples = query.getString(VariantDBAdaptor.VariantQueryParams.RETURNED_SAMPLES.key()).equals(ALL);

        Map<T, List<T>> samples = new HashMap<>(studyIds.size());
        for (Integer studyId : studyIds) {
            StudyConfiguration sc = studyProvider.apply(studyId);
            if (sc == null) {
                continue;
            }

            List<T> sampleNames;
            if (returnedSamplesSet != null || returnAllSamples || fileIds == null) {
                LinkedHashMap<String, Integer> returnedSamplesPosition
                        = StudyConfiguration.getReturnedSamplesPosition(sc, returnedSamplesSet);
                @SuppressWarnings("unchecked")
                T[] a = (T[]) new Object[returnedSamplesPosition.size()];
                sampleNames = Arrays.asList(a);
                returnedSamplesPosition.forEach((sample, position) -> {
                    sampleNames.set(position, getSample.apply(sc, sample));
                });
            } else {
                Set<T> sampleSet = new LinkedHashSet<>();
                for (Integer fileId : fileIds) {
                    LinkedHashSet<Integer> sampleIds = sc.getSamplesInFiles().get(fileId);
                    if (sampleIds != null) {
                        for (Integer sampleId : sampleIds) {
                            sampleSet.add(getSample.apply(sc, sc.getSampleIds().inverse().get(sampleId)));
                        }
                    }
                }
                sampleNames = new ArrayList<T>(sampleSet);
            }
            samples.put(getStudyId.apply(sc), sampleNames);
        }

        return samples;
    }

    public static List<String> getReturnedSamplesList(Query query, QueryOptions options) {
        return getReturnedSamplesList(query, VariantField.getReturnedFields(options));
    }

    public static List<String> getReturnedSamplesList(Query query, Set<VariantField> returnedFields) {
        List<String> samples;
        if (!returnedFields.contains(VariantField.STUDIES_SAMPLES_DATA)) {
            samples = Collections.emptyList();
        } else {
            //Remove the studyName, if any
            samples = getReturnedSamplesList(query);
        }
        return samples;
    }

    /**
     * Get list of returned samples.
     *
     * Null for undefined returned samples. If null, return ALL samples.
     * Return NONE if empty list
     *
     *
     * @param query     Query with the QueryParams
     * @return          List of samples to return.
     */
    public static List<String> getReturnedSamplesList(Query query) {
        List<String> samples;
        if (isValidParam(query, RETURNED_SAMPLES)) {
            String samplesString = query.getString(VariantDBAdaptor.VariantQueryParams.RETURNED_SAMPLES.key());
            if (samplesString.equals(ALL)) {
                samples = null; // Undefined. All by default
            } else if (samplesString.equals(NONE)) {
                samples = Collections.emptyList();
            } else {
                samples = query.getAsStringList(VariantDBAdaptor.VariantQueryParams.RETURNED_SAMPLES.key());
            }
        } else if (isValidParam(query, SAMPLES)) {
            samples = query.getAsStringList(VariantDBAdaptor.VariantQueryParams.SAMPLES.key());
        } else {
            samples = null;
        }
        if (samples != null) {
            samples.stream()
                    .map(s -> s.contains(":") ? s.split(":")[1] : s)
                    .collect(Collectors.toList());
        }
        return samples;
    }


    /**
     * Partes the genotype filter.
     *
     * @param sampleGenotypes   Genotypes filter value
     * @param map               Initialized map to be filled with the sample to list of genotypes
     * @return QueryOperation between samples
     */
    public static QueryOperation parseGenotypeFilter(String sampleGenotypes, Map<Object, List<String>> map) {
        Matcher matcher = GENOTYPE_FILTER_PATTERN.matcher(sampleGenotypes + '.');

        QueryOperation operation = null;
        while (matcher.find()) {
            String gts = matcher.group("gts");
            String sample = matcher.group("sample");
            String op = matcher.group("op");
            map.put(sample, Arrays.asList(gts.split(",")));
            if (AND.equals(op)) {
                if (operation == QueryOperation.OR) {
                    throw VariantQueryException.malformedParam(GENOTYPE, sampleGenotypes,
                            "Unable to mix AND (" + AND + ") and OR (" + OR + ") in the same query.");
                } else {
                    operation = QueryOperation.AND;
                }
            } else if (OR.equals(op)) {
                if (operation == QueryOperation.AND) {
                    throw VariantQueryException.malformedParam(GENOTYPE, sampleGenotypes,
                            "Unable to mix AND (" + AND + ") and OR (" + OR + ") in the same query.");
                } else {
                    operation = QueryOperation.OR;
                }
            }
        }

        return operation;
    }

    /**
     * Finds the cohortId from a cohort reference.
     *
     * @param cohort    Cohort reference (name or id)
     * @param studyConfiguration  Default study configuration
     * @return  Cohort id
     * @throws VariantQueryException if the cohort does not exist
     */
    public int getCohortId(String cohort, StudyConfiguration studyConfiguration) throws VariantQueryException {
        int cohortId;
        if (StringUtils.isNumeric(cohort)) {
            cohortId = Integer.parseInt(cohort);
            if (!studyConfiguration.getCohortIds().containsValue(cohortId)) {
                throw VariantQueryException.cohortNotFound(cohortId, studyConfiguration.getStudyId(),
                        studyConfiguration.getCohortIds().keySet());
            }
        } else {
            Integer cohortIdNullable = studyConfiguration.getCohortIds().get(cohort);
            if (cohortIdNullable == null) {
                throw VariantQueryException.cohortNotFound(cohort, studyConfiguration.getStudyId(),
                        studyConfiguration.getCohortIds().keySet());
            }
            cohortId = cohortIdNullable;
        }
        return cohortId;
    }

    public Region getGeneRegion(String geneStr) {
        QueryOptions params = new QueryOptions(QueryOptions.INCLUDE, "name,chromosome,start,end");
        try {
            Gene gene = adaptor.getCellBaseClient().getGeneClient().get(Collections.singletonList(geneStr), params).firstResult();
            if (gene != null) {
                int start = Math.max(0, gene.getStart() - GENE_EXTRA_REGION);
                int end = gene.getEnd() + GENE_EXTRA_REGION;
                return new Region(gene.getChromosome(), start, end);
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Set<String> getGenesByGo(List<String> goValues) {
        Set<String> genes = new HashSet<>();
        QueryOptions params = new QueryOptions(QueryOptions.INCLUDE, "name,chromosome,start,end");
        try {
            List<QueryResult<Gene>> responses = adaptor.getCellBaseClient().getGeneClient().get(goValues, params)
                    .getResponse();
            for (QueryResult<Gene> response : responses) {
                for (Gene gene : response.getResult()) {
                    genes.add(gene.getName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return genes;
    }

    public Set<String> getGenesByExpression(List<String> expressionValues) {
        Set<String> genes = new HashSet<>();
        QueryOptions params = new QueryOptions(QueryOptions.INCLUDE, "name,chromosome,start,end");

        // The number of results for each expression value may be huge. Query one by one
        for (String expressionValue : expressionValues) {
            try {
                String[] split = expressionValue.split(":");
                expressionValue = split[0];
                // TODO: Add expression value {UP, DOWN}. See https://github.com/opencb/cellbase/issues/245
                Query cellbaseQuery = new Query(GeneDBAdaptor.QueryParams.ANNOTATION_EXPRESSION_TISSUE.key(), expressionValue);
                List<QueryResult<Gene>> responses = adaptor.getCellBaseClient().getGeneClient().search(cellbaseQuery, params)
                        .getResponse();
                for (QueryResult<Gene> response : responses) {
                    for (Gene gene : response.getResult()) {
                        genes.add(gene.getName());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return genes;
    }

    public static int parseConsequenceType(String so) {
        int soAccession;
        boolean startsWithSO = so.toUpperCase().startsWith("SO:");
        if (startsWithSO || StringUtils.isNumeric(so)) {
            try {
                if (startsWithSO) {
                    soAccession = Integer.parseInt(so.substring("SO:".length()));
                } else {
                    soAccession = Integer.parseInt(so);
                }
            } catch (NumberFormatException e) {
                throw VariantQueryException.malformedParam(VariantDBAdaptor.VariantQueryParams.ANNOT_CONSEQUENCE_TYPE, so,
                        "Not a valid SO number");
            }
            if (!ConsequenceTypeMappings.accessionToTerm.containsKey(soAccession)) {
                throw VariantQueryException.malformedParam(VariantDBAdaptor.VariantQueryParams.ANNOT_CONSEQUENCE_TYPE, so,
                        "Not a valid SO number");
            }
        } else {
            if (!ConsequenceTypeMappings.termToAccession.containsKey(so)) {
                throw VariantQueryException.malformedParam(VariantDBAdaptor.VariantQueryParams.ANNOT_CONSEQUENCE_TYPE, so,
                        "Not a valid Accession term");
            } else {
                soAccession = ConsequenceTypeMappings.termToAccession.get(so);
            }
        }
        return soAccession;
    }

    /**
     * Checks that the filter value list contains only one type of operations.
     *
     * @param value List of values to check
     * @return  The used operator. Null if no operator is used.
     * @throws VariantQueryException if the list contains different operators.
     */
    public static QueryOperation checkOperator(String value) throws VariantQueryException {
        boolean containsOr = value.contains(OR);
        boolean containsAnd = value.contains(AND);
        if (containsAnd && containsOr) {
            throw new VariantQueryException("Can't merge in the same query filter, AND and OR operators");
        } else if (containsAnd) {   // && !containsOr  -> true
            return QueryOperation.AND;
        } else if (containsOr) {    // && !containsAnd  -> true
            return QueryOperation.OR;
        } else {    // !containsOr && !containsAnd
            return null;
        }
    }

    /**
     * Splits the string with the specified operation.
     *
     * @param value     Value to split
     * @param operation Operation that defines the split delimiter
     * @return          List of values, without the delimiter
     */
    public static List<String> splitValue(String value, QueryOperation operation) {
        List<String> list;
        if (value == null || value.isEmpty()) {
            list = Collections.emptyList();
        } else if (operation == null) {
            list = Collections.singletonList(value);
        } else if (operation == QueryOperation.AND) {
            list = Arrays.asList(value.split(QueryOperation.AND.separator()));
        } else {
            list = Arrays.asList(value.split(QueryOperation.OR.separator()));
        }
        return list;
    }

    public static String[] splitOperator(String value) {
        Matcher matcher = OPERATION_PATTERN.matcher(value);
        String key;
        String operator;
        String filter;

        if (matcher.find()) {
            key = matcher.group(1);
            operator = matcher.group(2);
            filter = matcher.group(3);
        } else {
            return new String[]{null, "=", value};
        }

        return new String[]{key.trim(), operator.trim(), filter.trim()};
    }

}
