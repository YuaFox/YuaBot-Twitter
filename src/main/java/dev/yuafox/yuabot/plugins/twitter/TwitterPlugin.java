package dev.yuafox.yuabot.plugins.twitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.scribejava.core.httpclient.multipart.FileByteArrayBodyPartPayload;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Verb;
import dev.yuafox.yuabot.YuaBot;
import dev.yuafox.yuabot.data.Media;

import java.io.*;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import dev.yuafox.yuabot.plugins.ActionHandler;
import dev.yuafox.yuabot.plugins.Plugin;
import io.github.redouane59.twitter.TwitterClient;
import io.github.redouane59.twitter.dto.others.RequestToken;
import io.github.redouane59.twitter.dto.tweet.MediaCategory;
import io.github.redouane59.twitter.dto.tweet.TweetParameters;
import io.github.redouane59.twitter.signature.TwitterCredentials;


public class TwitterPlugin extends Plugin {

    private TwitterClient twitterClient;

    private final File propertiesFile;
    private final Properties properties;

    public TwitterPlugin(){
        this.twitterClient = null;
        this.propertiesFile = new File(this.getBaseFolder(), "config.properties");
        this.properties = new Properties();
    }

    @Override
    public void onLoad(){
        YuaBot.registerActionHandler("twitter", this);
    }

    @ActionHandler(action="install")
    public void install(){
        try {
            String consumerKey = YuaBot.params.get("consumerKey") != null ? YuaBot.params.get("consumerKey").get(0) : null;
            String secretKey = YuaBot.params.get("secretKey") != null ? YuaBot.params.get("secretKey").get(0) : null;

            if(consumerKey == null || secretKey == null){
                System.err.println("Missing arguments: consumerKey, secretKey");
                return;
            }

            this.getBaseFolder().mkdirs();
            this.propertiesFile.createNewFile();
            this.properties.put("consumerKey", consumerKey);
            this.properties.put("secretKey", secretKey);
            this.properties.store(new FileOutputStream(propertiesFile), null);

            if(this.readCredentials()) {
                PreparedStatement statement = null;
                statement = YuaBot.dbConnection.prepareStatement("CREATE TABLE twitterPost (" +
                        "id INTEGER NOT NULL PRIMARY KEY REFERENCES post (id)," +
                        "idtweet TEXT NOT NULL" +
                        ");");
                statement.execute();
            } else {
                System.err.println("Error with credentials.");
            }
        }catch (SQLException | IOException exception){
            exception.printStackTrace();
        }
    }

    @ActionHandler(action="login")
    public void login() throws IOException {
        this.readCredentials();

        this.twitterClient.getTwitterCredentials().setAccessToken("");
        this.twitterClient.getTwitterCredentials().setAccessTokenSecret("");
        RequestToken requestToken = this.twitterClient.getOauth1Token("oob");

        YuaBot.LOGGER.info("Paste your PIN here:");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String pin = br.readLine();
        RequestToken oAuth1AccessToken = twitterClient.getOAuth1AccessToken(requestToken, pin);

        twitterClient.setTwitterCredentials(
                TwitterCredentials.builder()
                        .accessToken(oAuth1AccessToken.getOauthToken())
                        .accessTokenSecret(oAuth1AccessToken.getOauthTokenSecret())
                        .build()
        );

        this.properties.put("oAuthToken", oAuth1AccessToken.getOauthToken());
        this.properties.put("oAuthSecret", oAuth1AccessToken.getOauthTokenSecret());
        this.properties.store(new FileOutputStream(propertiesFile), null);
    }

    @ActionHandler(action="post")
    public void post() throws IOException, SQLException, ExecutionException, InterruptedException {
        // Twitter Credentials
        this.readCredentials();
        this.readAuth();

        // Fetch data
        Media media = YuaBot.getRandomMedia();

        // Upload
        String mediaId;
        if(media.media.getName().endsWith(".mp4")) {
            mediaId = this.uploadMediaChunked(media.media);
        }else
            mediaId = this.twitterClient.uploadMedia(media.media, MediaCategory.TWEET_IMAGE).getMediaId();
        twitterClient.postTweet(TweetParameters.builder().text(media.text).media(
                TweetParameters.Media.builder().mediaIds(List.of(mediaId)).build()
        ).build());
    }

