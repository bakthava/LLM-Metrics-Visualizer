package com.example.jmeter;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.json.JSONObject;

public class LLMMetricsVisualizer extends AbstractVisualizer {

    private static final long serialVersionUID = 1L;

    private static final String[] COLUMN_NAMES = {
        "Request Name", "Request Count", "RPS (req/sec)", "Min (ms)", "Avg (ms)", "Max (ms)",
        "90th Percentile (ms)", "TTFT (ms)", "TPOT (ms)", "Input Tokens/Req",
        "Output Tokens/Req", "Input Tokens/Min", "Output Tokens/Min",
        "Total Input Tokens", "Total Output Tokens"
    };

    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0);
    private final JTable metricsTable = new JTable(tableModel);
    private final ConcurrentHashMap<String, SamplerStats> statsMap = new ConcurrentHashMap<>();
    private volatile long testStartTime = 0;

    /** Holds aggregated stats per sampler name */
    private static class SamplerStats {
        long count = 0;
        long minTime = Long.MAX_VALUE;
        long maxTime = 0;
        long totalTime = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        long totalTtft = 0;
        long totalTpot = 0;
        long tpotSampleCount = 0;
        final List<Long> responseTimes = new ArrayList<>();

        synchronized void addSample(long responseTime, long latency, int inputTokens, int outputTokens) {
            count++;
            totalTime += responseTime;
            if (responseTime < minTime) minTime = responseTime;
            if (responseTime > maxTime) maxTime = responseTime;
            totalInputTokens += inputTokens;
            totalOutputTokens += outputTokens;
            responseTimes.add(responseTime);

            // TTFT = latency (time to first byte of response)
            totalTtft += latency;

            // TPOT = (total response time - latency) / output tokens
            if (outputTokens > 0) {
                long generationTime = responseTime - latency;
                totalTpot += (generationTime > 0 ? generationTime : 0) / outputTokens;
                tpotSampleCount++;
            }
        }

        synchronized long getAvgTtft() {
            return count > 0 ? totalTtft / count : 0;
        }

        synchronized long getAvgTpot() {
            return tpotSampleCount > 0 ? totalTpot / tpotSampleCount : 0;
        }

        synchronized long getPercentile90() {
            if (responseTimes.isEmpty()) return 0;
            List<Long> sorted = new ArrayList<>(responseTimes);
            Collections.sort(sorted);
            int index = (int) Math.ceil(0.9 * sorted.size()) - 1;
            return sorted.get(Math.max(0, index));
        }
    }

    public LLMMetricsVisualizer() {
        setLayout(new BorderLayout(0, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        metricsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        add(new JScrollPane(metricsTable), BorderLayout.CENTER);
    }

    @Override
    public String getLabelResource() {
        return getClass().getSimpleName();
    }

    @Override
    public String getStaticLabel() {
        return "LLM Metrics Visualizer";
    }

    @Override
    public void add(SampleResult sample) {
        String responseData = sample.getResponseDataAsString();
        if (responseData == null || responseData.isEmpty()) {
            return;
        }

        try {
            // Trim leading whitespace (mock server sends a space for chunked TTFT flush)
            JSONObject jsonResponse = new JSONObject(responseData.trim());

            long now = System.currentTimeMillis();
            if (testStartTime == 0) {
                testStartTime = now;
            }

            String requestName = sample.getSampleLabel();
            long responseTime = sample.getTime();
            long latency = sample.getLatency(); // time to first byte
            int inputTokens = jsonResponse.optInt("input_tokens", 0);
            int outputTokens = jsonResponse.optInt("output_tokens", 0);

            // Get or create per-sampler stats
            SamplerStats stats = statsMap.computeIfAbsent(requestName, k -> new SamplerStats());
            stats.addSample(responseTime, latency, inputTokens, outputTokens);

            long elapsedMs = now - testStartTime;
            double elapsedSec = elapsedMs / 1000.0;

            // RPS = requests for this sampler / elapsed seconds
            double rps = elapsedSec > 0 ? stats.count / elapsedSec : 0;
            long avg = stats.count > 0 ? stats.totalTime / stats.count : 0;
            int avgInputTokens = stats.count > 0 ? (int) (stats.totalInputTokens / stats.count) : 0;
            int avgOutputTokens = stats.count > 0 ? (int) (stats.totalOutputTokens / stats.count) : 0;
            long p90 = stats.getPercentile90();

            // Tokens/min: only show after 1 minute has elapsed
            boolean pastOneMinute = elapsedMs >= 60_000;
            String inputTokensPerMin;
            String outputTokensPerMin;
            if (pastOneMinute) {
                double elapsedMin = elapsedMs / 60_000.0;
                inputTokensPerMin = String.valueOf((long) (stats.totalInputTokens / elapsedMin));
                outputTokensPerMin = String.valueOf((long) (stats.totalOutputTokens / elapsedMin));
            } else {
                inputTokensPerMin = "NA";
                outputTokensPerMin = "NA";
            }

            long avgTtft = stats.getAvgTtft();
            long avgTpot = stats.getAvgTpot();

            final Object[] rowData = {
                requestName,
                stats.count,
                String.format("%.2f", rps),
                stats.minTime,
                avg,
                stats.maxTime,
                p90,
                avgTtft,
                avgTpot,
                avgInputTokens,
                avgOutputTokens,
                inputTokensPerMin,
                outputTokensPerMin,
                stats.totalInputTokens,
                stats.totalOutputTokens
            };

            SwingUtilities.invokeLater(() -> {
                // Find existing row for this sampler
                int rowIndex = -1;
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    if (requestName.equals(tableModel.getValueAt(i, 0))) {
                        rowIndex = i;
                        break;
                    }
                }

                if (rowIndex >= 0) {
                    // Update existing row
                    for (int col = 0; col < rowData.length; col++) {
                        tableModel.setValueAt(rowData[col], rowIndex, col);
                    }
                } else {
                    // Add new row
                    tableModel.addRow(rowData);
                }
            });
        } catch (Exception e) {
            // Response is not valid JSON - skip silently
        }
    }

    @Override
    public void clearData() {
        statsMap.clear();
        testStartTime = 0;
        SwingUtilities.invokeLater(() -> tableModel.setRowCount(0));
    }

    @Override
    public void clearGui() {
        super.clearGui();
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
    }

    @Override
    public TestElement createTestElement() {
        ResultCollector collector = new ResultCollector();
        modifyTestElement(collector);
        return collector;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.modifyTestElement(element);
    }
}