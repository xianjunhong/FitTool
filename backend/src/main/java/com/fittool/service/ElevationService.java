package com.fittool.service;

import com.fittool.domain.Point;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;




@Service
@Slf4j
public class ElevationService {



    private final RestClient restClient;
    private final boolean enabled;
    private final int batchSize;

    public ElevationService(RestClient.Builder restClientBuilder,
                            @Value("${elevation.enabled}") boolean enabled,
                            @Value("${elevation.base-url}") String baseUrl,
                            @Value("${elevation.connect-timeout-ms}") int connectTimeoutMs,
                            @Value("${elevation.read-timeout-ms}") int readTimeoutMs,
                            @Value("${elevation.batch-size}") int batchSize) {
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public List<Double> fetchAltitudesOrNull(List<Point> points) {
        if (!enabled || points == null || points.isEmpty()) {
            return null;
        }

        try {
            return fetchAltitudes(points);
        } catch (Exception ex) {
            log.warn("Open-Elevation 请求失败，回退到本地模拟海拔: {}", ex.getMessage());
            return null;
        }
    }

    private List<Double> fetchAltitudes(List<Point> points) {
        List<Double> altitudes = new ArrayList<>(points.size());

        for (int start = 0; start < points.size(); start += batchSize) {
            int end = Math.min(start + batchSize, points.size());
            List<Point> chunk = points.subList(start, end);

            List<ElevationLocation> locations = new ArrayList<>(chunk.size());
            for (Point point : chunk) {
                locations.add(new ElevationLocation(point.getLat(), point.getLng()));
            }

            ElevationLookupResponse response = restClient.post()
                    .uri("/api/v1/lookup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new ElevationLookupRequest(locations))
                    .retrieve()
                    .body(ElevationLookupResponse.class);

            if (response == null || response.getResults() == null || response.getResults().size() != chunk.size()) {
                throw new IllegalStateException("Open-Elevation 返回数量与请求数量不一致");
            }

            for (ElevationResult result : response.getResults()) {
                altitudes.add(result.getElevation() == null ? 0D : result.getElevation());
            }
        }

        return altitudes;
    }

    public static class ElevationLookupRequest {
        private List<ElevationLocation> locations;

        public ElevationLookupRequest() {
        }

        public ElevationLookupRequest(List<ElevationLocation> locations) {
            this.locations = locations;
        }

        public List<ElevationLocation> getLocations() {
            return locations;
        }

        public void setLocations(List<ElevationLocation> locations) {
            this.locations = locations;
        }
    }

    public static class ElevationLookupResponse {
        private List<ElevationResult> results;

        public List<ElevationResult> getResults() {
            return results;
        }

        public void setResults(List<ElevationResult> results) {
            this.results = results;
        }
    }

    public  static class ElevationLocation {
        private Double latitude;
        private Double longitude;

        public ElevationLocation() {
        }

        public ElevationLocation(Double latitude, Double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }
    }

    public static class ElevationResult {
        private Double latitude;
        private Double longitude;
        private Double elevation;

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }

        public Double getElevation() {
            return elevation;
        }

        public void setElevation(Double elevation) {
            this.elevation = elevation;
        }
    }
}