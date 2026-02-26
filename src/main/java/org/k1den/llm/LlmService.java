package org.k1den.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LlmService {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL = "my-llama3";

    private final HttpClient client;
    private final ObjectMapper mapper;

    public LlmService() {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(90)).build();
        this.mapper = new ObjectMapper();
    }

    public LlmPrediction predict(List<Double> history, int minutes, String title, String rules) {
        try {
            // 5 промежуточных точек, чтобы была не рпямая линия
            String prompt = String.format(
                    "Role: Strict DevOps Monitor. Task: Analyze '%s' history (last %d points): %s. " +
                            "Goal: Forecast EXACTLY 5 intermediate values spaced evenly over the next %d min to show the trend. " +
                            "RULES: %s. " +
                            "Output JSON ONLY: {\"predictedValues\": [<float>, <float>, <float>, <float>, <float>], \"status\": \"OK\"|\"WARN\"|\"ERROR\", \"reason\": \"<short text>\"}",
                    title, history.size(), history.toString(), minutes, rules
            );

            ObjectNode json = mapper.createObjectNode();
            json.put("model", MODEL);
            json.put("prompt", prompt);
            json.put("stream", false);
            json.put("format", "json");

            ObjectNode options = json.putObject("options");
            options.put("num_predict", 250);
            options.put("temperature", 0.1);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(json)))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                String innerJson = root.get("response").asText().replace("```json", "").replace("```", "").trim();
                JsonNode aiData = mapper.readTree(innerJson);

                String status = aiData.has("status") ? aiData.get("status").asText().toUpperCase() : "UNKNOWN";
                String reason = aiData.has("reason") ? aiData.get("reason").asText() : "Analysis done";

                List<Double> values = new ArrayList<>();
                if (aiData.has("predictedValues") && aiData.get("predictedValues").isArray()) {
                    for (JsonNode node : aiData.get("predictedValues")) {
                        values.add(node.asDouble());
                    }
                }

                // Защита, если LLM всё-таки вернула одно число
                if (values.isEmpty()) {
                    values.add(aiData.has("predictedValue") ? aiData.get("predictedValue").asDouble() : 0.0);
                }

                return new LlmPrediction(values, status, reason);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new LlmPrediction(List.of(0.0), "ERROR", "AI Connection Failed: " + e.getMessage());
        }
        return new LlmPrediction(List.of(0.0), "UNKNOWN", "No response from AI");
    }

    public static class LlmPrediction {
        public List<Double> predictedValues;
        public String status;
        public String reason;

        public LlmPrediction(List<Double> predictedValues, String status, String reason) {
            this.predictedValues = predictedValues;
            this.status = status;
            this.reason = reason;
        }
    }
}