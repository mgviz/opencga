package org.opencb.opencga.server.rest.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.annotations.*;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.opencb.biodata.models.clinical.interpretation.Comment;
import org.opencb.biodata.models.clinical.interpretation.DiseasePanel;
import org.opencb.biodata.models.clinical.interpretation.ReportedLowCoverage;
import org.opencb.biodata.models.clinical.interpretation.ReportedVariant;
import org.opencb.biodata.models.commons.Analyst;
import org.opencb.biodata.models.commons.Software;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResponse;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.opencga.catalog.db.api.ClinicalAnalysisDBAdaptor;
import org.opencb.opencga.catalog.db.api.InterpretationDBAdaptor;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.ClinicalAnalysisManager;
import org.opencb.opencga.catalog.managers.InterpretationManager;
import org.opencb.opencga.catalog.utils.Constants;
import org.opencb.opencga.catalog.utils.ParamUtils;
import org.opencb.opencga.core.exception.VersionException;
import org.opencb.opencga.core.models.*;
import org.opencb.opencga.core.models.acls.AclParams;
import org.opencb.opencga.storage.core.clinical.ClinicalVariantException;
import org.opencb.opencga.storage.core.manager.clinical.ClinicalInterpretationManager;
import org.opencb.opencga.storage.core.manager.variant.VariantCatalogQueryUtils;
import org.opencb.opencga.storage.core.variant.adaptors.VariantField;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.opencb.opencga.core.common.JacksonUtils.getUpdateObjectMapper;
import static org.opencb.opencga.storage.core.clinical.ReportedVariantQueryParam.*;
import static org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam.*;

@Path("/{apiVersion}/analysis/clinical")
@Produces(MediaType.APPLICATION_JSON)
@Api(value = "Analysis - Clinical Interpretation", position = 4, description = "Methods for working with Clinical Interpretations")
public class InterpretationWSService extends AnalysisWSService {

    private final ClinicalAnalysisManager clinicalManager;
    private final InterpretationManager catalogInterpretationManager;
    private final ClinicalInterpretationManager clinicalInterpretationManager;

    public InterpretationWSService(@Context UriInfo uriInfo, @Context HttpServletRequest httpServletRequest,
                                   @Context HttpHeaders httpHeaders) throws IOException, VersionException {
        super(uriInfo, httpServletRequest, httpHeaders);

        clinicalInterpretationManager = new ClinicalInterpretationManager(catalogManager, storageEngineFactory);
        catalogInterpretationManager = catalogManager.getInterpretationManager();
        clinicalManager = catalogManager.getClinicalAnalysisManager();
    }

    public InterpretationWSService(String version, @Context UriInfo uriInfo, @Context HttpServletRequest httpServletRequest,
                                   @Context HttpHeaders httpHeaders) throws IOException, VersionException {
        super(version, uriInfo, httpServletRequest, httpHeaders);

        clinicalInterpretationManager = new ClinicalInterpretationManager(catalogManager, storageEngineFactory);
        catalogInterpretationManager = catalogManager.getInterpretationManager();
        clinicalManager = catalogManager.getClinicalAnalysisManager();
    }

