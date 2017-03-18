package com.openroar.imageanalysis.notify;

/**
 * Created by jamesjackson on 2/26/17.
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
import com.dropbox.core.v2.files.*;
import redis.clients.jedis.Jedis;

import java.io.*;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
            messages = jedis.blpop(0,"notify_queue");
            System.out.println("Got the message");
            System.out.println("KEY:" + messages.get(0) + " VALUE:" + messages.get(1));
            String payload = messages.get(1);

            File localFile = new File(Config.getProperty("darknet_path") + "/images_detections/" + payload);

            uploadFile(dbxClient, localFile,"/detected_images/" + payload);

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


    /**
     * Uploads a file in a single request. This approach is preferred for small files since it
     * eliminates unnecessary round-trips to the servers.
     *
     * @param dbxClient Dropbox user authenticated client
    //     * @param localFIle local file to upload
    //     * @param dropboxPath Where to upload the file to within Dropbox
     */
    private static void uploadFile(DbxClientV2 dbxClient, File localFile, String dropboxPath) {
        try (InputStream in = new FileInputStream(localFile)) {
            FileMetadata metadata = dbxClient.files().uploadBuilder(dropboxPath)
                    .withMode(WriteMode.ADD)
                    .withClientModified(new Date(localFile.lastModified()))
                    .uploadAndFinish(in);

            System.out.println(metadata.toStringMultiline());
        } catch (UploadErrorException ex) {
            System.err.println("Error uploading to Dropbox: " + ex.getMessage());
            System.exit(1);
        } catch (DbxException ex) {
            System.err.println("Error uploading to Dropbox: " + ex.getMessage());
            System.exit(1);
        } catch (IOException ex) {
            System.err.println("Error reading from file \"" + localFile + "\": " + ex.getMessage());
            System.exit(1);
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