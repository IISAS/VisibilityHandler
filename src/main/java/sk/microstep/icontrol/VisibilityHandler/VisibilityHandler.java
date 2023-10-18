package sk.microstep.icontrol.VisibilityHandler;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.lang.ProcessBuilder.Redirect;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sk.uisav.icontrol.*;

import static java.lang.String.format;

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
    private final List<String> mylog;
    private final JsonObject storage;
    private final static String workDir = "/tmp/visibilityHandler";
    private final int year;
    private final int month;
    private final int dom;
    private final int hour;
    private final int minute;
    private final int pan;
    private final int azimuth;

    public VisibilityHandler(int year, int month, int dom, int hour, int min, int pan, int azimuth, JsonObject storage) throws Exception
    {
        this.mylog = new LinkedList<String>();
        this.storage = storage;
        this.year = year;
        this.month = month;
        this.dom = dom;
        this.hour = hour;
        this.minute = min;
        this.pan = pan;
        this.azimuth = azimuth;
        log(format( "VisibilityHandler::VisibilityHandler: Created visibility handler for %04d-%02d-%02d %02d:%02d/%03d/%03d",
                year, month, dom, hour, min, pan, azimuth));
    }

    private void log(String msg)
    {
        this.mylog.add(getDateTime() + " " + msg);
    }

    private List<String> getLog()
    {
        return this.mylog;
    }

    public void run() throws Exception
    {
        log("VisibilityHandler::run: creating working directory");
        Boolean res = new File(workDir).mkdirs();
        log(format("VisibilityHandler::run: working directory %s", (res ? "created" : "not created")));
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
            throw new RuntimeException(format(
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
        VisibilityHandlerClient vhc = new VisibilityHandlerClient(wdc);
        int count = vhc.putVisibilityHandlerOutput(year, month, dom, hour, minute, pan, azimuth, workDir);
        log(format("VisibilityHandler::uploadOutputs: uploaded %d files", count));
    }

    private String fileToString(String fileName) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        StringBuilder stringBuilder = new StringBuilder();
        String line = null;
        String ls = System.getProperty("line.separator");
        boolean doDeleteLastLs = false;
        while ((line = reader.readLine()) != null) {
            stringBuilder.append(line);
            stringBuilder.append(ls);
            doDeleteLastLs = true;
        }
// delete the last new line separator
        if(doDeleteLastLs)
            stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        reader.close();

        String content = stringBuilder.toString();
        return content;
    }

    public Set<String> listDirectory(String dir) throws IOException {
        try (Stream<Path> stream = Files.list(Paths.get(dir))) {
            return stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }
    }

    public String getDateTime() {
        Date date = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss.SSS");
        return dateFormat.format(date);
    }

    private void execute()
    {
        log("VisibilityHandler::execute: cp is " + System.getProperty("java.class.path"));
        String inputImg = workDir + "/panasonic_fullhd_01-" + this.pan + "-" + this.azimuth + "-" + this.year + this.month + this.dom + this.hour + this.minute + ".jpg";
        String resultPath = workDir;
        String resultName = "ImageVisibilityHandler-test_result";
        String panAzimuth = this.pan + "-" + this.azimuth;
        String generateImg = "1"; // (1-yes/0-no)
        String cfgAutomated = "/cfgs/automated";
        String cfgPrevailingVis = "/cfgs/ImagePrevailingVisibilityCfg.xml";

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", "/javaAction/libs/ImageVisibilityHandler-jar-with-dependencies.jar:/javaAction/libs/opencv-4.4.0-natives-linux-amd64.jar", "com.microstepmis.remoteObserver.visibility.automated.ImageVisibilityHandler",
                inputImg,
                resultPath,
                resultName,
                panAzimuth,
                generateImg,
                cfgAutomated,
                cfgPrevailingVis);

        String rErrName = "/tmp/pberr.txt";
        String rOutName = "/tmp/pbout.txt";

        Redirect rErr = ProcessBuilder.Redirect.to(new File(rErrName));
        Redirect rOut = ProcessBuilder.Redirect.to(new File(rOutName));

        pb.redirectError(rErr);
        pb.redirectOutput(rOut);

        log("VisibilityHandler::execute: Run ImageVisibilityHandler-jar-with-dependencies.jar with parameters: \n"
                + "INPUT IMG: " + inputImg + "\n"
                + "RESULT PATH: " + resultPath + "\n"
                + "RESULT NAME: " + resultName + "\n"
                + "PAN and AZIMUTH: " + this.pan + "-" + this.azimuth + "\n"
                + "GENERATE IMAGE (1-yes/0-no): " + generateImg + "\n"
                + "CFG_AUTOMATED FOLDER: " + cfgAutomated + "\n"
                + "CFG_PREVAILING_VIS: " + cfgPrevailingVis);

        pb.directory(new File(workDir));
        log("VisibilityHandler::execute: executing...");
        try {
            Set<String> filesInDir = listDirectory(workDir);
            for(String filename: filesInDir)
                log("VisibilityHandler::execute: before file \"" + filename + "\"");
            Process proc = pb.start();
            log("VisibilityHandler::execute: before WaitFor()");
            proc.waitFor();
            log("VisibilityHandler::execute: after WaitFor()");
            filesInDir = listDirectory(workDir);
            for(String filename: filesInDir)
                log("VisibilityHandler::execute: after file \"" + filename + "\"");
            String errOut = fileToString(rErrName);
            log("VisibilityHandler::execute: stderr: " + errOut);
            String stdOut = fileToString(rOutName);
            log("VisibilityHandler::execute: stdout: " + stdOut);
        } catch (IOException e) {
            log("VisibilityHandler::execute: Process failed: \n" + e.toString());
        } catch (InterruptedException e) {
            log("VisibilityHandler::execute: Process interrupted: \n" + e.toString());
        }

        log("VisibilityHandler::execute: DONE");
    }

    private void downloadInputs() throws IOException {
        WebDavClient wdc = getStorageClient(this.storage);
        VisibilityHandlerClient vhc = new VisibilityHandlerClient(wdc);
        long dlinfo = vhc.getVisibilityHandlerInput(year, month, dom, hour, minute, pan, azimuth, workDir);
        log(format("VisibilityHandler::downloadInputs: downloaded %d bytes", dlinfo));
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
        int pan = date.getAsJsonPrimitive("pan").getAsInt();
        int azimuth = date.getAsJsonPrimitive("azimuth").getAsInt();
        JsonObject storage = args.getAsJsonObject("storage");
        JsonObject response = new JsonObject();
        response.add("request", args);
        VisibilityHandler vh = null;
        try
        {
            vh = new VisibilityHandler(year, month, dom, hour, minute, pan, azimuth, storage);
        }
        catch (Exception e)
        {
            response.addProperty("result", "exception");
            response.addProperty("exception", e.toString());
        }
        try
        {
            vh.run();
            JsonObject result = vh.getResult();
            response.addProperty("result", "value");
            response.add("value", result);
        } catch (Exception e)
        {
            response.addProperty("result", "exception");
            response.addProperty("exception", e.toString());
        }
        finally {
            List<String> log = vh.getLog();
            JsonArray logJson = new JsonArray();
            for(String line: log)
                logJson.add(line);
            response.add("log", logJson);
        }
        return response;
    }
}