    @POST
    @Path("/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create a new clinical analysis", position = 1, response = ClinicalAnalysis.class)
    public Response create(
            @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study")
                    String studyStr,
            @ApiParam(name = "params", value = "JSON containing clinical analysis information", required = true)
                    ClinicalAnalysisParameters params) {
        try {
            return createOkResponse(clinicalManager.create(studyStr, params.toClinicalAnalysis(), queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @POST
    @Path("/{clinicalAnalysis}/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update a clinical analysis", position = 1, response = ClinicalAnalysis.class)
    public Response update(
            @ApiParam(value = "Clinical analysis id") @PathParam(value = "clinicalAnalysis") String clinicalAnalysisStr,
            @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study")
                    String studyStr,
            @ApiParam(name = "params", value = "JSON containing clinical analysis information", required = true)
                    ClinicalAnalysisParameters params) {
        try {
            ObjectMap parameters = new ObjectMap(getUpdateObjectMapper().writeValueAsString(params.toClinicalAnalysis()));

            if (parameters.containsKey(ClinicalAnalysisDBAdaptor.QueryParams.INTERPRETATIONS.key())) {
                Map<String, Object> actionMap = new HashMap<>();
                actionMap.put(ClinicalAnalysisDBAdaptor.QueryParams.INTERPRETATIONS.key(), ParamUtils.UpdateAction.SET.name());
                queryOptions.put(Constants.ACTIONS, actionMap);
            }

            // We remove the following parameters that are always going to appear because of Jackson
            parameters.remove(ClinicalAnalysisDBAdaptor.QueryParams.UID.key());
            parameters.remove(ClinicalAnalysisDBAdaptor.QueryParams.RELEASE.key());

            return createOkResponse(clinicalManager.update(studyStr, clinicalAnalysisStr, parameters, queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @POST
    @Path("/{clinicalAnalysis}/interpretations/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update a clinical analysis", position = 1, response = ClinicalAnalysis.class)
    public Response interpretationUpdate(
            @ApiParam(value = "Clinical analysis id") @PathParam(value = "clinicalAnalysis") String clinicalAnalysisStr,
            @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study")
                    String studyStr,
            @ApiParam(value = "Action to be performed if the array of interpretations is being updated.", defaultValue = "ADD")
            @QueryParam("action") ParamUtils.BasicUpdateAction interpretationAction,
            @ApiParam(name = "params", value = "JSON containing clinical analysis information", required = true)
                    ClinicalInterpretationParameters params) {
        try {
            if (interpretationAction == null) {
                interpretationAction = ParamUtils.BasicUpdateAction.ADD;
            }

            Map<String, Object> actionMap = new HashMap<>();
            actionMap.put(ClinicalAnalysisDBAdaptor.QueryParams.INTERPRETATIONS.key(), interpretationAction.name());
            queryOptions.put(Constants.ACTIONS, actionMap);

            ObjectMap parameters = new ObjectMap(ClinicalAnalysisDBAdaptor.QueryParams.INTERPRETATIONS.key(),
                    Arrays.asList(getUpdateObjectMapper().writeValueAsString(params.toClinicalInterpretation())));

            return createOkResponse(clinicalManager.update(studyStr, clinicalAnalysisStr, parameters, queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @GET
    @Path("/{clinicalAnalyses}/info")
    @ApiOperation(value = "Clinical analysis info", position = 3, response = ClinicalAnalysis[].class)
    @ApiImplicitParams({
            @ApiImplicitParam(name = "include", value = "Fields included in the response, whole JSON path must be provided",
                    example = "name,attributes", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "exclude", value = "Fields excluded in the response, whole JSON path must be provided",
                    example = "id,status", dataType = "string", paramType = "query")
    })
    public Response info(@ApiParam(value = "Comma separated list of clinical analysis IDs up to a maximum of 100")
                         @PathParam(value = "clinicalAnalyses") String clinicalAnalysisStr,
                         @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias")
                         @QueryParam("study") String studyStr,
                         @ApiParam(value = "Boolean to retrieve all possible entries that are queried for, false to raise an "
                                 + "exception whenever one of the entries looked for cannot be shown for whichever reason",
                                 defaultValue = "false") @QueryParam("silent") boolean silent) {
        try {
            query.remove("study");
            query.remove("clinicalAnalyses");

            List<String> analysisList = getIdList(clinicalAnalysisStr);
            List<QueryResult<ClinicalAnalysis>> analysisResult = clinicalManager.get(studyStr, analysisList, query, queryOptions, silent, sessionId);
            return createOkResponse(analysisResult);
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @GET
    @Path("/search")
    @ApiOperation(value = "Clinical analysis search.", position = 12, response = ClinicalAnalysis[].class)
    @ApiImplicitParams({
            @ApiImplicitParam(name = "include", value = "Fields included in the response, whole JSON path must be provided", example = "name,attributes", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "exclude", value = "Fields excluded in the response, whole JSON path must be provided", example = "id,status", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "limit", value = "Number of results to be returned in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = "skip", value = "Number of results to skip in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = "count", value = "Total number of results", defaultValue = "false", dataType = "boolean", paramType = "query")
    })
    public Response search(
            @ApiParam(value = "Study [[user@]project:]{study} where study and project can be either the id or alias.")
            @QueryParam("study") String studyStr,
            @ApiParam(value = "Clinical analysis type") @QueryParam("type") ClinicalAnalysis.Type type,
            @ApiParam(value = "Priority") @QueryParam("priority") String priority,
            @ApiParam(value = "Clinical analysis status") @QueryParam("status") String status,
            @ApiParam(value = "Creation date (Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805...)")
            @QueryParam("creationDate") String creationDate,
            @ApiParam(value = "Modification date (Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805...)")
            @QueryParam("modificationDate") String modificationDate,
            @ApiParam(value = "Due date (Format: yyyyMMddHHmmss. Examples: >2018, 2017-2018, <201805...)") @QueryParam("dueDate") String dueDate,
            @ApiParam(value = "Description") @QueryParam("description") String description,
            @ApiParam(value = "Germline") @QueryParam("germline") String germline,
            @ApiParam(value = "Somatic") @QueryParam("somatic") String somatic,
            @ApiParam(value = "Family") @QueryParam("family") String family,
            @ApiParam(value = "Proband") @QueryParam("proband") String proband,
            @ApiParam(value = "Sample") @QueryParam("sample") String sample,
            @ApiParam(value = "Release value") @QueryParam("release") String release,
            @ApiParam(value = "Text attributes (Format: sex=male,age>20 ...)") @QueryParam("attributes") String attributes,
            @ApiParam(value = "Numerical attributes (Format: sex=male,age>20 ...)") @QueryParam("nattributes") String nattributes) {
        try {
            query.remove("study");

            QueryResult<ClinicalAnalysis> queryResult;
            if (count) {
                queryResult = clinicalManager.count(studyStr, query, sessionId);
            } else {
                queryResult = clinicalManager.search(studyStr, query, queryOptions, sessionId);
            }
            return createOkResponse(queryResult);
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

//    @GET
//    @Path("/groupBy")
//    @ApiOperation(value = "Group clinical analysis by several fields", position = 10, hidden = true,
//            notes = "Only group by categorical variables. Grouping by continuous variables might cause unexpected behaviour")
//    @ApiImplicitParams({
//            @ApiImplicitParam(name = "count", value = "Count the number of elements matching the group", dataType = "boolean",
//                    paramType = "query"),
//            @ApiImplicitParam(name = "limit", value = "Maximum number of documents (groups) to be returned", dataType = "integer",
//                    paramType = "query", defaultValue = "50")
//    })
//    public Response groupBy(
//            @ApiParam(value = "Comma separated list of fields by which to group by.", required = true) @DefaultValue("") @QueryParam("fields") String fields,
//            @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study")
//                    String studyStr,
//            @ApiParam(value = "Comma separated list of ids.") @QueryParam("id") String id,
//            @ApiParam(value = "DEPRECATED: Comma separated list of names.") @QueryParam("name") String name,
//            @ApiParam(value = "Clinical analysis type") @QueryParam("type") ClinicalAnalysis.Type type,
//            @ApiParam(value = "Clinical analysis status") @QueryParam("status") String status,
//            @ApiParam(value = "Germline") @QueryParam("germline") String germline,
//            @ApiParam(value = "Somatic") @QueryParam("somatic") String somatic,
//            @ApiParam(value = "Family") @QueryParam("family") String family,
//            @ApiParam(value = "Proband") @QueryParam("proband") String proband,
//            @ApiParam(value = "Sample") @QueryParam("sample") String sample,
//            @ApiParam(value = "Release value (Current release from the moment the families were first created)") @QueryParam("release") String release) {
//        try {
//            query.remove("study");
//            query.remove("fields");
//
//            QueryResult result = clinicalManager.groupBy(studyStr, query, fields, queryOptions, sessionId);
//            return createOkResponse(result);
//        } catch (Exception e) {
//            return createErrorResponse(e);
//        }
//    }

    @GET
    @Path("/{clinicalAnalyses}/acl")
    @ApiOperation(value = "Returns the acl of the clinical analyses. If member is provided, it will only return the acl for the member.",
            position = 18)
    public Response getAcls(
            @ApiParam(value = "Comma separated list of clinical analysis IDs or names up to a maximum of 100", required = true)
            @PathParam("clinicalAnalyses") String clinicalAnalysis,
            @ApiParam(value = "Study [[user@]project:]study") @QueryParam("study") String studyStr,
            @ApiParam(value = "User or group id") @QueryParam("member") String member,
            @ApiParam(value = "Boolean to retrieve all possible entries that are queried for, false to raise an "
                    + "exception whenever one of the entries looked for cannot be shown for whichever reason",
                    defaultValue = "false") @QueryParam("silent") boolean silent) {
        try {
            List<String> idList = getIdList(clinicalAnalysis);
            return createOkResponse(clinicalManager.getAcls(studyStr, idList, member, silent, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    public static class ClinicalAnalysisAcl extends AclParams {
        public String clinicalAnalysis;
    }

    @POST
    @Path("/acl/{members}/update")
    @ApiOperation(value = "Update the set of permissions granted for the member", position = 21)
    public Response updateAcl(
            @ApiParam(value = "Study [[user@]project:]study") @QueryParam("study") String studyStr,
            @ApiParam(value = "Comma separated list of user or group ids", required = true) @PathParam("members") String memberId,
            @ApiParam(value = "JSON containing the parameters to add ACLs", required = true) ClinicalAnalysisAcl params) {
        try {
            params = ObjectUtils.defaultIfNull(params, new ClinicalAnalysisAcl());
            AclParams clinicalAclParams = new AclParams(params.getPermissions(), params.getAction());
            List<String> idList = getIdList(params.clinicalAnalysis);
            return createOkResponse(clinicalManager.updateAcl(studyStr, idList, memberId, clinicalAclParams, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }


    private static class SampleParams {
        public String id;
    }

    private static class ProbandParam {
        public String id;
        public List<SampleParams> samples;
    }

    private static class FamilyParam {
        public String id;
        public List<ProbandParam> members;
    }

    private static class ClinicalAnalysisParameters {
        public String id;
        @Deprecated
        public String name;
        public String description;
        public ClinicalAnalysis.Type type;

        public Disorder disorder;

        public Map<String, List<String>> files;

        public ProbandParam proband;
        public FamilyParam family;
        public ClinicalAnalysis.ClinicalStatus status;
        public List<ClinicalInterpretationParameters> interpretations;

        public String dueDate;
        public List<Comment> comments;
        public ClinicalAnalysis.Priority priority;

        public Map<String, Object> attributes;

        public ClinicalAnalysis toClinicalAnalysis() {

            Individual individual = null;
            if (proband != null) {
                individual = new Individual().setId(proband.id);
                if (proband.samples != null) {
                    List<Sample> sampleList = proband.samples.stream()
                            .map(sample -> new Sample().setId(sample.id))
                            .collect(Collectors.toList());
                    individual.setSamples(sampleList);
                }
            }

            Map<String, List<File>> fileMap = new HashMap<>();
            if (files != null) {
                for (Map.Entry<String, List<String>> entry : files.entrySet()) {
                    List<File> fileList = entry.getValue().stream().map(fileId -> new File().setPath(fileId)).collect(Collectors.toList());
                    fileMap.put(entry.getKey(), fileList);
                }
            }

            Family f = null;
            if (family != null) {
                f = new Family().setId(family.id);
                if (family.members != null) {
                    List<Individual> members = new ArrayList<>(family.members.size());
                    for (ProbandParam member : family.members) {
                        Individual auxIndividual = new Individual().setId(member.id);
                        if (member.samples != null) {
                            List<Sample> samples = member.samples.stream().map(s -> new Sample().setId(s.id)).collect(Collectors.toList());
                            auxIndividual.setSamples(samples);
                        }
                        members.add(auxIndividual);
                    }
                    f.setMembers(members);
                }
            }

            List<Interpretation> interpretationList =
                    interpretations != null
                            ? interpretations.stream()
                            .map(ClinicalInterpretationParameters::toClinicalInterpretation).collect(Collectors.toList())
                            : new ArrayList<>();
            String clinicalId = StringUtils.isEmpty(id) ? name : id;
            return new ClinicalAnalysis(clinicalId, description, type, disorder, fileMap, individual, f,
                    interpretationList, priority, null, dueDate, comments, status, 1, attributes).setName(name);
        }
    }
    
    /*
    
    /interpretation    
    
     */



    @POST
    @Path("/interpretation/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create a new clinical interpretation", position = 1,
            response = org.opencb.biodata.models.clinical.interpretation.Interpretation.class)
    public Response create(
            @ApiParam(value = "[[user@]project:]study id") @QueryParam("study") String studyStr,
            @ApiParam(value = "Clinical analysis the interpretation belongs to") @QueryParam("clinicalAnalysis") String clinicalAnalysis,
            @ApiParam(name = "params", value = "JSON containing clinical interpretation information", required = true)
                    ClinicalInterpretationParameters params) {
        try {
            return createOkResponse(catalogInterpretationManager.create(studyStr, params.toClinicalInterpretation(), queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @POST
    @Path("/interpretation/{interpretation}/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update clinical interpretation information", position = 1,
            response = org.opencb.biodata.models.clinical.interpretation.Interpretation.class)
    public Response update(
            @ApiParam(value = "[[user@]project:]study id") @QueryParam("study") String studyStr,
            @ApiParam(value = "Interpretation id") @PathParam("interpretation") String interpretationId,
//            @ApiParam(value = "Create a new version of clinical interpretation", defaultValue = "false")
//                @QueryParam(Constants.INCREMENT_VERSION) boolean incVersion,
            @ApiParam(name = "params", value = "JSON containing clinical interpretation information", required = true)
                    ClinicalInterpretationParameters params) {
        try {
            return createOkResponse(catalogInterpretationManager.update(studyStr, interpretationId, params.toInterpretationObjectMap(),
                    queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @POST
    @Path("/interpretation/{interpretation}/comments/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update comments of an interpretation", position = 1,
            response = org.opencb.biodata.models.clinical.interpretation.Interpretation.class)
    public Response commentsUpdate(
            @ApiParam(value = "[[user@]project:]study id") @QueryParam("study") String studyStr,
            @ApiParam(value = "Interpretation id") @PathParam("interpretation") String interpretationId,
            // TODO: Think about having an action in this web service. Are we ever going to allow people to set or remove comments?
            @ApiParam(value = "Action to be performed.", defaultValue = "ADD") @QueryParam("action") ParamUtils.UpdateAction action,
            @ApiParam(name = "params", value = "JSON containing a list of comments", required = true)
                    List<Comment> comments) {
        try {
            ObjectMap params = new ObjectMap(InterpretationDBAdaptor.UpdateParams.COMMENTS.key(), comments);

            Map<String, Object> actionMap = new HashMap<>();
            actionMap.put(InterpretationDBAdaptor.UpdateParams.COMMENTS.key(), action.name());
            queryOptions.put(Constants.ACTIONS, actionMap);

            return createOkResponse(catalogInterpretationManager.update(studyStr, interpretationId, params, queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @POST
    @Path("/interpretation/{interpretation}/reportedVariants/update")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update reported variants of an interpretation", position = 1,
            response = org.opencb.biodata.models.clinical.interpretation.Interpretation.class)
    public Response reportedVariantUpdate(
            @ApiParam(value = "[[user@]project:]study id") @QueryParam("study") String studyStr,
            @ApiParam(value = "Interpretation id") @PathParam("interpretation") String interpretationId,
            @ApiParam(value = "Action to be performed.", defaultValue = "ADD") @QueryParam("action") ParamUtils.UpdateAction action,
            @ApiParam(name = "params", value = "JSON containing a list of reported variants", required = true)
                    List<ReportedVariant> reportedVariants) {
        try {
            ObjectMap params = new ObjectMap(InterpretationDBAdaptor.UpdateParams.REPORTED_VARIANTS.key(), Arrays.asList(reportedVariants));

            Map<String, Object> actionMap = new HashMap<>();
            actionMap.put(InterpretationDBAdaptor.UpdateParams.REPORTED_VARIANTS.key(), action.name());
            queryOptions.put(Constants.ACTIONS, actionMap);

            return createOkResponse(catalogInterpretationManager.update(studyStr, interpretationId, params, queryOptions, sessionId));
        } catch (Exception e) {
            return createErrorResponse(e);
        }
    }

    @GET
    @Path("/interpretation/index")
    @ApiOperation(value = "Index clinical analysis interpretations in the clinical variant database", position = 14, response = QueryResponse.class)
    public Response index(@ApiParam(value = "Comma separated list of interpretation IDs to be indexed in the clinical variant database") @QueryParam(value = "interpretationId") String interpretationId,
                          @ApiParam(value = "Comma separated list of clinical analysis IDs to be indexed in the clinical variant database") @QueryParam("clinicalAnalysisId") String clinicalAnalysisId,
                          @ApiParam(value = "Reset the clinical variant database and import the specified interpretations") @QueryParam("false") boolean reset,
                          @ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study") String study) {
        try {
            clinicalInterpretationManager.index(study, sessionId);
            return Response.ok().build();
        } catch (IOException | ClinicalVariantException | CatalogException e) {
            return createErrorResponse(e);
        }
    }

    @GET
    @Path("/interpretation/query")
    @ApiOperation(value = "Query for reported variants", position = 14, response = QueryResponse.class)
    @ApiImplicitParams({
            @ApiImplicitParam(name = QueryOptions.INCLUDE, value = "Fields included in the response, whole JSON path must be provided", example = "name,attributes", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.EXCLUDE, value = "Fields excluded in the response, whole JSON path must be provided", example = "id,status", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.LIMIT, value = "Number of results to be returned in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SKIP, value = "Number of results to skip in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.COUNT, value = "Total number of results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SKIP_COUNT, value = "Do not count total number of results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SORT, value = "Sort the results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = VariantField.SUMMARY, value = "Fast fetch of main variant parameters", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "approximateCount", value = "Get an approximate count, instead of an exact total count. Reduces execution time", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "approximateCountSamplingSize", value = "Sampling size to get the approximate count. "
                    + "Larger values increase accuracy but also increase execution time", dataType = "integer", paramType = "query"),

            // Variant filters
            @ApiImplicitParam(name = "id", value = ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "region", value = REGION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "type", value = TYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reference", value = REFERENCE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "alternate", value = ALTERNATE_DESCR, dataType = "string", paramType = "query"),

            // Study filters
            @ApiImplicitParam(name = "project", value = VariantCatalogQueryUtils.PROJECT_DESC, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "study", value = STUDY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "file", value = FILE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "filter", value = FILTER_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "qual", value = QUAL_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "info", value = INFO_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "sample", value = SAMPLE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "genotype", value = GENOTYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "format", value = FORMAT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "sampleAnnotation", value = VariantCatalogQueryUtils.SAMPLE_ANNOTATION_DESC, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "sampleMetadata", value = SAMPLE_METADATA_DESCR, dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "unknownGenotype", value = UNKNOWN_GENOTYPE_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "cohort", value = COHORT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "maf", value = STATS_MAF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "mgf", value = STATS_MGF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "missingAlleles", value = MISSING_ALLELES_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "missingGenotypes", value = MISSING_GENOTYPES_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "includeStudy", value = INCLUDE_STUDY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeFile", value = INCLUDE_FILE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeSample", value = INCLUDE_SAMPLE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeFormat", value = INCLUDE_FORMAT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeGenotype", value = INCLUDE_GENOTYPE_DESCR, dataType = "string", paramType = "query"),

            // Annotation filters
            @ApiImplicitParam(name = "annotationExists", value = ANNOT_EXISTS_DESCR, dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "gene", value = GENE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "ct", value = ANNOT_CONSEQUENCE_TYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "xref", value = ANNOT_XREF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "biotype", value = ANNOT_BIOTYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "proteinSubstitution", value = ANNOT_PROTEIN_SUBSTITUTION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "conservation", value = ANNOT_CONSERVATION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyAlt", value = ANNOT_POPULATION_ALTERNATE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyRef", value = ANNOT_POPULATION_REFERENCE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyMaf", value = ANNOT_POPULATION_MINOR_ALLELE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "transcriptionFlag", value = ANNOT_TRANSCRIPTION_FLAG_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "geneTraitId", value = ANNOT_GENE_TRAIT_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "go", value = ANNOT_GO_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "expression", value = ANNOT_EXPRESSION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "proteinKeyword", value = ANNOT_PROTEIN_KEYWORD_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "drug", value = ANNOT_DRUG_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "functionalScore", value = ANNOT_FUNCTIONAL_SCORE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalSignificance", value = ANNOT_CLINICAL_SIGNIFICANCE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "customAnnotation", value = CUSTOM_ANNOTATION_DESCR, dataType = "string", paramType = "query"),

            // WARN: Only available in Solr
            @ApiImplicitParam(name = "trait", value = ANNOT_TRAIT_DESCR, dataType = "string", paramType = "query"),

            // Clinical analysis
            @ApiImplicitParam(name = "clinicalAnalysisId", value = CA_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisName", value = CA_NAME_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisDescr", value = CA_DESCRIPTION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisFiles", value = CA_FILE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisProbandId", value = CA_PROBAND_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisFamilyId", value = CA_FAMILY_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisFamPhenotypeNames", value = CA_FAMILY_PHENOTYPE_NAMES_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalAnalysisFamMemberIds", value = CA_FAMILY_MEMBER_IDS_DESCR, dataType = "string", paramType = "query"),

            // Interpretation
            @ApiImplicitParam(name = "interpretationId", value = INT_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationSoftwareName", value = INT_SOFTWARE_NAME_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationSoftwareVersion", value = INT_SOFTWARE_VERSION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationAnalystName", value = INT_ANALYST_NAME_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationPanelNames", value = INT_PANEL_NAMES_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationDescription", value = INT_DESCRIPTION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationDependencies", value = INT_DEPENDENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationFilters", value = INT_FILTERS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationComments", value = INT_COMMENTS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "interpretationCreationDate", value = INT_CREATION_DATE_DESCR, dataType = "string", paramType = "query"),

            // Reported variant
            @ApiImplicitParam(name = "reportedVariantDeNovoQualityScore", value = RV_DE_NOVO_QUALITY_SCORE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedVariantComments", value = RV_COMMENTS_DESCR, dataType = "string", paramType = "query"),

            // Reported event
            @ApiImplicitParam(name = "reportedEventPhenotypeNames", value = RE_PHENOTYPE_NAMES_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventConsequenceTypeIds", value = RE_CONSEQUENCE_TYPE_IDS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventXrefs", value = RE_XREFS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventPanelIds", value = RE_PANEL_IDS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventAcmg", value = RE_ACMG_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventClinicalSignificance", value = RE_CLINICAL_SIGNIFICANCE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventDrugResponse", value = RE_DRUG_RESPONSE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventTraitAssociation", value = RE_TRAIT_ASSOCIATION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventFunctionalEffect", value = RE_FUNCTIONAL_EFFECT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventTumorigenesis", value = RE_TUMORIGENESIS_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventOtherClassification", value = RE_OTHER_CLASSIFICATION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reportedEventRolesInCancer", value = RE_ROLES_IN_CANCER_DESCR, dataType = "string", paramType = "query")
    })
    public Response query(@ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study") String study) {
        return Response.ok().build();
    }

    @GET
    @Path("/interpretation/stats")
    @ApiOperation(value = "Clinical interpretation analysis", position = 14, response = QueryResponse.class)
    @ApiImplicitParams({
            @ApiImplicitParam(name = QueryOptions.INCLUDE, value = "Fields included in the response, whole JSON path must be provided", example = "name,attributes", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.EXCLUDE, value = "Fields excluded in the response, whole JSON path must be provided", example = "id,status", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.LIMIT, value = "Number of results to be returned in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SKIP, value = "Number of results to skip in the queries", dataType = "integer", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.COUNT, value = "Total number of results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SKIP_COUNT, value = "Do not count total number of results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = QueryOptions.SORT, value = "Sort the results", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = VariantField.SUMMARY, value = "Fast fetch of main variant parameters", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "approximateCount", value = "Get an approximate count, instead of an exact total count. Reduces execution time", dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "approximateCountSamplingSize", value = "Sampling size to get the approximate count. "
                    + "Larger values increase accuracy but also increase execution time", dataType = "integer", paramType = "query"),

            // Variant filters
            @ApiImplicitParam(name = "id", value = ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "region", value = REGION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "type", value = TYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "reference", value = REFERENCE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "alternate", value = ALTERNATE_DESCR, dataType = "string", paramType = "query"),

            // Study filters
            @ApiImplicitParam(name = "project", value = VariantCatalogQueryUtils.PROJECT_DESC, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "study", value = STUDY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "file", value = FILE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "filter", value = FILTER_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "qual", value = QUAL_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "info", value = INFO_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "sample", value = SAMPLE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "genotype", value = GENOTYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "format", value = FORMAT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "sampleAnnotation", value = VariantCatalogQueryUtils.SAMPLE_ANNOTATION_DESC, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "sampleMetadata", value = SAMPLE_METADATA_DESCR, dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "unknownGenotype", value = UNKNOWN_GENOTYPE_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "cohort", value = COHORT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "maf", value = STATS_MAF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "mgf", value = STATS_MGF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "missingAlleles", value = MISSING_ALLELES_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "missingGenotypes", value = MISSING_GENOTYPES_DESCR, dataType = "string", paramType = "query"),

            @ApiImplicitParam(name = "includeStudy", value = INCLUDE_STUDY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeFile", value = INCLUDE_FILE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeSample", value = INCLUDE_SAMPLE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeFormat", value = INCLUDE_FORMAT_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "includeGenotype", value = INCLUDE_GENOTYPE_DESCR, dataType = "string", paramType = "query"),

            // Annotation filters
            @ApiImplicitParam(name = "annotationExists", value = ANNOT_EXISTS_DESCR, dataType = "boolean", paramType = "query"),
            @ApiImplicitParam(name = "gene", value = GENE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "ct", value = ANNOT_CONSEQUENCE_TYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "xref", value = ANNOT_XREF_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "biotype", value = ANNOT_BIOTYPE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "proteinSubstitution", value = ANNOT_PROTEIN_SUBSTITUTION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "conservation", value = ANNOT_CONSERVATION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyAlt", value = ANNOT_POPULATION_ALTERNATE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyRef", value = ANNOT_POPULATION_REFERENCE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "populationFrequencyMaf", value = ANNOT_POPULATION_MINOR_ALLELE_FREQUENCY_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "transcriptionFlag", value = ANNOT_TRANSCRIPTION_FLAG_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "geneTraitId", value = ANNOT_GENE_TRAIT_ID_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "go", value = ANNOT_GO_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "expression", value = ANNOT_EXPRESSION_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "proteinKeyword", value = ANNOT_PROTEIN_KEYWORD_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "drug", value = ANNOT_DRUG_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "functionalScore", value = ANNOT_FUNCTIONAL_SCORE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "clinicalSignificance", value = ANNOT_CLINICAL_SIGNIFICANCE_DESCR, dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "customAnnotation", value = CUSTOM_ANNOTATION_DESCR, dataType = "string", paramType = "query"),

            // WARN: Only available in Solr
            @ApiImplicitParam(name = "trait", value = ANNOT_TRAIT_DESCR, dataType = "string", paramType = "query"),

            // Facet fields
            @ApiImplicitParam(name = "field", value = "Facet field for categorical fields", dataType = "string", paramType = "query"),
            @ApiImplicitParam(name = "fieldRange", value = "Facet field range for continuous fields", dataType = "string", paramType = "query")
    })
    public Response stats(@ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study") String studyStr,
                          @ApiParam(value = "Clinical Analysis Id") @QueryParam("clinicalAnalysisId") String clinicalAnalysisId,
                          @ApiParam(value = "Disease (HPO term)") @QueryParam("disease") String disease,
                          @ApiParam(value = "Family ID") @QueryParam("familyId") String familyId,
                          @ApiParam(value = "Comma separated list of subject IDs") @QueryParam("subjectIds") List<String> subjectIds,
                          @ApiParam(value = "Clinical analysis type, e.g. DUO, TRIO, ...") @QueryParam("type") String type,
                          @ApiParam(value = "Panel ID") @QueryParam("panelId") String panelId,
                          @ApiParam(value = "Panel version") @QueryParam("panelVersion") String panelVersion,
                          @ApiParam(value = "Save interpretation in Catalog") @QueryParam("save") Boolean save,
                          @ApiParam(value = "ID of the stored interpretation") @QueryParam("interpretationId") String interpretationId,
                          @ApiParam(value = "Name of the stored interpretation") @QueryParam("interpretationName") String interpretationName) {
        return Response.ok().build();
    }



    @GET
    @Path("/interpretation/tools/team")
    @ApiOperation(value = "TEAM interpretation analysis (PENDING)", position = 14, response = QueryResponse.class)
    public Response team(@ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study") String studyStr,
                         @ApiParam(value = "Clinical Analysis ID") @QueryParam("clinicalAnalysisId") String clinicalAnalysisId,
                         @ApiParam(value = "Disease (HPO term)") @QueryParam("disease") String disease,
                         @ApiParam(value = "Family ID") @QueryParam("familyId") String familyId,
                         @ApiParam(value = "Proband ID, if family exist this must be a family member") @QueryParam("probandId") String probandId,
//                         @ApiParam(value = "Clinical analysis type, e.g. DUO, TRIO, ...") @QueryParam("type") String type,
                         @ApiParam(value = "Panel ID") @QueryParam("panelId") String panelId,
                         @ApiParam(value = "Panel version") @QueryParam("panelVersion") String panelVersion,
                         @ApiParam(value = "Save interpretation in Catalog") @QueryParam("save") boolean save,
                         @ApiParam(value = "ID of the stored interpretation") @QueryParam("interpretationId") String interpretationId,
                         @ApiParam(value = "Description of the stored interpretation") @QueryParam("description") String description) {

        return Response.ok().build();
    }

    @GET
    @Path("/interpretation/tools/tiering")
    @ApiOperation(value = "GEL Tiering interpretation analysis (PENDING)", position = 14, response = QueryResponse.class)
    public Response tiering(@ApiParam(value = "Study [[user@]project:]study where study and project can be either the id or alias") @QueryParam("study") String studyStr,
                            @ApiParam(value = "Clinical Analysis ID") @QueryParam("clinicalAnalysisId") String clinicalAnalysisId,
                            @ApiParam(value = "Disease (HPO term)") @QueryParam("disease") String disease,
                            @ApiParam(value = "Family ID") @QueryParam("familyId") String familyId,
                            @ApiParam(value = "Proband ID, if family exist this must be a family member") @QueryParam("probandId") String probandId,
//                         @ApiParam(value = "Clinical analysis type, e.g. DUO, TRIO, ...") @QueryParam("type") String type,
                            @ApiParam(value = "Panel ID") @QueryParam("panelId") String panelId,
                            @ApiParam(value = "Panel version") @QueryParam("panelVersion") String panelVersion,
                            @ApiParam(value = "Save interpretation in Catalog") @QueryParam("save") boolean save,
                            @ApiParam(value = "ID of the stored interpretation") @QueryParam("interpretationId") String interpretationId,
                            @ApiParam(value = "Description of the stored interpretation") @QueryParam("description") String description) {

        return Response.ok().build();
    }

    private static class ClinicalInterpretationParameters {
        public String id;
        public String description;
        public String clinicalAnalysisId;
        public List<DiseasePanel> panels;
        public Software software;
        public Analyst analyst;
        public List<Software> dependencies;
        public Map<String, Object> filters;
        public String creationDate;
        public List<ReportedVariant> reportedVariants;
        public List<ReportedLowCoverage> reportedLowCoverages;
        public List<Comment> comments;
        public Map<String, Object> attributes;

        public Interpretation toClinicalInterpretation() {
            return new Interpretation(id, description, clinicalAnalysisId, panels, software, analyst, dependencies, filters, creationDate,
                    reportedVariants, reportedLowCoverages, comments, attributes);
        }

        public ObjectMap toInterpretationObjectMap() throws JsonProcessingException {
            return new ObjectMap(getUpdateObjectMapper().writeValueAsString(this.toClinicalInterpretation()));
        }

    }

}
