package org.opencb.opencga.catalog.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.BasicDBObject;
import org.junit.*;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

import org.junit.runners.MethodSorters;
import org.opencb.commons.containers.map.QueryOptions;
import org.opencb.commons.test.GenericTest;
import org.opencb.datastore.core.ObjectMap;
import org.opencb.datastore.core.QueryResult;
import org.opencb.opencga.catalog.core.beans.*;
import org.opencb.opencga.catalog.core.beans.File;
import org.opencb.opencga.catalog.core.db.CatalogManagerException;
import org.opencb.opencga.catalog.core.io.CatalogIOManagerException;
import org.opencb.opencga.lib.common.StringUtils;
import org.opencb.opencga.lib.common.TimeUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@FixMethodOrder(MethodSorters.JVM)
public class CatalogManagerTest extends GenericTest {

    public static final String PASSWORD = "asdf";
    static CatalogManager catalogManager;
    private String sessionIdUser;
    private String sessionIdUser2;
    private String sessionIdUser3;

    @BeforeClass
    public static void init() throws IOException, CatalogIOManagerException, CatalogManagerException {
        InputStream is = CatalogManagerTest.class.getClassLoader().getResourceAsStream("catalog.properties");
        Properties properties = new Properties();
        properties.load(is);
        catalogManager = new CatalogManager(properties);
    }

    @Before
    public void setUp() throws IOException, CatalogIOManagerException {
        List<ObjectMap> result;
        try {
            result = catalogManager.login("user", PASSWORD, "127.0.0.1").getResult();
            sessionIdUser = result.get(0).getString("sessionId");
        } catch (CatalogManagerException | IOException ignore) {
        }
        try {
            result = catalogManager.login("user2", PASSWORD, "127.0.0.1").getResult();
            sessionIdUser2 = result.get(0).getString("sessionId");
        } catch (CatalogManagerException | IOException ignore) {
        }

        try {
            result = catalogManager.login("user3", PASSWORD, "127.0.0.1").getResult();
            sessionIdUser3 = result.get(0).getString("sessionId");
        } catch (CatalogManagerException | IOException ignore) {
        }
    }

    @After
    public void tearDown() throws Exception {
        if(sessionIdUser != null) {
            catalogManager.logout("user", sessionIdUser);
        }
        if(sessionIdUser2 != null) {
            catalogManager.logout("user2", sessionIdUser2);
        }
        if(sessionIdUser3 != null) {
            catalogManager.logout("user3", sessionIdUser3);
        }
    }


    @Test
    public void testCreateUser() throws Exception {
        User user = new User("user", "User Name", "mail@ebi.ac.uk", PASSWORD, "", "", "");
        System.out.println(catalogManager.createUser(user));
        user = new User("user2", "User2 Name", "mail2@ebi.ac.uk", PASSWORD, "", "", "");
        System.out.println(catalogManager.createUser(user));
        System.out.println(catalogManager.createUser("user3", "User3 Name", "email3", PASSWORD, "ACME"));
    }

    @Test
    public void testLoginAsAnonymous() throws Exception {
        System.out.println(catalogManager.loginAsAnonymous("127.0.0.1"));
    }

    @Test
    public void testLogin() throws Exception {
        QueryResult<ObjectMap> queryResult = catalogManager.login("user", PASSWORD, "127.0.0.1");
        System.out.println(queryResult.getResult().get(0).toJson());
        try{
            catalogManager.login("user", "fakePassword", "127.0.0.1");
            fail("Expected 'wrong password' exception");
        } catch (CatalogManagerException e ){
            System.out.println(e.getMessage());
        }
    }


    @Test
    public void testLogoutAnonymous() throws Exception {
        QueryResult<ObjectMap> queryResult = catalogManager.loginAsAnonymous("127.0.0.1");
        catalogManager.logoutAnonymous(queryResult.getResult().get(0).getString("sessionId"));
    }

    @Test
    public void testGetUserInfo() throws CatalogManagerException {
        QueryResult<User> user = catalogManager.getUser("user", null, sessionIdUser);
        System.out.println("user = " + user);
        QueryResult<User> userVoid = catalogManager.getUser("user", user.getResult().get(0).getLastActivity(), sessionIdUser);
        System.out.println("userVoid = " + userVoid);
        assertTrue(userVoid.getResult().isEmpty());
        try {
            catalogManager.getUser("user", null, sessionIdUser2);
            fail();
        } catch (CatalogManagerException e) {
            System.out.println(e);
        }
    }

