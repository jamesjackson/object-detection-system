package com.openroar.imageanalysis.detect;

/**
 * Created by jamesjackson on 2/25/17.
 */

import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxAuthInfo;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.json.JsonReader;
import com.dropbox.core.http.StandardHttpRequestor;
import com.dropbox.core.v1.DbxEntry;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderGetLatestCursorResult;
import com.dropbox.core.v2.files.ListFolderLongpollResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import org.json.JSONArray;
import org.json.JSONObject;
import redis.clients.jedis.Jedis;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void trackQueue(DbxAuthInfo auth) throws Exception {

        StandardHttpRequestor.Config config = StandardHttpRequestor.Config.DEFAULT_INSTANCE;
        DbxClientV2 dbxClient = createClient(auth, config);
        Jedis jedis = new Jedis("localhost");
        List<String> messages = null;

        while(true){

            System.out.println("Waiting for a message in the queue");
            messages = jedis.blpop(0,"new_image_queue");
            System.out.println("Got the message");
            System.out.println("KEY:" + messages.get(0) + " VALUE:" + messages.get(1));
            String payload = messages.get(1);

            HashSet<String> exSet = readToHashset();

            detectObjects(exSet, payload);

        }
    }

    /**
     * Create a new Dropbox client using the given authentication
     * information and HTTP client config.
     *
     * @param auth Authentication information
     * @param config HTTP request configuration
     *
     * @return new Dropbox V2 client
     */
    private static DbxClientV2 createClient(DbxAuthInfo auth, StandardHttpRequestor.Config config) {
        String clientUserAgentId = "examples-longpoll";
        StandardHttpRequestor requestor = new StandardHttpRequestor(config);
        DbxRequestConfig requestConfig = DbxRequestConfig.newBuilder(clientUserAgentId)
                .withHttpRequestor(requestor)
                .build();

        return new DbxClientV2(requestConfig, auth.getAccessToken(), auth.getHost());
    }


    public static HashSet<String> readToHashset() throws Exception {
        HashSet<String> set=new HashSet<String>();

        try {
            BufferedReader in = new BufferedReader(new FileReader("exclusions"));
            String line = "";
            while ((line = in.readLine()) != null) {
                set.add(line);
            }
            in.close();

        } catch(FileNotFoundException e) {
            System.err.println("exclusions file not found");
            System.exit(1);
        }
        return set;
    }

    public static void detectObjects(HashSet<String> exSet, String fileName) throws IOException {

        long unixTime = System.currentTimeMillis() / 1000L;

        ProcessBuilder pb = new ProcessBuilder(
                Config.getProperty("darknet_path") + "/darknet", "detect", "cfg/yolo.cfg", "yolo.weights", "images_download/" + fileName);

        pb.directory(new File(Config.getProperty("darknet_path")));

        Process process = pb.start();

        InputStream is = process.getInputStream();
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader br = new BufferedReader(isr);
        String line;

        JSONObject event = new JSONObject();
        event.put("filename", fileName);
        JSONArray objectList = new JSONArray();

        int detectionCount = 0;

        while ((line = br.readLine()) != null) {
            boolean b = line.endsWith("%");
            if (b) {
                String detectedObject = line.substring(0, line.indexOf(":"));
                if (!exSet.contains(detectedObject)) {
                    String detectedObjectProb = line.substring(line.indexOf(":") + 2, line.indexOf("%"));
                    int intProb = Integer.parseInt(detectedObjectProb);
                    System.out.println(line);
                    JSONObject obj = new JSONObject();
                    obj.put(detectedObject, intProb);
                    objectList.put(obj);

                    detectionCount ++;
                }
            }
        }

        if (detectionCount > 0) {
            BufferedImage img = ImageIO.read(new File(Config.getProperty("darknet_path") + "/predictions.png"));
            ImageIO.write(img, "jpg", new File(Config.getProperty("darknet_path") + "/images_detections/" + fileName));

            event.put("objects", objectList);

            Jedis jedis = new Jedis("localhost");
            jedis.rpush("notify_queue", fileName);

            jedis.zadd("events", unixTime, event.toString());

        } else {
            File file = new File(Config.getProperty("darknet_path") + "/images_download/" + fileName);
            file.delete();
        }

    }

    public static void main(String[] args) throws Exception {
        // Only display important log messages.
        Logger.getLogger("").setLevel(Level.WARNING);

        if (args.length != 1) {
            System.out.println("");
            System.out.println("Usage: COMMAND <auth-file> <dropbox-path>");
            System.out.println("");
            System.out.println(" <auth-file>: An \"auth file\" that contains the information necessary to make");
            System.out.println("    an authorized Dropbox API request.  Generate this file using the \"authorize\"");
            System.out.println("    example program.");
            System.out.println("");
            System.exit(1);
            return;
        }

        String authFile = args[0];

        // Read auth info file.
        DbxAuthInfo auth;
        try {
            auth = DbxAuthInfo.Reader.readFromFile(authFile);
        }
        catch (JsonReader.FileLoadException ex) {
            System.err.println("Error loading <auth-file>: " + ex.getMessage());
            System.exit(1); return;
        }

        new Config();

        trackQueue(auth);

    }
}