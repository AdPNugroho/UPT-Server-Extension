package org.oskari.example.up;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.nls.oskari.annotation.OskariActionRoute;
import fi.nls.oskari.control.ActionException;
import fi.nls.oskari.control.ActionParameters;
import fi.nls.oskari.control.RestActionHandler;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.util.JSONHelper;
import fi.nls.oskari.util.PropertyUtil;
import fi.nls.oskari.util.ResponseHelper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import org.json.JSONObject;
import org.oskari.example.PostStatus;

import au.com.bytecode.opencsv.CSVReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.lang.ArrayUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.oskari.example.Assumptions;
import org.oskari.example.Tables;
import org.springframework.web.client.RestTemplate;

@OskariActionRoute("up_csv_assumptions")
public class UPAssumptionsCSVHandler extends RestActionHandler {

    private static String upURL;
    private static String upUser;
    private static String upPassword;
    private static String upwsHost;
    private static String upwsPort;
    private static String upProjection;

    private JSONArray errors;
    private ObjectMapper Obj;

    private static final String PROPERTY_USERLAYER_MAX_FILE_SIZE_MB = "userlayer.max.filesize.mb";
    private static final int MAX_FILES_IN_ZIP = 1;

    private static final Charset[] POSSIBLE_CHARSETS_USED_IN_ZIP_FILE_NAMES = {
        StandardCharsets.UTF_8,
        StandardCharsets.ISO_8859_1,
        Charset.forName("CP437"),
        Charset.forName("CP866")
    };

    private static final int KB = 1024 * 1024;
    private static final int MB = 1024 * KB;

    // Store files smaller than 128kb in memory instead of writing them to disk
    private static final int MAX_SIZE_MEMORY = 128 * KB;

    private static final int MAX_RETRY_RANDOM_UUID = 100;

    private final DiskFileItemFactory diskFileItemFactory = new DiskFileItemFactory(MAX_SIZE_MEMORY, null);
    private final int userlayerMaxFileSize = PropertyUtil.getOptional(PROPERTY_USERLAYER_MAX_FILE_SIZE_MB, 10) * MB;

    private static final Logger log = LogFactory.getLogger(UPAssumptionsCSVHandler.class);

    @Override
    public void preProcess(ActionParameters params) throws ActionException {
        // common method called for all request methods
        log.info(params.getUser(), "accessing route", getName());
        PropertyUtil.loadProperties("/oskari-ext.properties");
        upURL = PropertyUtil.get("up.db.URL");
        upUser = PropertyUtil.get("up.db.user");
        upPassword = PropertyUtil.get("up.db.password");

        upwsHost = PropertyUtil.get("upws.db.host");
        upwsPort = PropertyUtil.get("upws.db.port");
        upProjection = PropertyUtil.get("oskari.native.srs").substring(PropertyUtil.get("oskari.native.srs").indexOf(":") + 1);

        errors = new JSONArray();
        Obj = new ObjectMapper();

    }

    @Override
    public void handleGet(ActionParameters params) throws ActionException {
        params.requireLoggedInUser();
    }

