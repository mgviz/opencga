package org.opencb.opencga.catalog.authorization;

import org.opencb.datastore.core.QueryOptions;
import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.catalog.exceptions.CatalogAuthorizationException;
import org.opencb.opencga.catalog.exceptions.CatalogDBException;
import org.opencb.opencga.catalog.exceptions.CatalogException;
import org.opencb.opencga.catalog.managers.FileManager;
import org.opencb.opencga.catalog.utils.ParamUtils;
import org.opencb.opencga.catalog.models.*;
import org.opencb.opencga.catalog.db.api.*;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class CatalogAuthorizationManager implements AuthorizationManager {
    final CatalogUserDBAdaptor userDBAdaptor;
    final CatalogStudyDBAdaptor studyDBAdaptor;
    final CatalogFileDBAdaptor fileDBAdaptor;
    final CatalogSampleDBAdaptor sampleDBAdaptor;

    public CatalogAuthorizationManager(CatalogDBAdaptorFactory catalogDBAdaptorFactory) {
        this.userDBAdaptor = catalogDBAdaptorFactory.getCatalogUserDBAdaptor();
        this.studyDBAdaptor = catalogDBAdaptorFactory.getCatalogStudyDBAdaptor();
        this.fileDBAdaptor = catalogDBAdaptorFactory.getCatalogFileDBAdaptor();
        this.sampleDBAdaptor = catalogDBAdaptorFactory.getCatalogSampleDBAdaptor();
    }

    @Override
    public User.Role getUserRole(String userId) throws CatalogException {
        return userDBAdaptor.getUser(userId, new QueryOptions("include", Arrays.asList("role")), null).first().getRole();
    }

    @Override
    public Acl getProjectACL(String userId, int projectId) throws CatalogException {
        Acl projectAcl;
        if (getUserRole(userId).equals(User.Role.ADMIN)) {
            return new Acl(userId, true, true, true, true);
        }
        boolean sameOwner = userDBAdaptor.getProjectOwnerId(projectId).equals(userId);

        if (sameOwner) {
            projectAcl = new Acl(userId, true, true, true, true);
        } else {
            QueryResult<Acl> result = userDBAdaptor.getProjectAcl(projectId, userId);
            if (!result.getResult().isEmpty()) {
                projectAcl = result.getResult().get(0);
            } else {
                QueryResult<Acl> resultAll = userDBAdaptor.getProjectAcl(projectId, Acl.USER_OTHERS_ID);
                if (!resultAll.getResult().isEmpty()) {
                    projectAcl = resultAll.getResult().get(0);
                } else {
                    projectAcl = new Acl(userId, false, false, false, false);
                }
            }
        }
        return projectAcl;
    }

    @Override
    public QueryResult setProjectACL(int projectId, Acl acl, String sessionId) throws CatalogException {
        ParamUtils.checkObj(acl, "acl");
        ParamUtils.checkParameter(sessionId, "sessionId");

        String userId = userDBAdaptor.getUserIdBySessionId(sessionId);
        Acl projectAcl = getProjectACL(userId, projectId);
        if (!projectAcl.isWrite()) {
            throw CatalogAuthorizationException.cantModify(userId, "Project", projectId, null);
        }

        return userDBAdaptor.setProjectAcl(projectId, acl);
    }

    @Override
    public void checkStudyPermission(int studyId, String userId, StudyPermission permission) throws CatalogException {
        checkStudyPermission(studyId, userId, permission, permission.toString());
    }

    @Override
    public void checkStudyPermission(int studyId, String userId, StudyPermission permission, String message) throws CatalogException {
        if (userDBAdaptor.getProjectOwnerId(studyDBAdaptor.getProjectIdByStudyId(studyId)).equals(userId)) {
            return;
        }
        if (getUserRole(userId).equals(User.Role.ADMIN)) {
            return;
        }

        Group group = getGroupBelonging(studyId, userId);
        if (group == null) {
            throw CatalogAuthorizationException.denny(userId, message, "Study", studyId, null);
        }

        final boolean auth;
        switch (permission) {
            case DELETE_JOBS:
                auth = group.getPermissions().isDeleteJobs();
                break;
            case LAUNCH_JOBS:
                auth = group.getPermissions().isLaunchJobs();
                break;
            case MANAGE_SAMPLES:
                auth = group.getPermissions().isManagerSamples();
                break;
            case MANAGE_STUDY:
                auth = group.getPermissions().isStudyManager();
                break;
            case READ_STUDY:
                auth = true; //Authorize if belongs to any group
                break;
            default:
                auth = false;
        }
        if (!auth) {
            throw CatalogAuthorizationException.denny(userId, message, "Study", studyId, null);
        }
    }

    @Override
    public void checkFilePermission(int fileId, String userId, CatalogPermission permission) throws CatalogException {
        int studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        if (userDBAdaptor.getProjectOwnerId(studyDBAdaptor.getProjectIdByStudyId(studyId)).equals(userId)) {
            return;
        }
        if (getUserRole(userId).equals(User.Role.ADMIN)) {
            return;
        }

        Acl fileAcl = resolveFileAcl(fileId, userId, studyId);


        final boolean auth;
        switch (permission) {
            case READ:
                auth = fileAcl.isRead();
                break;
            case WRITE:
                auth = fileAcl.isWrite();
                break;
            case DELETE:
                auth = fileAcl.isDelete();
                break;
            default:
                auth = false;
                break;
        }
        if (!auth) {
            throw CatalogAuthorizationException.denny(userId, permission.toString(), "File", studyId, null);
        }

    }

    @Override
    public void checkSamplePermission(int sampleId, String userId, CatalogPermission permission) throws CatalogException {

    }

    public Acl resolveFileAcl(int fileId, String userId, int studyId) throws CatalogException {
        Group group = getGroupBelonging(studyId, userId);
        if (group == null) {
            return new Acl(userId, false, false, false, false);
        }

        Acl studyAcl = getStudyACL(userId, group);
        Acl fileAcl = null;

        File file = fileDBAdaptor.getFile(fileId, fileIncludeQueryOptions).first();
        List<String> paths = FileManager.getParentPaths(file.getPath());
        Map<String, Map<String, Acl>> pathAclMap = fileDBAdaptor.getFilesAcl(studyId, FileManager.getParentPaths(file.getPath()), Arrays.asList(userId, Acl.USER_OTHERS_ID)).first();

        for (int i = paths.size() - 1; i >= 0; i--) {
            String path = paths.get(i);
            if (pathAclMap.containsKey(path)) {
                //Get first the user AclEntry
                fileAcl = pathAclMap.get(path).get(userId);
                //If missing, get Others AclEntry
                if (fileAcl == null) {
                    fileAcl = pathAclMap.get(path).get(Acl.USER_OTHERS_ID);
                }
                if (fileAcl != null) {
                    break;
                }
            }
        }

        if (fileAcl == null) {
            fileAcl = studyAcl;
        }
        return fileAcl;
    }

    private Acl getStudyACL(String userId, Group group) {
        return new Acl(userId, group.getPermissions().isRead(), group.getPermissions().isWrite(), false, group.getPermissions().isDelete());
    }

    @Override
    public Acl getStudyACL(String userId, int studyId) throws CatalogException {
        return getStudyACL(userId, getGroupBelonging(studyId, userId));
//        int projectId = studyDBAdaptor.getProjectIdByStudyId(studyId);
//        return getStudyACL(userId, studyId, getProjectACL(userId, projectId));
    }

    @Override
    public QueryResult setStudyACL(int studyId, Acl acl, String sessionId) throws CatalogException {
        ParamUtils.checkObj(acl, "acl");
        ParamUtils.checkParameter(sessionId, "sessionId");

        String userId = userDBAdaptor.getUserIdBySessionId(sessionId);
        Acl studyACL = getStudyACL(userId, studyId);
        if (!studyACL.isWrite()) {
            throw CatalogAuthorizationException.cantModify(userId, "Study", studyId, null);
        }

        return studyDBAdaptor.setStudyAcl(studyId, acl);
    }

    @Override
    public Acl getFileACL(String userId, int fileId) throws CatalogException {
        if (getUserRole(userId).equals(User.Role.ADMIN)) {
            return new Acl(userId, true, true, true, true);
        }
        int studyId = fileDBAdaptor.getStudyIdByFileId(fileId);
        return getFileACL(userId, fileId, getStudyACL(userId, studyId));
    }

    @Override
    public QueryResult setFileACL(int fileId, Acl acl, String sessionId) throws CatalogException {
        ParamUtils.checkObj(acl, "acl");
        ParamUtils.checkParameter(sessionId, "sessionId");

        String userId = userDBAdaptor.getUserIdBySessionId(sessionId);
        checkStudyPermission(fileDBAdaptor.getStudyIdByFileId(fileId), userId, StudyPermission.MANAGE_STUDY);

        return fileDBAdaptor.setFileAcl(fileId, acl);
    }

    @Override
    public Acl getSampleACL(String userId, int sampleId) throws CatalogException {
        return getStudyACL(userId, sampleDBAdaptor.getStudyIdBySampleId(sampleId));
    }

    @Override
    public QueryResult setSampleACL(int sampleId, Acl acl, String sessionId) throws CatalogException {
//        ParamUtils.checkObj(acl, "acl");
//        ParamUtils.checkParameter(sessionId, "sessionId");
//
//        String userId = userDBAdaptor.getUserIdBySessionId(sessionId);
//        checkStudyPermission(sampleDBAdaptor.getStudyIdBySampleId(sampleId), userId, StudyPermission.MANAGE_STUDY);
//
//
//        return sampleDBAdaptor.setSampleAcl(sampleId, acl);
        throw new UnsupportedOperationException("Unimplemented");
    }

    @Override
    public void filterProjects(String userId, List<Project> projects) throws CatalogException {
        Iterator<Project> projectIt = projects.iterator();
        while (projectIt.hasNext()) {
            Project p = projectIt.next();
            Acl projectAcl = getProjectACL(userId, p.getId());
            if (!projectAcl.isRead()) {
                projectIt.remove();
            } else {
                List<Study> studies = p.getStudies();
                filterStudies(userId, projectAcl, studies);
            }
        }
    }

    @Override
    public void filterStudies(String userId, Acl projectAcl, List<Study> studies) throws CatalogException {
        Iterator<Study> studyIt = studies.iterator();
        while (studyIt.hasNext()) {
            Study s = studyIt.next();
            Acl studyAcl = getStudyACL(userId, s.getId(), projectAcl);
            if (!studyAcl.isRead()) {
                studyIt.remove();
            } else {
                List<File> files = s.getFiles();
                filterFiles(userId, studyAcl, files);
            }
        }
    }

    @Override
    public void filterFiles(String userId, Acl studyAcl, List<File> files) throws CatalogException {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (studyAcl == null) {
            studyAcl = getStudyACL(userId, fileDBAdaptor.getStudyIdByFileId(files.get(0).getId()));
        }
        Iterator<File> fileIt = files.iterator();
        while (fileIt.hasNext()) {
            File f = fileIt.next();
            Acl fileAcl = getFileACL(userId, f.getId(), studyAcl);
            if (!fileAcl.isRead()) {
                fileIt.remove();
            }
        }
    }

    @Override
    public void filterJobs(String userId, List<Job> jobs) throws CatalogException {
        job_loop: for (Iterator<Job> iterator = jobs.iterator(); iterator.hasNext(); ) {
            Job job = iterator.next();
            for (Integer fileId : job.getOutput()) {
                if (!resolveFileAcl(fileId, userId, fileDBAdaptor.getStudyIdByFileId(fileId)).isRead()) {
                    iterator.remove();
                    break job_loop;
                }
            }
            for (Integer fileId : job.getInput()) {
                if (!resolveFileAcl(fileId, userId, fileDBAdaptor.getStudyIdByFileId(fileId)).isRead()) {
                    iterator.remove();
                    break job_loop;
                }
            }
        }
    }

    @Override
    public void checkReadJob(String userId, Job job) throws CatalogException {
        for (Integer fileId : job.getOutput()) {
            checkFilePermission(fileId, userId, CatalogPermission.READ);
        }
        for (Integer fileId : job.getInput()) {
            checkFilePermission(fileId, userId, CatalogPermission.READ);
        }
    }

    @Override
    public Group getGroupBelonging(int studyId, String userId) throws CatalogException {
        QueryResult<Group> queryResult = studyDBAdaptor.getGroup(studyId, userId, null, null);
        return queryResult.getNumResults() == 0 ? null : queryResult.first();
    }

    @Override
    public QueryResult<Group> addMember(int studyId, String groupId, String userIdToAdd, String sessionId) throws CatalogException {

        checkStudyPermission(studyId, userDBAdaptor.getUserIdBySessionId(sessionId), StudyPermission.MANAGE_STUDY);

        Group groupFromUserToAdd = getGroupBelonging(studyId, userIdToAdd);
        if (groupFromUserToAdd != null) {
            throw new CatalogException("User \"" + userIdToAdd + "\" already belongs to group " + groupFromUserToAdd.getId());
        }

        return studyDBAdaptor.addMemberToGroup(studyId, groupId, userIdToAdd);
    }

    @Override
    public QueryResult<Group> removeMember(int studyId, String groupId, String userIdToRemove, String sessionId) throws CatalogException {

        checkStudyPermission(studyId, userDBAdaptor.getUserIdBySessionId(sessionId), StudyPermission.MANAGE_STUDY);

        Group groupFromUserToRemove = getGroupBelonging(studyId, userIdToRemove);
        if (groupFromUserToRemove == null || !groupFromUserToRemove.getId().equals(groupId)) {
            throw new CatalogException("User \"" + userIdToRemove + "\" does not belongs to group " + groupId);
        }

        return studyDBAdaptor.removeMemberFromGroup(studyId, groupId, userIdToRemove);
    }

    private Acl mergeAcl(String userId, Acl acl1, Acl acl2) {
        return new Acl(
                userId,
                acl1.isRead() && acl2.isRead(),
                acl1.isWrite() && acl2.isWrite(),
                acl1.isExecute() && acl2.isExecute(),
                acl1.isDelete() && acl2.isDelete()
        );
    }


    public Acl getStudyACL(String userId, int studyId, Acl projectAcl) throws CatalogException {
        Acl studyAcl;
        if (getUserRole(userId).equals(User.Role.ADMIN)) {
            return new Acl(userId, true, true, true, true);
        }
        boolean sameOwner = studyDBAdaptor.getStudyOwnerId(studyId).equals(userId);

        if (sameOwner) {
            studyAcl = new Acl(userId, true, true, true, true);
        } else {
            QueryResult<Acl> result = studyDBAdaptor.getStudyAcl(studyId, userId);
            if (!result.getResult().isEmpty()) {
                studyAcl = result.getResult().get(0);
            } else {
                QueryResult<Acl> resultAll = studyDBAdaptor.getStudyAcl(studyId, Acl.USER_OTHERS_ID);
                if (!resultAll.getResult().isEmpty()) {
                    studyAcl = resultAll.getResult().get(0);
                } else {
                    //studyAcl = new Acl(userId, false, false, false, false);
                    studyAcl = projectAcl;
                }
            }
        }
        return mergeAcl(userId, projectAcl, studyAcl);
    }

    /**
     * Use StudyACL for all files.
     */
    public Acl getFileACL(String userId, int fileId, Acl studyAcl) throws CatalogException {
        return __getFileAcl(userId, fileDBAdaptor.getStudyIdByFileId(fileId), fileId, studyAcl);
    }

    //TODO: Check folder ACLs
    private final QueryOptions fileIncludeQueryOptions = new QueryOptions("include", Arrays.asList("projects.studies.files.id", "projects.studies.files.path", "projects.studies.files.acls"));

    private Acl __getFileAcl(String userId, int studyId, int fileId, Acl studyAcl) throws CatalogException {
        Acl fileAcl = null;
        boolean sameOwner = fileDBAdaptor.getFileOwnerId(fileId).equals(userId);

        if (sameOwner) {
            fileAcl = new Acl(userId, true, true, true, true);
        } else {
            File file = fileDBAdaptor.getFile(fileId, fileIncludeQueryOptions).first();
            List<String> paths = FileManager.getParentPaths(file.getPath());
//            QueryOptions query = new QueryOptions(CatalogFileDBAdaptor.FileFilterOption.path.toString(), paths);
            Map<String, Map<String, Acl>> pathAclMap = fileDBAdaptor.getFilesAcl(studyId, FileManager.getParentPaths(file.getPath()), Arrays.asList(userId, Acl.USER_OTHERS_ID)).first();

            for (int i = paths.size() - 1; i >= 0; i--) {
                String path = paths.get(i);
                if (pathAclMap.containsKey(path)) {
                    //Get first the user AclEntry
                    fileAcl = pathAclMap.get(path).get(userId);
                    //If missing, get Others AclEntry
                    if (fileAcl == null) {
                        fileAcl = pathAclMap.get(path).get(Acl.USER_OTHERS_ID);
                    }
                    if (fileAcl != null) {
                        break;
                    }
                }
            }
//            for (String path : paths) {
//                if (pathAclMap.containsKey(path)) {
//                    mergeAcl(userId, )
//                }
//            }
//
//            if (!result.getResult().isEmpty()) {
//                fileAcl = result.getResult().get(0);
//            } else {
//                QueryResult<Acl> resultAll = fileDBAdaptor.getFileAcl(fileId, Acl.USER_OTHERS_ID);
//                if (!resultAll.getResult().isEmpty()) {
//                    fileAcl = resultAll.getResult().get(0);
//                } else {
//                    //fileAcl = new Acl(userId, false, false, false, false);
//                    fileAcl = studyAcl;
//                }
//            }
        }
        return fileAcl == null ? studyAcl : fileAcl;
//        return mergeAcl(userId, fileAcl, studyAcl);
    }

}