    @Test
    public void testModifyUser() throws CatalogManagerException, InterruptedException {
        ObjectMap params = new ObjectMap();
        String newName = "Changed Name " + StringUtils.randomString(10);
        String newPassword = StringUtils.randomString(10);
        String newEmail = "new@email.ac.uk";

        params.put("name", newName);
        ObjectMap attributes = new ObjectMap("myBoolean", true);
        attributes.put("value", 6);
        attributes.put("object", new BasicDBObject("id", 1234));
        params.put("attributes", attributes);

        User userPre = catalogManager.getUser("user", null, sessionIdUser).getResult().get(0);
        System.out.println("userPre = " + userPre);
        Thread.sleep(10);

        catalogManager.modifyUser("user", params, sessionIdUser);
        catalogManager.changeEmail("user", newEmail, sessionIdUser);
        catalogManager.changePassword("user", PASSWORD, newPassword, sessionIdUser);

        List<User> userList = catalogManager.getUser("user", userPre.getLastActivity(), sessionIdUser).getResult();
        if(userList.isEmpty()){
            fail("Error. LastActivity should have changed");
        }
        User userPost = userList.get(0);
        System.out.println("userPost = " + userPost);
        assertTrue(!userPre.getLastActivity().equals(userPost.getLastActivity()));
        assertEquals(userPost.getName(), newName);
        assertEquals(userPost.getEmail(), newEmail);
        assertEquals(userPost.getPassword(), newPassword);
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            assertEquals(userPost.getAttributes().get(entry.getKey()), entry.getValue());
        }

        try {
            params = new ObjectMap();
            params.put("password", "1234321");
            catalogManager.modifyUser("user", params, sessionIdUser);
            fail("Expected exception");
        } catch (CatalogManagerException e){
            System.out.println(e);
        }

        try {
            catalogManager.modifyUser("user", params, sessionIdUser2);
            fail("Expected exception");
        } catch (CatalogManagerException e){
            System.out.println(e);
        }

