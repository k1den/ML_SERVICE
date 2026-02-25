package org.k1den;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class LlmPredictionFunction extends RichAsyncFunction<DeviceTimeSeries, String> {

    // Временный пул потоков для имитации HTTP клиента
    private transient ExecutorService executorService;

    @Override
    public void open(Configuration parameters) throws Exception {
        executorService = Executors.newFixedThreadPool(10);
    }

    @Override
    public void close() throws Exception {
        executorService.shutdown();
    }

    @Override
    public void asyncInvoke(DeviceTimeSeries input, ResultFuture<String> resultFuture) {
        // Запускаем асинхронную задачу
        CompletableFuture.supplyAsync(new Supplier<String>() {
            @Override
            public String get() {
                // !!! ЗДЕСЬ КОД ВЫЗОВА TVOЕЙ LLM !!!
                // Например, HTTP POST запрос к OpenAI / Ollama / GigaChat

                // Формируем промпт
                String prompt = "Анализируй загрузку CPU: " + input.cpuLoadHistory.toString() +
                        ". Дай прогноз среднего значения на следующие 30 минут одним числом.";

                // Имитация задержки сети (LLM думает)
                try { Thread.sleep(1000); } catch (InterruptedException e) {}

                // Возвращаем фейковый ответ (замени на response.getBody())
                double lastValue = input.cpuLoadHistory.get(input.cpuLoadHistory.size() - 1);
                double prediction = lastValue * 1.05; // Прогноз: +5%

                return "Device: " + input.deviceId + " | Forecast CPU: " + prediction;
            }
        }, executorService).thenAccept( (String result) -> {
            // Когда ответ получен, передаем его дальше в Flink
            resultFuture.complete(Collections.singleton(result));
        });
    }
}