package sk.microstep.icontrol;

import com.google.gson.*;
import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.Test;
import sk.microstep.icontrol.VisibilityHandler.VisibilityHandler;

import java.io.FileWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;

import static junit.framework.TestCase.assertEquals;

public class VisibilityHandlerTest {
    @Test
    public void testVisibilityHandler()
    {
        System.out.println("Working Directory = " + System.getProperty("user.dir"));
        try {
            Reader reader = Files.newBufferedReader(Paths.get("src/main/resources/in.json"));
            JsonParser parser = new JsonParser();
            JsonElement ie = parser.parse(reader);
            JsonObject inputObject = ie.getAsJsonObject();

            JsonObject response = VisibilityHandler.main(inputObject);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            FileWriter file = new FileWriter("src/main/resources/result.json");
            file.write(gson.toJson(response));
            file.close();


            assertEquals(1, 1);
        } catch (Exception e) {
            Assert.fail("Exception: " + e.toString());
        }

    }

}
