# LLM Metrics Visualizer — JMeter Plugin

A custom Apache JMeter listener plugin that captures and displays real-time LLM (Large Language Model) performance metrics in a tabular format. Designed for load testing LLM APIs and monitoring key inference metrics such as TTFT, TPOT, tokens per minute, and throughput.

## Metrics Captured

| Metric | Description |
|---|---|
| **Request Name** | HTTP sampler label (one row per unique sampler) |
| **Request Count** | Total number of requests sent for this sampler |
| **RPS (req/sec)** | Requests per second (total requests / elapsed time) |
| **Min (ms)** | Minimum response time |
| **Avg (ms)** | Average response time |
| **Max (ms)** | Maximum response time |
| **90th Percentile (ms)** | 90th percentile response time |
| **TTFT (ms)** | Time To First Token — average latency before the first byte of response |
| **TPOT (ms)** | Time Per Output Token — average `(response_time - latency) / output_tokens` |
| **Input Tokens/Req** | Average input tokens per request |
| **Output Tokens/Req** | Average output tokens per request |
| **Input Tokens/Min** | Input token throughput per minute (shows "NA" until 1 minute has elapsed) |
| **Output Tokens/Min** | Output token throughput per minute (shows "NA" until 1 minute has elapsed) |
| **Total Input Tokens** | Cumulative input tokens for this sampler |
| **Total Output Tokens** | Cumulative output tokens for this sampler |

## Requirements

- **Apache JMeter** 5.6.3 or later
- **Java** 8 (JDK 1.8) or later

## Installation

1. Download or build the `LLM_Metrics_Visualizer-0.1.jar` file.
2. Copy the JAR into the JMeter `lib/ext/` directory:
   ```
   <JMETER_HOME>/lib/ext/LLM_Metrics_Visualizer-0.1.jar
   ```
3. Restart JMeter.

## Usage

### Adding the Listener

1. Open JMeter and load or create a test plan.
2. Right-click on a **Thread Group** (or the **Test Plan** node).
3. Navigate to **Add → Listener → LLM Metrics Visualizer**.
4. The listener will appear with an empty metrics table.

### Expected API Response Format

The plugin parses the HTTP response body as JSON and reads the following fields:

```json
{
  "input_tokens": 10,
  "output_tokens": 20,
  "response": "Hello, world!"
}
```

| Field | Type | Description |
|---|---|---|
| `input_tokens` | int | Number of input/prompt tokens consumed |
| `output_tokens` | int | Number of output/completion tokens generated |

If the response is not valid JSON or the token fields are missing, the sample is skipped gracefully.

### TTFT and TPOT Measurement

- **TTFT** is measured using JMeter's `SampleResult.getLatency()` — the time from sending the request to receiving the first byte of the response. For accurate TTFT, the target API should flush headers or the first byte before completing generation.
- **TPOT** is calculated as `(response_time - latency) / output_tokens`. This requires `output_tokens > 0` and meaningful latency separation (e.g., streaming or chunked responses).

### Tokens/Min Display

- Input Tokens/Min and Output Tokens/Min columns display **"NA"** until at least **1 minute** of test time has elapsed, to avoid misleading early extrapolations.

### Row Grouping

- Rows are grouped by **HTTP sampler name**. Each unique sampler label gets a single row that is updated in-place as new results arrive.

## Building from Source

### Prerequisites

- **JDK 8** or later (the project compiles to Java 8 bytecode)
- **Apache Maven** 3.6+

### Build Commands

```bash
# Clone the repository
git clone https://github.com/<your-username>/LLM_Metrics_Visualizer.git
cd LLM_Metrics_Visualizer

# Build the plugin JAR
mvn clean package

# The JAR will be at:
#   target/LLM_Metrics_Visualizer-0.1.jar
```

### Deploy to JMeter

```bash
cp target/LLM_Metrics_Visualizer-0.1.jar <JMETER_HOME>/lib/ext/
```

Restart JMeter after copying the JAR.

## Project Structure

```
├── pom.xml                          # Maven build configuration
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/jmeter/
│       │       └── LLMMetricsVisualizer.java   # Plugin source
│       └── resources/
│           └── META-INF/services/
│               └── org.apache.jmeter.visualizers.Visualizer
└── README.md
```

## Configuration Details

| Setting | Value |
|---|---|
| Artifact ID | `LLM_Metrics_Visualizer` |
| Version | `0.1` |
| Java Target | 8 (bytecode major version 52) |
| JMeter Compatibility | 5.6.3+ |
| Bundled Dependencies | `org.json:json:20210307` (shaded into the JAR) |

## Example JMeter Test Plan Setup

1. **Thread Group**: Configure the number of threads, ramp-up, and loop count.
2. **HTTP Request Sampler**:
   - **Method**: `POST`
   - **URL**: Your LLM API endpoint (e.g., `http://localhost:8080/generate`)
   - **Body Data**:
     ```json
     {
       "prompt": "Generate a greeting message",
       "max_tokens": 50,
       "temperature": 0.7,
       "stream": false
     }
     ```
   - **Content-Type Header**: `application/json` (add via HTTP Header Manager)
3. **LLM Metrics Visualizer**: Add as a listener under the Thread Group.
4. Run the test and observe the metrics table updating in real time.

## Troubleshooting

| Issue | Solution |
|---|---|
| Plugin not visible in JMeter | Ensure the JAR is in `<JMETER_HOME>/lib/ext/` and restart JMeter |
| All metrics show zero | Verify the API response contains valid JSON with `input_tokens` and `output_tokens` fields |
| TTFT shows zero | The target API must flush the first byte before completing the full response |
| TPOT shows zero | Requires `output_tokens > 0` and latency < response time (use chunked/streaming responses) |
| Tokens/Min shows "NA" | This is expected — values appear after 1 minute of elapsed test time |
| `ClassNotFoundException` | Ensure you are using the shaded JAR (`LLM_Metrics_Visualizer-0.1.jar`), not the original artifact |

## License

This project is open source. See [LICENSE](LICENSE) for details.
