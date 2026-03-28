package com.example.mockserver;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class MockLLMServer {

    private static final int OUTPUT_TOKENS = 20;
    private static final int INPUT_TOKENS = 10;
    private static final long TTFT_DELAY_MS = 500;            // Time to first token (prompt processing)
    private static final long TOTAL_GENERATION_MS = 3000;      // Total token generation time

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/generate", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // Read request body
                String requestBody;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                    requestBody = reader.lines().collect(Collectors.joining());
                }

                JSONObject requestJson = new JSONObject(requestBody);
                boolean streaming = requestJson.optBoolean("stream", false);

                // Simulate TTFT delay (processing prompt before first token)
                sleep(TTFT_DELAY_MS);

                if (streaming) {
                    // --- Streaming mode ---
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0); // chunked

                    OutputStream os = exchange.getResponseBody();

                    // Send first token immediately so JMeter records latency (TTFT)
                    JSONObject firstChunk = new JSONObject();
                    firstChunk.put("token", "tok_1");
                    firstChunk.put("index", 1);
                    firstChunk.put("finished", false);
                    os.write(("data: " + firstChunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();

                    // Stream remaining tokens with delay
                    long delayPerToken = TOTAL_GENERATION_MS / OUTPUT_TOKENS;
                    for (int i = 2; i <= OUTPUT_TOKENS; i++) {
                        sleep(delayPerToken);

                        JSONObject chunk = new JSONObject();
                        chunk.put("token", "tok_" + i);
                        chunk.put("index", i);
                        chunk.put("finished", i == OUTPUT_TOKENS);

                        if (i == OUTPUT_TOKENS) {
                            chunk.put("input_tokens", INPUT_TOKENS);
                            chunk.put("output_tokens", OUTPUT_TOKENS);
                        }

                        os.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }

                    os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();

                } else {
                    // --- Non-streaming mode ---
                    // Use chunked transfer so we can flush partial data for TTFT measurement
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0); // 0 = chunked transfer

                    OutputStream os = exchange.getResponseBody();

                    // Write a single space and flush immediately — JMeter records latency (TTFT) here
                    os.write(' ');
                    os.flush();

                    // Simulate token generation delay
                    sleep(TOTAL_GENERATION_MS);

                    // Write the actual JSON response
                    String response = "{\"input_tokens\": " + INPUT_TOKENS
                            + ", \"output_tokens\": " + OUTPUT_TOKENS
                            + ", \"response\": \"Hello, world!\"}";
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.close();
                }
            }
        });

        server.start();
        System.out.println("Mock LLM Server is running on http://localhost:8080");
        System.out.println("  Non-streaming: {\"prompt\": \"...\", \"stream\": false}");
        System.out.println("  Streaming:     {\"prompt\": \"...\", \"stream\": true}");
        System.out.println("  TTFT delay: " + TTFT_DELAY_MS + "ms, Generation time: " + TOTAL_GENERATION_MS + "ms");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}