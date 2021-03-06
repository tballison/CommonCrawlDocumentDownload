package org.dstadler.commoncrawl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.poi.util.HexDump;
import org.apache.poi.util.LittleEndian;
import org.dstadler.commons.logging.jdk.LoggerFactory;

import com.google.common.base.Preconditions;


public class Utils {
    private final static Logger log = LoggerFactory.make();

    // avoid having to read the header data always during testing, can be removed later...
    public static final int INDEX_BLOCK_COUNT = 2644;
    public static final int BLOCK_SIZE = 65536;

    public static final String INDEX_URL = "https://aws-publicdatasets.s3.amazonaws.com/common-crawl/projects/url-index/url-index.1356128792";
    public static final int HEADER_BLOCK_SIZE = 8;
    public static final File DOWNLOAD_DIR = new File("download");
    public static final File COMMONURLS_PATH = new File("commonurls.txt");

    public static HttpEntity checkAndFetch(CloseableHttpResponse response, String url) throws IOException {
        int statusCode = response.getStatusLine().getStatusCode();
        if(statusCode > 206) {
            String msg = "Had HTTP StatusCode " + statusCode + " for request: " + url + ", response: " +
                    response.getStatusLine().getReasonPhrase();
            log.warning(msg);

            throw new IOException(msg);
        }
        return response.getEntity();
    }


    public static Pair<Long, Long> readStartPos(CloseableHttpClient client) throws IOException, ClientProtocolException {
        log.info("Reading header from " + INDEX_URL);
        HttpGet httpGet = new HttpGet(INDEX_URL);
        try (CloseableHttpResponse response = client.execute(httpGet)) {
            HttpEntity entity = Utils.checkAndFetch(response, INDEX_URL);

            try (InputStream stream = entity.getContent()) {
                try {
                    // try with the first few bytes initially
                    byte[] header = new byte[HEADER_BLOCK_SIZE];
                    stream.read(header);

                    HexDump.dump(header, 0, System.out, 0);

                    long blockSize = LittleEndian.getUInt(header, 0);
                    long indexBlockCount = LittleEndian.getUInt(header, 4);
                    log.info("Header: blockSize " + blockSize + ", indexBlockCount: " + indexBlockCount);

                    return ImmutablePair.of(blockSize, HEADER_BLOCK_SIZE + (blockSize * indexBlockCount));
                } finally {
                    // always abort reading here inside the finally block of the InputStream as
                    // otherwise HttpClient tries to read the stream fully, which is at least 270GB...
                    httpGet.abort();
                }
            }
        }
    }

    public static String reverseDomain(String host) {
        if(StringUtils.isEmpty(host)) {
            return host;
        }

        String[] parts = host.split("\\.");
        Preconditions.checkState(parts.length > 0, "Should have some parts, but did not found any for %s", host);

        StringBuilder builder = new StringBuilder();
        for(int i = parts.length-1;i>= 0;i--) {
            builder.append(parts[i]).append(".");
        }

        // remove trailing dot
        builder.setLength(builder.length()-1);

        return builder.toString();
    }

    public static URI convertUrl(String urlStr) throws URISyntaxException {
        if(urlStr.endsWith(":http")) {
            urlStr = "http://" + StringUtils.removeEnd(urlStr, ":http");
        } else if (urlStr.endsWith(":https")) {
            urlStr = "https://" + StringUtils.removeEnd(urlStr, ":https");
        } else {
            urlStr = "http://" + urlStr;
        }

        // some URLs contain invalid characters, for now remove them although this
        // likely makes the URL invalid unless it is some query-parameter which is not
        // actually significant for the URL
        URI url = new URI(urlStr.replace("[", "").replace("]", ""));

        // convert the host of the URL from tld-first
        return new URI(url.getScheme(), url.getUserInfo(), Utils.reverseDomain(url.getHost()),
                url.getPort(), url.getPath(), url.getQuery(), url.getFragment());
    }

    public static boolean handleBlock(long blockIndex, int blockSize, InputStream stream, BlockProcessor processor) throws IOException {
        byte[] block = new byte[blockSize];
        int read = IOUtils.read(stream, block);
        if(read == 0) {
            log.info("EOF => stopping processing");
            return false;
        }

        //FileUtils.writeByteArrayToFile(new File("/tmp/block" + i + ".bin"), block);
        //HexDump.dump(block, 0, System.out, 0, block.length);
        //log.info("Block number " + i);

        processor.offer(block, blockIndex);
        return true;
    }


    public static void logProgress(long startPos, int blockSize, int skipBlocks, long startTs, long blockIndex, int logStep, long fileLength) {
        if(blockIndex % logStep == 0) {
            long diff = System.currentTimeMillis()-startTs;
            double diffSec = ((double)diff)/1000;
            long readBlocks = blockIndex - skipBlocks;
            long currentPos = startPos + (readBlocks * blockSize);

            log.info("Reading block " + blockIndex + " at position " + currentPos +
                    " fetched " + readBlocks + " blocks in " + diffSec + " s," +
                    " with " + readBlocks/diffSec + " per second" +
                    (fileLength > 0 ? ", " + (((double)currentPos)/fileLength*100) + "% of " + fileLength + " bytes done" : ""));
        }
    }


    public static boolean isCorruptDownload(File file) throws IOException {
        try (InputStream stream = new FileInputStream(file)) {
            byte[] start = new byte[100];
            stream.read(start);
            String string = new String(start, "UTF-8").trim().toLowerCase();
            if(string.startsWith("<!doctype html") ||
                    string.startsWith("<html") ||
                    string.startsWith("<!--[if ie")) {
                return true;
            }
        }
        return false;
    }

    public static File computeDownloadFileName(String urlStr, String postfix) {
        // try to simply use the full url with only slashes replaced
        String replace = StringUtils.removeEnd(urlStr, ":http").
                replace("/", "_").replace("[", "(").replace("]", ")");
        if(replace.length() > 240) {
            replace = replace.substring(0, 240) + "..." + FilenameUtils.getExtension(replace);
        }
        return new File(DOWNLOAD_DIR, replace + postfix);
    }
}