        catalogManager.changePassword("user", newPassword, PASSWORD, sessionIdUser);

    }

    /**
     * Project methods
     * ***************************
     */

    @Test
    public void testCreateProject() throws Exception {
        Project p = new Project("Project about some genomes", "1000G", "Today", "Cool", "", "", 1000, "");
        System.out.println(catalogManager.createProject("user", p, sessionIdUser));
        System.out.println(catalogManager.createProject("user3", "Project Management Project", "pmp", "life art intelligent system", "myorg", sessionIdUser3));
    }

    @Test
    public void testGetAllProjects() throws Exception {
        System.out.println(catalogManager.getAllProjects("user", sessionIdUser));
        System.out.println(catalogManager.getAllProjects("user", sessionIdUser2));
    }

    @Test
    public void testModifyProject() throws CatalogManagerException {
        String newProjectName = "ProjectName " + StringUtils.randomString(10);
        int projectId = catalogManager.getUser("user", null, sessionIdUser).getResult().get(0).getProjects().get(0).getId();

        ObjectMap options = new ObjectMap();
        options.put("name", newProjectName);
        ObjectMap attributes = new ObjectMap("myBoolean", true);
        attributes.put("value", 6);
        attributes.put("object", new BasicDBObject("id", 1234));
        options.put("attributes", attributes);

        catalogManager.modifyProject(projectId, options, sessionIdUser);
        QueryResult<Project> result = catalogManager.getProject(projectId, sessionIdUser);
        Project project = result.getResult().get(0);
        System.out.println(result);

        assertEquals(newProjectName, project.getName());
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            assertEquals(project.getAttributes().get(entry.getKey()), entry.getValue());
        }

        try {
            options = new ObjectMap();
            options.put("alias", "newProjectAlias");
            catalogManager.modifyProject(projectId, options, sessionIdUser);
            fail("Expected 'Parameter can't be changed' exception");
        } catch (CatalogManagerException e){
            System.out.println(e);
        }

        try {
            catalogManager.modifyProject(projectId, options, sessionIdUser2);
            fail("Expected 'Permission denied' exception");
        } catch (CatalogManagerException e){
            System.out.println(e);
        }

    }

    /**
     * Study methods
     * ***************************
     */

    @Test
    public void testCreateStudy() throws Exception {
        int projectId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getId();
        System.out.println(catalogManager.createStudy(projectId, "Phase 3", "phase3", "type", "d", sessionIdUser));
        System.out.println(catalogManager.createStudy(projectId, "Phase 1", "phase1", "type", "Done", sessionIdUser));
    }

    @Test
    public void testModifyStudy() throws Exception {
        int studyId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getStudies().get(0).getId();
        String newName = "Phase 1 "+ StringUtils.randomString(20);
        String newDescription = StringUtils.randomString(500);

        ObjectMap parameters = new ObjectMap();
        parameters.put("name", newName);
        parameters.put("description", newDescription);
        BasicDBObject attributes = new BasicDBObject("key", "value");
        parameters.put("attributes", attributes);
        catalogManager.modifyStudy(studyId, parameters, sessionIdUser);

        QueryResult<Study> result = catalogManager.getStudy(studyId, sessionIdUser);
        System.out.println(result);
        Study study = result.getResult().get(0);
        assertEquals(study.getName(), newName);
        assertEquals(study.getDescription(), newDescription);
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            assertEquals(study.getAttributes().get(entry.getKey()), entry.getValue());
        }
    }

    /**
     * File methods
     * ***************************
     */

    @Test
    public void testDeleteDataFromStudy() throws Exception {

    }

    @Test
    public void testCreateFolder() throws Exception {
        int projectId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getId();
        int studyId = catalogManager.getAllStudies(projectId, sessionIdUser).getResult().get(0).getId();
        System.out.println(catalogManager.createFolder(studyId, Paths.get("data", "new", "folder"), true, sessionIdUser));
    }

    @Test
    public void testCreateAndUpload() throws Exception {
        int projectId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getId();
        int studyId = catalogManager.getAllStudies(projectId, sessionIdUser).getResult().get(0).getId();

        String fileTest = "/tmp/" + StringUtils.randomString(5);
        DataOutputStream os = new DataOutputStream(new FileOutputStream(fileTest));

        for (int i = 0; i < 200; i++) {
            os.writeBytes(StringUtils.randomString(500));
        }
        os.close();

        InputStream is = new FileInputStream(Paths.get(fileTest).toFile());

        String fileName = "item." + TimeUtils.getTime() + ".vcf";
        File preFile = new File(fileName, File.FILE, "", "", "", "data/", "", "", "", 12);
        QueryResult<File> file = catalogManager.createFile(studyId, "txt", "vcf", preFile.getPath() + fileName, preFile.getDescription(), true, sessionIdUser);

        catalogManager.uploadFile(file.getResult().get(0).getId(), is, false, sessionIdUser);
        is.close();

        Files.delete(Paths.get(fileTest));
    }

    /**
     * Analysis methods
     * ***************************
     */

    @Test
    public void testCreateAnalysis() throws CatalogManagerException, JsonProcessingException {
        int projectId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getId();
        int studyId = catalogManager.getAllStudies(projectId, sessionIdUser).getResult().get(0).getId();
        Analysis analysis = new Analysis("MyAnalysis", "analysis1", "date", "user", "description");

        System.out.println(catalogManager.createAnalysis(studyId, analysis, sessionIdUser));

    }

    /**
     * Job methods
     * ***************************
     */

    @Test
    public void testCreateJob() throws CatalogManagerException, JsonProcessingException, CatalogIOManagerException {
        int projectId = catalogManager.getAllProjects("user", sessionIdUser).getResult().get(0).getId();
        int studyId = catalogManager.getAllStudies(projectId, sessionIdUser).getResult().get(0).getId();
        int analysisId = catalogManager.getAllAnalysis(studyId, sessionIdUser).getResult().get(0).getId();

        Job job = new Job("myFirstJob", "", "samtool", "description", "#rm -rf .*", "jobs/myJob", Collections.<Integer>emptyList());

        System.out.println(catalogManager.createJob(analysisId, job, sessionIdUser));

    }
}