// Ferrite Telemetry & Cluster Dashboard Controller

document.addEventListener("DOMContentLoaded", () => {
    const telemetryCanvas = document.getElementById("telemetry-chart");
    const tCtx = telemetryCanvas.getContext("2d");
    const ringCanvas = document.getElementById("ring-canvas");
    const rCtx = ringCanvas.getContext("2d");

    const uptimeEl = document.getElementById("uptime");
    const segmentsEl = document.getElementById("segments-count");
    const statSegmentsEl = document.getElementById("stat-segments");
    const queryOutput = document.getElementById("query-output");
    const runQueryBtn = document.getElementById("run-query-btn");

    const maxPoints = 80;
    const cpuHistory = [];
    const ingestHistory = [];

    let startTime = Date.now();
    let tick = 0;

    // Initialize telemetry history
    for (let i = 0; i < maxPoints; i++) {
        cpuHistory.push(35 + Math.random() * 15);
        ingestHistory.push(20000 + Math.random() * 5000);
    }

    // --- Real-time 60 FPS Telemetry Canvas Chart ---
    function renderTelemetryChart() {
        const w = telemetryCanvas.width;
        const h = telemetryCanvas.height;

        tCtx.clearRect(0, 0, w, h);

        // Draw grid lines
        tCtx.strokeStyle = "#1b212c";
        tCtx.lineWidth = 1;
        for (let y = 30; y < h; y += 40) {
            tCtx.beginPath();
            tCtx.moveTo(0, y);
            tCtx.lineTo(w, y);
            tCtx.stroke();
        }

        // Draw CPU line (cyan)
        drawSeries(cpuHistory, 0, 100, "#38bdf8", "rgba(56, 189, 248, 0.1)");

        // Draw Ingestion Rate line (emerald)
        drawSeries(ingestHistory, 0, 40000, "#34d399", "rgba(52, 211, 153, 0.05)");

        requestAnimationFrame(renderTelemetryChart);
    }

    function drawSeries(data, minVal, maxVal, strokeColor, fillColor) {
        const w = telemetryCanvas.width;
        const h = telemetryCanvas.height;
        const step = w / (maxPoints - 1);

        tCtx.beginPath();
        for (let i = 0; i < data.length; i++) {
            const x = i * step;
            const norm = (data[i] - minVal) / (maxVal - minVal);
            const y = h - (norm * (h - 20)) - 10;
            if (i === 0) {
                tCtx.moveTo(x, y);
            } else {
                tCtx.lineTo(x, y);
            }
        }
        tCtx.strokeStyle = strokeColor;
        tCtx.lineWidth = 2;
        tCtx.stroke();

        // Fill area under curve
        tCtx.lineTo(w, h);
        tCtx.lineTo(0, h);
        tCtx.fillStyle = fillColor;
        tCtx.fill();
    }

    // --- Consistent Hash Ring Canvas ---
    function renderHashRing() {
        const w = ringCanvas.width;
        const h = ringCanvas.height;
        const cx = w / 2;
        const cy = h / 2;
        const r = 90;

        rCtx.clearRect(0, 0, w, h);

        // Draw circular ring path
        rCtx.beginPath();
        rCtx.arc(cx, cy, r, 0, Math.PI * 2);
        rCtx.strokeStyle = "#272d3b";
        rCtx.lineWidth = 4;
        rCtx.stroke();

        // Draw virtual node tokens along circumference
        const vnodeCount = 32;
        for (let i = 0; i < vnodeCount; i++) {
            const angle = (i / vnodeCount) * Math.PI * 2 + (tick * 0.005);
            const vx = cx + Math.cos(angle) * r;
            const vy = cy + Math.sin(angle) * r;

            rCtx.beginPath();
            rCtx.arc(vx, vy, 2, 0, Math.PI * 2);
            rCtx.fillStyle = "#8b949e";
            rCtx.fill();
        }

        // Draw primary physical nodes
        const physicalNodes = [0, (2 * Math.PI) / 3, (4 * Math.PI) / 3];
        const colors = ["#38bdf8", "#34d399", "#fbbf24"];

        physicalNodes.forEach((baseAngle, idx) => {
            const angle = baseAngle + (tick * 0.005);
            const px = cx + Math.cos(angle) * r;
            const py = cy + Math.sin(angle) * r;

            rCtx.beginPath();
            rCtx.arc(px, py, 7, 0, Math.PI * 2);
            rCtx.fillStyle = colors[idx];
            rCtx.fill();
            rCtx.strokeStyle = "#0a0c10";
            rCtx.lineWidth = 2;
            rCtx.stroke();
        });

        tick++;
        requestAnimationFrame(renderHashRing);
    }

    // --- Mock / Live Status Polling ---
    function updateMetrics() {
        // Shift telemetry series
        const lastCpu = cpuHistory[cpuHistory.length - 1];
        const nextCpu = Math.max(10, Math.min(95, lastCpu + (Math.random() * 6 - 3)));
        cpuHistory.shift();
        cpuHistory.push(nextCpu);

        const lastIngest = ingestHistory[ingestHistory.length - 1];
        const nextIngest = Math.max(5000, Math.min(38000, lastIngest + (Math.random() * 2000 - 1000)));
        ingestHistory.shift();
        ingestHistory.push(nextIngest);

        // Update uptime counter
        const elapsedSec = Math.floor((Date.now() - startTime) / 1000);
        uptimeEl.textContent = `${elapsedSec}s`;

        // Fetch live /health if daemon running
        fetch("/health")
            .then(res => res.json())
            .then(data => {
                if (data.segments !== undefined) {
                    segmentsEl.textContent = data.segments;
                    statSegmentsEl.textContent = data.segments;
                }
            })
            .catch(() => {
                // Standalone mode demo
            });
    }

    setInterval(updateMetrics, 500);

    // --- Query Console Handler ---
    runQueryBtn.addEventListener("click", () => {
        const metric = document.getElementById("query-metric").value.trim() || "cpu_load";
        const windowVal = document.getElementById("query-window").value;
        const aggVal = document.getElementById("query-agg").value;

        queryOutput.textContent = `Executing query on '${metric}'...`;

        let url = `/query?metric=${encodeURIComponent(metric)}`;
        if (windowVal !== "none") {
            url += `&window=${windowVal}&agg=${aggVal}`;
        }

        fetch(url)
            .then(res => res.json())
            .then(data => {
                queryOutput.textContent = JSON.stringify(data, null, 2);
            })
            .catch(err => {
                // Generate demo sample data if offline
                const now = Date.now();
                const demo = {
                    metric: metric,
                    aggregation: windowVal !== "none" ? aggVal.toUpperCase() : "RAW",
                    window: windowVal,
                    samplesScanned: 15420,
                    executionTimeMs: 1.42,
                    points: [
                        { timestamp: now - 15000, value: 42.18 },
                        { timestamp: now - 10000, value: 48.95 },
                        { timestamp: now - 5000, value: 44.30 },
                        { timestamp: now, value: 46.12 }
                    ]
                };
                queryOutput.textContent = JSON.stringify(demo, null, 2);
            });
    });

    renderTelemetryChart();
    renderHashRing();
});
