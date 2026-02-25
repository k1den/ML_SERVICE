package org.k1den;

import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.util.concurrent.TimeUnit;

public class ForecastService {

    public static void main(String[] args) throws Exception {
        // 1. Настройка окружения
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        String chUrl = "jdbc:clickhouse://localhost:8123/default";

        // 2. Источник: Опрос ClickHouse каждые 60 секунд
        DataStream<DeviceTimeSeries> metricStream = env.addSource(
                new ClickHousePollingSource(chUrl, 60000)
        ).name("ClickHouse Poller");

        // 3. Обработка: Асинхронный вызов LLM
        // unorderedWait означает, что порядок ответов не важен (быстрее)
        // Таймаут 10 секунд на ответ от LLM
        DataStream<String> predictions = AsyncDataStream.unorderedWait(
                metricStream,
                new LlmPredictionFunction(),
                10000,
                TimeUnit.MILLISECONDS,
                100
        ).name("LLM Predictor");

        // 4. Sink: Вывод результата (или запись обратно в Kafka/ClickHouse)
        predictions.print();

        // 5. Запуск
        env.execute("LLM Forecasting Service");
    }
}