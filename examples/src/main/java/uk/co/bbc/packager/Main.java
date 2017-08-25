package uk.co.bbc.packager;

import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.mp4parser.Container;
import org.mp4parser.muxer.MemoryDataSourceImpl;
import org.mp4parser.muxer.Movie;
import org.mp4parser.muxer.builder.EmsgFragmentedMp4Builder;
import org.mp4parser.muxer.builder.Mp4Builder;
import org.mp4parser.muxer.tracks.AACTrackImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {

    private static final String USP_URL = "";

    private int previousChunk = 0;

    private static final CloseableHttpClient httpClient = HttpClientBuilder.create().build();

    private String getNextFilename() {
        // TODO use actual timecodes, or whatever
        return String.format("./somefile-%d.aac", ++previousChunk);
    }

    private ByteBuffer getFileOrWait(String fileName) throws InterruptedException, IOException {
        Path path = Paths.get(fileName);

        System.out.println(String.format("Waiting for %s", fileName));

        while (!Files.exists(path)) {
            Thread.sleep(10);
        }

        System.out.println(String.format("Found %s", fileName));

        return ByteBuffer.wrap(Files.readAllBytes(path));
    }

    private void wrapInMp4(ByteBuffer buffer, ByteChannel channel) throws IOException {

        AACTrackImpl aacTrack = new AACTrackImpl(new MemoryDataSourceImpl(buffer));

        Movie m = new Movie();

        m.addTrack(aacTrack);

        Mp4Builder mp4Builder = new EmsgFragmentedMp4Builder();

        Container out = mp4Builder.build(m);

        out.writeContainer(channel);
    }

    private void sendPiffChunk() throws IOException {

        HttpPost post = new HttpPost(USP_URL);

        post.setEntity(new FileEntity(new File("./.tmp-box")));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int code = response.getStatusLine().getStatusCode();

            if (code != HttpStatus.SC_OK) {
                System.err.println("Http POST returned non-200: " + code);
            }

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Http POST failed");
        }
    }

    private void run() throws IOException, InterruptedException {

        String fileName = getNextFilename();

        ByteBuffer inBuffer = getFileOrWait(fileName);

        try (FileOutputStream fos = new FileOutputStream("./.tmp-box")) {

            System.out.println("Wrapping aac in mp4");
            wrapInMp4(inBuffer, fos.getChannel());

            System.out.println("Start sending chunk to USP");
            sendPiffChunk();
        }
    }

    public static void main(String[] args) throws IOException {
        try {
            Main main = new Main();
            while (true) {
                main.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("We're done here.");
        } finally {
            httpClient.close();
        }
    }
}
