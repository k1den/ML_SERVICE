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
    private static final String MODEL = "my-llama3"; // Твоя модель

    private final HttpClient client;
    private final ObjectMapper mapper;

    public LlmService() {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build(); // Увеличил таймаут для 150 точек
        this.mapper = new ObjectMapper();
    }

    public LlmPrediction predict(List<Double> history, int minutes, String title, String rules) {
        // 1. Считаем количество точек (5 точек на каждую минуту)
        int expectedPoints = minutes * 5;

        try {
            // 2. Агрессивный промпт, запрещающий прямые линии
            String prompt = String.format(
                    "Role: Expert DevOps AI. Task: Analyze '%s' history: %s. " +
                            "Goal: Forecast EXACTLY %d future values spaced evenly over the next %d minutes. " +
                            "CRITICAL INSTRUCTION: Do NOT output a simple linear progression or straight line! Real system metrics are noisy. " +
                            "You MUST include realistic micro-fluctuations, minor jitter, and occasional small spikes/drops along the overall trend. " +
                            "RULES: %s. " +
                            "Output JSON ONLY: {\"predictedValues\": [<exactly %d floats>], \"status\": \"OK\"|\"WARN\"|\"ERROR\", \"reason\": \"<short text>\"}",
                    title, history.toString(), expectedPoints, minutes, rules, expectedPoints
            );

            ObjectNode json = mapper.createObjectNode();
            json.put("model", MODEL);
            json.put("prompt", prompt);
            json.put("stream", false);
            json.put("format", "json");

            // 3. Даем свободу и расширяем лимиты
            ObjectNode options = json.putObject("options");
            options.put("num_predict", 3000); // 150 точек = огромный JSON, нужен большой лимит
            options.put("temperature", 0.6);  // Подняли температуру, чтобы появилась "дрожь" на графике

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

                // Если LLM обрезала массив или не выдала его, кладем хоть что-то
                if (values.isEmpty()) {
                    values.add(aiData.has("predictedValue") ? aiData.get("predictedValue").asDouble() : 0.0);
                }

                return new LlmPrediction(values, status, reason);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new LlmPrediction(List.of(0.0), "ERROR", "AI Failed: " + e.getMessage());
        }
        return new LlmPrediction(List.of(0.0), "UNKNOWN", "No response");
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