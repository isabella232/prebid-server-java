package org.rtb.vexing.cache;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.rtb.vexing.cache.model.BidCacheResult;
import org.rtb.vexing.cache.model.request.BidCacheRequest;
import org.rtb.vexing.cache.model.request.PutObject;
import org.rtb.vexing.cache.model.request.PutValue;
import org.rtb.vexing.cache.model.response.BidCacheResponse;
import org.rtb.vexing.config.ApplicationConfig;
import org.rtb.vexing.exception.PreBidException;
import org.rtb.vexing.model.response.Bid;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Interacts with prebid cache service
 */
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    private static final long HTTP_REQUEST_TIMEOUT = 1000L; // FIXME: request should be bounded by client timeout
    private static final String APPLICATION_JSON =
            HttpHeaderValues.APPLICATION_JSON.toString() + ";" + HttpHeaderValues.CHARSET.toString() + "=" + "utf-8";

    private final HttpClient httpClient;
    private final String endpointUrl;
    private final String cachedAssetUrlTemplate;

    private CacheService(HttpClient httpClient, String endpointUrl, String cachedAssetUrlTemplate) {
        this.httpClient = httpClient;
        this.endpointUrl = endpointUrl;
        this.cachedAssetUrlTemplate = cachedAssetUrlTemplate;
    }

    public static CacheService create(HttpClient httpClient, ApplicationConfig config) {
        Objects.requireNonNull(httpClient);
        Objects.requireNonNull(config);

        return new CacheService(httpClient, getEndpointUrl(config), getCachedAssetUrlTemplate(config));
    }

    public Future<List<BidCacheResult>> saveBids(List<Bid> bids) {
        Objects.requireNonNull(bids);
        if (bids.isEmpty()) {
            return Future.succeededFuture(Collections.emptyList());
        }

        final Future<List<BidCacheResult>> future = Future.future();
        httpClient.postAbs(endpointUrl, response -> handleResponse(response, bids, future))
                .exceptionHandler(exception -> handleException(exception, future))
                .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
                .putHeader(HttpHeaders.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
                .setTimeout(HTTP_REQUEST_TIMEOUT)
                .end(Json.encode(toBidCacheRequest(bids)));
        return future;
    }

    private BidCacheRequest toBidCacheRequest(List<Bid> bids) {
        final List<PutObject> putObjects = bids.stream()
                .map(bid -> PutObject.builder()
                        .type("json")
                        .value(PutValue.builder()
                                .adm(bid.adm)
                                .nurl(bid.nurl)
                                .width(bid.width)
                                .height(bid.height)
                                .build())
                        .build())
                .collect(Collectors.toList());

        return BidCacheRequest.builder()
                .puts(putObjects)
                .build();
    }

    private void handleResponse(HttpClientResponse response, List<Bid> bids, Future<List<BidCacheResult>> future) {
        response
                .bodyHandler(buffer -> handleResponseAndBody(response.statusCode(), buffer.toString(), bids, future))
                .exceptionHandler(exception -> handleException(exception, future));
    }

    private void handleResponseAndBody(int statusCode, String body, List<Bid> bids,
                                       Future<List<BidCacheResult>> future) {

        if (statusCode != 200) {
            logger.warn("Cache service response code is {0}, body: {1}", statusCode, body);
            future.fail(new PreBidException(String.format("HTTP status code %d", statusCode)));
        } else {
            processBidCacheResponse(body, bids, future);
        }
    }

    private void handleException(Throwable exception, Future<List<BidCacheResult>> future) {
        logger.warn("Error occurred while sending request to cache service", exception);
        future.fail(exception);
    }

    private void processBidCacheResponse(String body, List<Bid> bids, Future<List<BidCacheResult>> future) {
        final BidCacheResponse bidCacheResponse;
        try {
            bidCacheResponse = Json.decodeValue(body, BidCacheResponse.class);
        } catch (DecodeException e) {
            logger.warn("Error occurred while parsing bid cache response: {0}", body, e);
            future.fail(e);
            return;
        }

        if (bidCacheResponse.responses == null || bidCacheResponse.responses.size() != bids.size()) {
            future.fail(new PreBidException("Put response length didn't match"));
            return;
        }

        final List<BidCacheResult> results = bidCacheResponse.responses.stream()
                .map(cacheResponse -> BidCacheResult.builder()
                        .cacheId(cacheResponse.uuid)
                        .cacheUrl(getCachedAssetURL(cacheResponse.uuid))
                        .build())
                .collect(Collectors.toList());
        future.complete(results);
    }

    public String getCachedAssetURL(String uuid) {
        Objects.requireNonNull(uuid);
        return cachedAssetUrlTemplate.replaceFirst("%PBS_CACHE_UUID%", uuid);
    }

    private static String getEndpointUrl(ApplicationConfig config) {
        try {
            final URL baseUrl = getBaseUrl(config);
            return new URL(baseUrl, "/cache").toString();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not get cache endpoint for prebid cache service", e);
        }
    }

    private static String getCachedAssetUrlTemplate(ApplicationConfig config) {
        try {
            final URL baseUrl = getBaseUrl(config);
            return new URL(baseUrl, "/cache?" + config.getString("cache.query")).toString();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not get cached asset url template for prebid cache service", e);
        }
    }

    private static URL getBaseUrl(ApplicationConfig config) throws MalformedURLException {
        return new URL(config.getString("cache.scheme") + "://" + config.getString("cache.host"));
    }
}