    private boolean readCredentials() {
        try {
            if(!this.propertiesFile.exists()) return false;
            FileInputStream fileIn = new FileInputStream(this.propertiesFile);
            this.properties.load(fileIn);
            this.twitterClient = new TwitterClient(TwitterCredentials.builder()
                    .accessToken("")
                    .accessTokenSecret("")
                    .apiKey(this.properties.getProperty("consumerKey"))
                    .apiSecretKey(this.properties.getProperty("secretKey"))
                    .build());
            return true;
        }catch (Exception e){
            e.printStackTrace(System.err);
            return false;
        }
    }

    private void readAuth() {
        try {
            this.twitterClient = new TwitterClient(TwitterCredentials.builder()
                    .apiKey(this.properties.getProperty("consumerKey"))
                    .apiSecretKey(this.properties.getProperty("secretKey"))
                    .accessToken(this.properties.getProperty("oAuthToken"))
                    .accessTokenSecret(this.properties.getProperty("oAuthSecret"))
                    .build());
        }catch (Exception e){
            e.printStackTrace(System.err);
        }
    }

    private String uploadMediaChunked(File media) throws IOException, ExecutionException, InterruptedException {
        byte[] fileData = Files.readAllBytes(media.toPath());

        String mediaId;
        // INIT
        {
            Map<String, String> params = new HashMap<>();
            params.put("command", "INIT");
            params.put("media_type", "video/mp4");
            params.put("media_category", "amplify_video");
            params.put("total_bytes", String.valueOf(media.length()));
            JsonNode jsonNode = this.twitterClient.getRequestHelperV1().postRequest(
                    "https://upload.twitter.com/1.1/media/upload.json",
                    params,
                    JsonNode.class
            ).get();
            mediaId = jsonNode.get("media_id_string").asText();
        }

        int CHUNKSIZE = 1024*1024;
        byte[] fileChunk = new byte[CHUNKSIZE];
        int size = 0;
        for(int i = 0; i*CHUNKSIZE < fileData.length; i++){
            int pointer = i*CHUNKSIZE;
            System.out.println("("+(pointer*100/fileData.length)+"%) " + pointer + " / "+fileData.length);
            //              (src   , src-offset  , dest , offset, count)
            if(fileChunk.length < fileData.length - pointer)
                System.arraycopy(fileData, pointer   , fileChunk, 0     , fileChunk.length);
            else{
                fileChunk = new byte[fileData.length - pointer];
                System.arraycopy(fileData, pointer   , fileChunk, 0     , fileData.length - pointer);
            }
            size += fileChunk.length;

            {
                Map<String, String> params = new HashMap<>();
                params.put("command", "APPEND");
                params.put("media_id", mediaId);
                params.put("segment_index", String.valueOf(i));

                OAuthRequest request = new OAuthRequest(Verb.POST, "https://upload.twitter.com/1.1/media/upload.json");
                request.initMultipartPayload();
                request.addBodyPartPayloadInMultipartPayload(new FileByteArrayBodyPartPayload("application/octet-stream", fileChunk, "media", "document.mp4"));
                for (Map.Entry<String, String> param : params.entrySet()) {
                    request.addQuerystringParameter(param.getKey(), param.getValue());
                }
                this.twitterClient.getRequestHelperV1().getService().signRequest(this.twitterClient.getTwitterCredentials().asAccessToken(), request);
                this.twitterClient.getRequestHelperV1().getService().execute(request);
            }
        }


        {
            Map<String, String> params = new HashMap<>();
            params.put("command", "FINALIZE");
            params.put("media_id", mediaId);
            this.twitterClient.getRequestHelperV1().postRequest(
                    "https://upload.twitter.com/1.1/media/upload.json",
                    params,
                    JsonNode.class
            );
        }

        {
            JsonNode response;
            do {
                Map<String, String> params = new HashMap<>();
                params.put("command", "STATUS");
                params.put("media_id", mediaId);
                response = this.twitterClient.getRequestHelperV1().getRequestWithParameters(
                        "https://upload.twitter.com/1.1/media/upload.json",
                        params,
                        JsonNode.class
                ).orElse(null);
                Thread.sleep(2000);
            }while(response != null && response.get("processing_info").get("state").asText("").equals("in_progress"));
        }

        return mediaId;
    }
}