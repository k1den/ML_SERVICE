package org.k1den.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class LlmService {

    // Убедись, что Ollama запущена (ollama serve)
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final String MODEL = "my-llama3"; // Или "qwen:0.5b" для скорости

    private final HttpClient client;
    private final ObjectMapper mapper;

    public LlmService() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(90)) // Увеличили таймаут для анализа
                .build();
        this.mapper = new ObjectMapper();
    }

    public LlmPrediction predict(List<Double> history, int minutes, String title, String rules) {
        try {
            // СТРОГИЙ ПРОМПТ
            String prompt = String.format(
                    "Role: Strict DevOps Monitor. Task: Analyze '%s' history (last %d points): %s. " +
                            "Goal: Forecast average value for next %d min. " +
                            "RULES: %s. " +
                            "CRITICAL INSTRUCTION: If forecast is below WARN threshold, status MUST be 'OK'. Do not hallucinate warnings. " +
                            "Output JSON ONLY: {\"predictedValue\": <float>, \"status\": \"OK\"|\"WARN\"|\"ERROR\", \"reason\": \"<short text>\"}",
                    title, history.size(), history.toString(), minutes, rules
            );

            ObjectNode json = mapper.createObjectNode();
            json.put("model", "my-llama3");
            json.put("prompt", prompt);
            json.put("stream", false);
            json.put("format", "json");

            // --- ДОБАВЛЯЕМ ЭТОТ БЛОК ---
            ObjectNode options = json.putObject("options");
            options.put("num_predict", 150); // Генерировать максимум 150 токенов (хватит для JSON)
            options.put("temperature", 0.0); // Максимальная четкость, ноль фантазии
            // ---------------------------

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(json)))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                // Ollama иногда возвращает текст внутри response
                String innerJson = root.get("response").asText();

                // Чистим от маркдауна, если модель его добавила
                innerJson = innerJson.replace("```json", "").replace("```", "").trim();

                JsonNode aiData = mapper.readTree(innerJson);

                // Защита от отсутствия полей
                String status = aiData.has("status") ? aiData.get("status").asText().toUpperCase() : "UNKNOWN";
                String reason = aiData.has("reason") ? aiData.get("reason").asText() : "Analysis done";
                double val = aiData.has("predictedValue") ? aiData.get("predictedValue").asDouble() : 0.0;

                return new LlmPrediction(val, status, reason);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new LlmPrediction(0, "ERROR", "AI Connection Failed: " + e.getMessage());
        }
        return new LlmPrediction(0, "UNKNOWN", "No response from AI");
    }

    public static class LlmPrediction {
        public double value;
        public String status;
        public String reason;

        public LlmPrediction(double value, String status, String reason) {
            this.value = value;
            this.status = status;
            this.reason = reason;
        }
    }
}