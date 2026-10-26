package com.ofss.fxlock.rate.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofss.fxlock.rate.config.TwelveDataProperties;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;

import jakarta.annotation.PreDestroy;

@Component
public class TwelveDataClient {

    private static final Logger log =
            LoggerFactory.getLogger(TwelveDataClient.class);

    private static final String STREAM_URL =
            "wss://ws.twelvedata.com/v1/quotes/price";

    private static final int MAX_MESSAGE_LENGTH = 65_536;

    private final TwelveDataProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final AtomicReference<WebSocket> connection =
            new AtomicReference<>();

    private final AtomicReference<TwelveDataResponse> latestObservation =
            new AtomicReference<>();

    private volatile Instant nextConnectionAttempt = Instant.EPOCH;
    private volatile boolean stopped;

    public TwelveDataClient(
            TwelveDataProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /*
     * The existing ingestion scheduler calls this every 15 seconds.
     * Provider timestamps are preserved; reading never renews their age.
     */
    public TwelveDataResponse fetchRate() {
        WebSocket socket = connection.get();

        if (socket == null
                || socket.isInputClosed()
                || socket.isOutputClosed()) {

            throw new IllegalStateException(
                    "Twelve Data price stream is not connected"
            );
        }

        TwelveDataResponse observation = latestObservation.get();

        if (observation == null) {
            throw new IllegalStateException(
                    "Waiting for a Twelve Data EUR/USD price observation"
            );
        }

        return observation;
    }

    @Scheduled(fixedDelay = 10_000)
    public void maintainConnection() {
        if (stopped) {
            return;
        }

        WebSocket socket = connection.get();

        if (socket == null) {
            if (!Instant.now().isBefore(nextConnectionAttempt)) {
                connect();
            }

            return;
        }

        try {
            socket.sendText(
                    "{\"action\":\"heartbeat\"}",
                    true
            ).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            disconnect(socket);
        } catch (Exception exception) {
            log.warn(
                    "Twelve Data stream heartbeat failed; reconnect scheduled"
            );

            disconnect(socket);
        }
    }

    private void connect() {
        nextConnectionAttempt = Instant.now().plusSeconds(30);

        WebSocket socket = null;

        try {
            if (properties.apiKey() == null
                    || properties.apiKey().isBlank()) {

                log.error("TWELVE_DATA_API_KEY is missing");
                return;
            }

            String encodedKey = URLEncoder.encode(
                    properties.apiKey(),
                    StandardCharsets.UTF_8
            );

            URI uri = URI.create(
                    STREAM_URL + "?apikey=" + encodedKey
            );

            socket = httpClient.newWebSocketBuilder()
                    .connectTimeout(properties.connectTimeout())
                    .buildAsync(uri, new PriceListener())
                    .get(
                            properties.connectTimeout().toMillis() + 1_000,
                            TimeUnit.MILLISECONDS
                    );

            if (stopped) {
                socket.abort();
                return;
            }

            latestObservation.set(null);
            connection.set(socket);

            String subscription = objectMapper.writeValueAsString(
                    Map.of(
                            "action",
                            "subscribe",
                            "params",
                            Map.of("symbols", properties.symbol())
                    )
            );

            socket.sendText(subscription, true)
                    .get(3, TimeUnit.SECONDS);

            log.info(
                    "Twelve Data stream connected; subscription requested for {}",
                    properties.symbol()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            if (socket != null) {
                disconnect(socket);
            }
        } catch (Exception exception) {
            // Connection exceptions can contain the API key in the URI.
            log.warn(
                    "Twelve Data stream connection failed; retrying after backoff"
            );

            if (socket != null) {
                disconnect(socket);
            }
        }
    }

    private void handleMessage(
            WebSocket socket,
            String message
    ) {
        try {
            JsonNode event = objectMapper.readTree(message);

            if ("subscribe-status".equals(
                    event.path("event").asText()
            )) {
                String status = event.path("status").asText();

                if ("ok".equalsIgnoreCase(status)) {
                    log.info(
                            "Twelve Data subscription accepted for {}",
                            properties.symbol()
                    );
                } else {
                    log.error(
                            "Twelve Data subscription rejected or incomplete. "
                                    + "Check API-key permissions and WebSocket credits."
                    );

                    disconnect(socket);
                }

                return;
            }

            if (!"price".equals(event.path("event").asText())) {
                return;
            }

            if (!properties.symbol().equalsIgnoreCase(
                    event.path("symbol").asText()
            )) {
                return;
            }

            if (!event.path("timestamp").isIntegralNumber()
                    || event.path("price").isMissingNode()
                    || event.path("price").isNull()) {

                log.warn(
                        "Ignoring a stream event with missing price or timestamp"
                );

                return;
            }

            TwelveDataResponse observation =
                    new TwelveDataResponse(
                            event.path("symbol").asText(),
                            event.path("price").asText(),
                            event.path("timestamp").longValue(),
                            null,
                            null,
                            "ok"
                    );

            if (connection.get() == socket) {
                latestObservation.set(observation);

                log.debug(
                        "Received provider observation for {} at epoch {}",
                        observation.symbol(),
                        observation.timestamp()
                );
            }
        } catch (Exception exception) {
            log.warn("Ignoring an unreadable Twelve Data stream message");
        }
    }

    private void disconnect(WebSocket socket) {
        if (connection.compareAndSet(socket, null)) {
            latestObservation.set(null);
            nextConnectionAttempt = Instant.now().plusSeconds(30);
        }

        socket.abort();
    }

    @PreDestroy
    public void stop() {
        stopped = true;

        WebSocket socket = connection.getAndSet(null);
        latestObservation.set(null);

        if (socket != null) {
            socket.abort();
        }
    }

    private class PriceListener implements WebSocket.Listener {

        private final StringBuilder messageBuffer =
                new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket socket,
                CharSequence data,
                boolean last
        ) {
            if (messageBuffer.length() + data.length()
                    > MAX_MESSAGE_LENGTH) {

                messageBuffer.setLength(0);
                disconnect(socket);

                return null;
            }

            messageBuffer.append(data);

            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);

                handleMessage(socket, message);
            }

            socket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket socket,
                int statusCode,
                String reason
        ) {
            disconnect(socket);

            return null;
        }

        @Override
        public void onError(
                WebSocket socket,
                Throwable error
        ) {
            log.warn("Twelve Data stream disconnected");
            disconnect(socket);
        }
    }
}