package sk.microstep.icontrol.VisibilityHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.LinkedList;
import java.util.List;
import sk.uisav.icontrol.*;

/**
 * A class for openwhisk action
 * Expects a JSON object with structure shown in ${project.home}/src/main/resources/in.json.example
 * Please copy in.json.example to in.json, add it to .gitignore and then edit in it your login and password
 * Returns a JSON object with string property "result" containing the name of a result object.
 * For example:
 * {
 *     "result": "value",
 *     "value": 10
 * }
 * or
 * {
 *     "result": "exception",
 *     "exception": "incorrect input parameters provided"
 * }
 * Additionaly the return JSON contains the original request and the log of the stitcher process.
 * See ${project.home}/src/main/resources/result.json.example for structure of the returned data
 *
 * After compiling stitcher.jar, use these steps in openwhisk:
 * 1. wsk -i action delete visibilityHandler
 * 2. wsk -i action create visibilityHandler visibilityHandler.jar --main sk.microstep.icontrol.VisibilityHandler.VisibilityHandler --docker iisas/java8action:latest
 * 3. wsk -i action invoke visibilityHandler -b -r -P in.json
 * 4. wsk -i action delete visibilityHandler
 */public class VisibilityHandler {
    private List<String> mylog;
    private JsonObject storage;
    private final static String workDir = "/tmp";
    private int year;
    private int month;
    private int dom;
    private int hour;
    private int minute;

    public VisibilityHandler(int year, int month, int dom, int hour, int min, JsonObject storage) throws Exception
    {
        this.mylog = new LinkedList<String>();
        this.storage = storage;
        this.year = year;
        this.month = month;
        this.dom = dom;
        this.hour = hour;
        this.minute = min;
        log(String.format( "VisibilityHandler::VisibilityHandler: Created visibility handler for %04d-%02d-%02d %02d:%02d",
                year, month, dom, hour, min));
    }

    private void log(String msg)
    {
        this.mylog.add(msg);
    }

    private List<String> getLog()
    {
        return this.mylog;
    }

    public void run() throws Exception
    {
        log("VisibilityHandler::run: downloading input data");
        downloadInputs();
        log("VisibilityHandler::run: starting execution");
        execute();
        log("VisibilityHandler::run: uploading output data");
        uploadOutputs();
        log("VisibilityHandler::run: finished OK");
    }

    private WebDavClient getStorageClient(JsonObject params) throws MalformedURLException {
        String storageType = params.getAsJsonPrimitive("type").getAsString();
        if(! storageType.equalsIgnoreCase("webdav"))
            throw new RuntimeException(String.format(
                    "VisibilityHandler::getStorageClient: can work only with webdav storage, you have provided \"%s\"",
                    storageType));
        String wdUrl = params.getAsJsonPrimitive("url").getAsString();
        String wdLogin = params.getAsJsonPrimitive("login").getAsString();
        String wdPass = params.getAsJsonPrimitive("password").getAsString();
        WebDavClient retv = new WebDavClient(wdLogin, wdPass, wdUrl);
        return retv;
    }

    private void uploadOutputs() throws IOException
    {
        WebDavClient wdc = getStorageClient(this.storage);
        VisibilityHandlerClient vhc = new VisibilityHandlerClient(wdc, null);
        int count = vhc.putVisibilityHandlerOutputs(year, month, dom, hour, minute, workDir);
        log(String.format("VisibilityHandler::uploadOutputs: uploaded %d files", count));
    }

    private void execute()
    {
        log("VisibilityHandler::execute: executing nothing...");
    }

    private void downloadInputs() throws IOException {
        WebDavClient wdc = getStorageClient(this.storage);
        VisibilityHandlerClient vhc = new VisibilityHandlerClient(wdc, null);
        int[] dlinfo = vhc.getVisibilityHandlerInputs(year, month, dom, hour, minute, workDir);
        log(String.format("VisibilityHandler::downloadInputs: downloaded %d bytes in %d files", dlinfo[1], dlinfo[0]));
    }

    public JsonObject getResult() throws Exception
    {
        //throw new Exception("this is a test exception in Stitcher::getResult()");
        JsonObject result = new JsonObject();
        result.addProperty("result", "OK");
        return result;
    }

    public static JsonObject main(JsonObject args) {
        JsonObject date = args.getAsJsonObject("datetime");
        int year = date.getAsJsonPrimitive("year").getAsInt();
        int month = date.getAsJsonPrimitive("month").getAsInt();
        int dom = date.getAsJsonPrimitive("dom").getAsInt();
        int hour = date.getAsJsonPrimitive("hour").getAsInt();
        int minute = date.getAsJsonPrimitive("minute").getAsInt();
        JsonObject storage = args.getAsJsonObject("storage");
        JsonObject response = new JsonObject();
        response.add("request", args);
        try
        {
            VisibilityHandler vh = new VisibilityHandler(year, month, dom, hour, minute, storage);
            vh.run();
            JsonObject result = vh.getResult();
            response.addProperty("result", "value");
            response.add("value", result);
            List<String> log = vh.getLog();
            JsonArray logJson = new JsonArray();
            for(String line: log)
                logJson.add(line);
            response.add("log", logJson);
        } catch (Exception e)
        {
            response.addProperty("result", "exception");
            response.addProperty("exception", e.toString());
        }
        return response;
    }
}