    @Override
    public void handlePost(ActionParameters params) throws ActionException {
        params.requireLoggedInUser();
        String errorMsg = "Assumptions post";
        Long study_area = Long.parseLong(params.getRequiredParam("study_area"));

        List<FileItem> fileItems = getFileItems(params.getRequest());

        PostStatus status = new PostStatus();
        status.status = "Error";
        JSONObject json = null;
        Integer scenario_id = null;
        try (
                Connection connection = DriverManager.getConnection(
                        upURL,
                        upUser,
                        upPassword);) {
            for (FileItem csv : fileItems) {

                PreparedStatement statement0 = connection.prepareStatement(
                        "select min(id) as scenario_id from up_scenario where study_area=?\n");
                statement0.setLong(1, study_area);
                ResultSet scenario = statement0.executeQuery();
                while (scenario.next()) {
                    scenario_id = scenario.getInt("scenario_id");
                }
                status.message += "step 1 :" + scenario_id.toString();
                CSVReader reader = new CSVReader(new InputStreamReader(csv.getInputStream()), ',', '"', 0);
                status.message += "step 2 : file readed" + reader.toString();
                boolean headers = true;
                String[] header = null;
                String[] nextLine = null;
                while ((nextLine = reader.readNext()) != null) {
                    status.message += Arrays.toString(nextLine);
                    PreparedStatement statement = connection.prepareStatement(
                            "insert into up_assumptions(study_area,scenario,category,name,value,units,description,source) \n"
                            + "values(?,?,?,?,?,?,?,?) \n"
                            + "on conflict(study_area, scenario, category, name) do nothing \n");
                    if (!headers) {
                        statement.setLong(1, study_area);
                        statement.setInt(2, scenario_id);
                        statement.setString(3, nextLine[ArrayUtils.indexOf(header, "category")]);
                        statement.setString(4, nextLine[ArrayUtils.indexOf(header, "name")]);
                        statement.setFloat(5, Float.parseFloat(nextLine[ArrayUtils.indexOf(header, "value")]));
                        statement.setString(6, nextLine[ArrayUtils.indexOf(header, "units")]);
                        statement.setString(7, nextLine[ArrayUtils.indexOf(header, "description")]);
                        statement.setString(8, nextLine[ArrayUtils.indexOf(header, "source")]);
                        try {
                            statement.execute();
                        } catch (Exception e) {
                            try {
                                errors.put(JSONHelper.createJSONObject(Obj.writeValueAsString(new PostStatus("Error", e.toString()))));
                            } catch (JsonProcessingException ex) {
                                java.util.logging.Logger.getLogger(UPAssumptionsCSVHandler.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }
                    } else {
                        header = nextLine;
                        headers = false;
                    }
                }

                setCreateAssumptions(scenario_id, params.getRequiredParam("study_area"));
            }
        } catch (Exception e) {
            log.error(e, errorMsg);
            try {
                errors.put(JSONHelper.createJSONObject(Obj.writeValueAsString(new PostStatus("Error", e.toString()))));
                ResponseHelper.writeError(params, "", 500, new JSONObject().put("Errors", errors));
            } catch (JsonProcessingException | JSONException ex) {
                java.util.logging.Logger.getLogger(UPAssumptionsCSVHandler.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    @Override
    public void handlePut(ActionParameters params) throws ActionException {
        params.requireLoggedInUser();
    }

    private List<FileItem> getFileItems(HttpServletRequest request) throws ActionException {
        try {
            request.setCharacterEncoding("UTF-8");
            ServletFileUpload upload = new ServletFileUpload(diskFileItemFactory);
            upload.setSizeMax(userlayerMaxFileSize);
            return upload.parseRequest(request);
        } catch (UnsupportedEncodingException | FileUploadException e) {
            try {
                errors.put(JSONHelper.createJSONObject(Obj.writeValueAsString(new PostStatus("Error", e.toString()))));
                ResponseHelper.writeError(null, "", 500, new JSONObject().put("Errors", errors));
            } catch (JsonProcessingException ex) {
                java.util.logging.Logger.getLogger(UPAssumptionsCSVHandler.class.getName()).log(Level.SEVERE, null, ex);
            } catch (JSONException ex) {
                java.util.logging.Logger.getLogger(UPAssumptionsCSVHandler.class.getName()).log(Level.SEVERE, null, ex);
            }
            throw new ActionException("Failed to read request", e);
        }
    }

    protected void setCreateAssumptions(Integer scenario_id, String study_area) throws Exception {
        PostStatus postStatus;
        try (Connection connection = DriverManager.getConnection(
                upURL,
                upUser,
                upPassword)) {

            Statement statement = connection.createStatement();
            String query = "SELECT " + scenario_id + " as scenario, category, name, value\n"
                    + "FROM public.up_assumptions\n"
                    + "where study_area=" + study_area;
            ResultSet data_set = statement.executeQuery(query);
            ArrayList<Assumptions> data_in = new ArrayList<>();
            while (data_set.next()) {
                Assumptions val = new Assumptions();
                val.scenario = data_set.getInt("scenario");
                val.category = data_set.getString("category");
                val.name = data_set.getString("name");
                val.value = data_set.getDouble("value");
                data_in.add(val);
            }
            Tables<Assumptions> final_data = new Tables<>(data_in);

            RestTemplate restTemplate = new RestTemplate();
            postStatus = restTemplate.postForObject("http://" + upwsHost + ":" + upwsPort + "/assumptions/", final_data, PostStatus.class);
        } catch (Exception e) {
            errors.put(JSONHelper.createJSONObject(Obj.writeValueAsString(new PostStatus("Error", e.toString()))));
            throw new Exception();
        }
    }
}