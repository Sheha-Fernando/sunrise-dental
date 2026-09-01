(function () {
    document.addEventListener("shell:ready", init);

    const STATUS_COLORS = { SCHEDULED: "#2A78D6", COMPLETED: "#1BAF7A", CANCELLED: "#EB6834" };
    const STATUS_LABELS = { SCHEDULED: "Scheduled", COMPLETED: "Completed", CANCELLED: "Cancelled" };
    const ACCENT = "#B89552";
    const RANGE_CAPTIONS = { today: "Today", week: "This week", month: "This month", custom: "Custom range" };

    const chartInstances = {};
    let currentRange = "today";

    // Draws "Total / N" in the centre of the appointment-status doughnut -
    // a small vanilla-canvas plugin so no extra chart library is needed.
    const centerTextPlugin = {
        id: "reportCenterText",
        afterDraw(chart) {
            if (!chart._centerText) return;
            const { ctx, chartArea: { width, height, left, top } } = chart;
            const cx = left + width / 2;
            const cy = top + height / 2;
            ctx.save();
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.font = "700 1.4rem Segoe UI, sans-serif";
            ctx.fillStyle = "#202321";
            ctx.fillText(chart._centerText.value, cx, cy - 10);
            ctx.font = "600 0.72rem Segoe UI, sans-serif";
            ctx.fillStyle = "#6B706D";
            ctx.fillText(chart._centerText.label, cx, cy + 12);
            ctx.restore();
        }
    };

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));
        if (window.Chart) {
            Chart.register(centerTextPlugin);
        }
        wireControls();
        load();
    }

    function wireControls() {
        document.querySelectorAll("#rangeSegmented button").forEach(btn => {
            btn.addEventListener("click", () => {
                document.querySelectorAll("#rangeSegmented button").forEach(b => b.classList.remove("active"));
                btn.classList.add("active");
                currentRange = btn.dataset.range;
                document.getElementById("customRangeFields").style.display = currentRange === "custom" ? "flex" : "none";
                setRangeError("");
                if (currentRange !== "custom") {
                    load();
                }
            });
        });

        document.getElementById("applyCustomRangeBtn").addEventListener("click", () => {
            const from = document.getElementById("reportFromDate").value;
            const to = document.getElementById("reportToDate").value;
            if (!from || !to) {
                setRangeError("Please select both a From and To date.");
                return;
            }
            if (from > to) {
                setRangeError("From date cannot be after To date.");
                return;
            }
            setRangeError("");
            load();
        });

        document.getElementById("reportRetryBtn").addEventListener("click", load);

        // window.print() is sufficient for now; production PDF export can
        // later be handled by a dedicated PDF library or backend service.
        document.getElementById("printReportBtn").addEventListener("click", () => window.print());
    }

    function setRangeError(message) {
        const el = document.getElementById("rangeError");
        el.textContent = message;
        el.classList.toggle("visible", !!message);
    }

    function buildQuery() {
        let qs = "range=" + encodeURIComponent(currentRange);
        if (currentRange === "custom") {
            qs += "&from=" + encodeURIComponent(document.getElementById("reportFromDate").value);
            qs += "&to=" + encodeURIComponent(document.getElementById("reportToDate").value);
        }
        return qs;
    }

    async function load() {
        try {
            const report = await Api.get("/reports?" + buildQuery());
            renderReport(report);
        } catch (err) {
            document.getElementById("reportBody").style.display = "none";
            document.getElementById("reportErrorCard").style.display = "block";
            document.getElementById("reportErrorDesc").textContent = err.message || "Please try again.";
            document.getElementById("rangePeriodLabel").textContent = "";
            document.getElementById("generatedAt").textContent = "";
        }
    }

    function renderReport(report) {
        document.getElementById("reportErrorCard").style.display = "none";
        document.getElementById("reportBody").style.display = "block";

        document.getElementById("rangePeriodLabel").textContent = report.from === report.to
            ? Fmt.date(report.from)
            : `${Fmt.date(report.from)} – ${Fmt.date(report.to)}`;

        renderSummary(report.summary);

        document.getElementById("overviewCaption").textContent = RANGE_CAPTIONS[report.range] || "";
        renderOverviewChart(report.appointmentOverview);

        const hasRevenueTrend = !!report.revenueTrend;
        document.getElementById("revenueTrendCard").style.display = hasRevenueTrend ? "flex" : "none";
        document.getElementById("overviewChartsGrid").classList.toggle("single", !hasRevenueTrend);
        if (hasRevenueTrend) {
            document.getElementById("revenueTrendCaption").textContent = RANGE_CAPTIONS[report.range] || "";
            renderRevenueTrendChart(report.revenueTrend);
        }

        renderStatusChart(report.statusBreakdown);

        const treatmentCard = document.getElementById("treatmentRevenueCard");
        treatmentCard.style.display = report.treatmentRevenue ? "block" : "none";
        if (report.treatmentRevenue) {
            renderTreatmentRevenue(report.treatmentRevenue);
        }

        const dentistCard = document.getElementById("dentistPerformanceCard");
        dentistCard.style.display = report.dentistPerformance ? "block" : "none";
        if (report.dentistPerformance) {
            renderDentistPerformance(report.dentistPerformance);
        }

        const patientCard = document.getElementById("patientActivityCard");
        patientCard.style.display = report.patientActivity ? "block" : "none";
        if (report.patientActivity) {
            Metrics.render(document.getElementById("patientActivityContainer"), [
                { label: "Total Patients", value: report.patientActivity.totalPatients },
                { label: "New Patients", value: report.patientActivity.newPatients, color: "#1BAF7A" },
                { label: "Returning Patients", value: report.patientActivity.returningPatients, color: "#1D5D95" },
            ]);
        }

        const dailyCard = document.getElementById("dailyOperationalCard");
        dailyCard.style.display = report.dailyOperational ? "block" : "none";
        if (report.dailyOperational) {
            const d = report.dailyOperational;
            Metrics.render(document.getElementById("dailyOperationalContainer"), [
                { label: "First Appointment", value: d.firstAppointmentTime ? Fmt.time(d.firstAppointmentTime) : "—" },
                { label: "Last Appointment", value: d.lastAppointmentTime ? Fmt.time(d.lastAppointmentTime) : "—" },
                { label: "Total", value: d.total },
                { label: "Completed", value: d.completed, color: STATUS_COLORS.COMPLETED },
                { label: "Remaining", value: d.remaining },
                { label: "Cancelled", value: d.cancelled, color: STATUS_COLORS.CANCELLED },
            ]);
        }

        document.getElementById("generatedAt").textContent = "Generated " + new Date().toLocaleString("en-GB", {
            day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit"
        });
    }

    function renderSummary(summary) {
        const items = [
            { label: "Total Appointments", value: summary.totalAppointments },
            { label: "Scheduled", value: summary.scheduled, color: STATUS_COLORS.SCHEDULED },
            { label: "Completed", value: summary.completed, color: STATUS_COLORS.COMPLETED },
            { label: "Cancelled", value: summary.cancelled, color: STATUS_COLORS.CANCELLED },
        ];
        if (summary.revenue !== undefined) {
            items.push({ label: "Revenue", value: Fmt.currency(summary.revenue), color: ACCENT });
        }
        Metrics.render(document.getElementById("summaryContainer"), items);
    }

    // --- Chart helpers (same conventions as dashboard.js) -----------------------

    function insertCanvas(canvasId, ariaLabel) {
        document.getElementById(canvasId + "-wrap").innerHTML =
            `<canvas id="${canvasId}" role="img" aria-label="${escapeHtml(ariaLabel)}"></canvas>`;
    }

    function showChartEmpty(canvasId, message) {
        document.getElementById(canvasId + "-wrap").innerHTML = `<div class="chart-empty">${escapeHtml(message)}</div>`;
        const legend = document.getElementById(canvasId + "-legend");
        if (legend) legend.innerHTML = "";
    }

    function destroyChart(canvasId) {
        if (chartInstances[canvasId]) {
            chartInstances[canvasId].destroy();
            delete chartInstances[canvasId];
        }
    }

    function renderOverviewChart(overview) {
        const total = sum(overview.scheduled) + sum(overview.completed) + sum(overview.cancelled);
        if (total === 0) {
            showChartEmpty("overviewChart", "There are no appointments for the selected period.");
            return;
        }
        insertCanvas("overviewChart",
            "Grouped bar chart showing scheduled, completed and cancelled appointments for the selected period.");
        destroyChart("overviewChart");
        chartInstances.overviewChart = new Chart(document.getElementById("overviewChart").getContext("2d"), {
            type: "bar",
            data: {
                labels: overview.labels,
                datasets: [
                    { label: "Scheduled", data: overview.scheduled, backgroundColor: STATUS_COLORS.SCHEDULED, borderRadius: 4, maxBarThickness: 28 },
                    { label: "Completed", data: overview.completed, backgroundColor: STATUS_COLORS.COMPLETED, borderRadius: 4, maxBarThickness: 28 },
                    { label: "Cancelled", data: overview.cancelled, backgroundColor: STATUS_COLORS.CANCELLED, borderRadius: 4, maxBarThickness: 28 },
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false }, tooltip: { mode: "index", intersect: false } },
                scales: { x: { grid: { display: false } }, y: { beginAtZero: true, ticks: { precision: 0 } } }
            }
        });

        const legend = document.getElementById("overviewChart-legend");
        legend.innerHTML = ["SCHEDULED", "COMPLETED", "CANCELLED"].map(status => {
            const key = status === "SCHEDULED" ? "scheduled" : status === "COMPLETED" ? "completed" : "cancelled";
            const total = sum(overview[key]);
            return `<span class="chart-legend-item">
                <span class="chart-legend-dot" style="background:${STATUS_COLORS[status]}"></span>
                ${STATUS_LABELS[status]} <span class="chart-legend-value">${total}</span>
            </span>`;
        }).join("");
    }

    function renderRevenueTrendChart(trend) {
        const values = trend.values.map(Number);
        const grandTotal = sum(values);
        if (grandTotal === 0) {
            showChartEmpty("revenueTrendChart", "No bills were generated for the selected period.");
            return;
        }
        insertCanvas("revenueTrendChart",
            "Line chart showing total billing revenue for each period in the selected date range.");
        destroyChart("revenueTrendChart");
        chartInstances.revenueTrendChart = new Chart(document.getElementById("revenueTrendChart").getContext("2d"), {
            type: "line",
            data: {
                labels: trend.labels,
                datasets: [{
                    label: "Revenue", data: values,
                    borderColor: ACCENT, backgroundColor: ACCENT,
                    pointBackgroundColor: ACCENT, pointRadius: 3, borderWidth: 2,
                    tension: 0.15, fill: false,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => Fmt.currency(ctx.parsed.y) } }
                },
                scales: {
                    x: { grid: { display: false } },
                    y: { beginAtZero: true, grid: { color: "rgba(32,35,33,0.06)" }, ticks: { callback: (v) => Fmt.currency(v) } }
                }
            }
        });

        const legend = document.getElementById("revenueTrendChart-legend");
        legend.innerHTML = `<span class="chart-legend-item">
            <span class="chart-legend-dot" style="background:${ACCENT}"></span>
            Total for period <span class="chart-legend-value">${Fmt.currency(grandTotal)}</span>
        </span>`;
    }

    function renderStatusChart(breakdown) {
        if (breakdown.total === 0) {
            showChartEmpty("statusChart", "No appointment data for the selected period.");
            return;
        }
        insertCanvas("statusChart", "Doughnut chart showing the share of scheduled, completed and cancelled appointments.");
        destroyChart("statusChart");

        const entries = [
            ["SCHEDULED", breakdown.scheduled],
            ["COMPLETED", breakdown.completed],
            ["CANCELLED", breakdown.cancelled],
        ].filter(([, count]) => count > 0);

        const chart = new Chart(document.getElementById("statusChart").getContext("2d"), {
            type: "doughnut",
            data: {
                labels: entries.map(([status]) => STATUS_LABELS[status]),
                datasets: [{ data: entries.map(([, count]) => count), backgroundColor: entries.map(([status]) => STATUS_COLORS[status]), borderWidth: 0 }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: "65%",
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => `${ctx.label}: ${ctx.parsed} (${Math.round(ctx.parsed / breakdown.total * 100)}%)` } }
                }
            }
        });
        chart._centerText = { value: String(breakdown.total), label: "Total" };
        chartInstances.statusChart = chart;

        const legend = document.getElementById("statusChart-legend");
        legend.innerHTML = entries.map(([status, count]) => {
            const pct = Math.round(count / breakdown.total * 100);
            return `<span class="chart-legend-item">
                <span class="chart-legend-dot" style="background:${STATUS_COLORS[status]}"></span>
                ${STATUS_LABELS[status]} <span class="chart-legend-value">${count} (${pct}%)</span>
            </span>`;
        }).join("");
    }

    // --- Tables ------------------------------------------------------------------

    function renderTreatmentRevenue(data) {
        const container = document.getElementById("treatmentRevenueContainer");
        if (!data.rows.length) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No revenue data</div>
                <div class="empty-desc">There is no billed revenue for the selected period.</div>
            </div>`;
            return;
        }
        const rows = data.rows.map(r => `
            <tr>
                <td class="table-primary-text">${escapeHtml(r.treatmentName)}</td>
                <td class="text-right">${r.appointments}</td>
                <td class="text-right">${Fmt.currency(r.revenue)}</td>
            </tr>`).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Treatment</th><th class="text-right">Appointments</th><th class="text-right">Revenue</th></tr></thead>
            <tbody>${rows}</tbody>
            <tfoot><tr>
                <td class="table-primary-text">Total Revenue</td>
                <td></td>
                <td class="text-right table-primary-text">${Fmt.currency(data.totalRevenue)}</td>
            </tr></tfoot>
        </table></div>`;
    }

    function renderDentistPerformance(data) {
        const container = document.getElementById("dentistPerformanceContainer");
        if (!data.rows.length) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No dentist activity</div>
                <div class="empty-desc">There is no dentist activity for the selected period.</div>
            </div>`;
            return;
        }
        const rows = data.rows.map(r => `
            <tr>
                <td class="table-primary-text">${escapeHtml(r.dentistName)}</td>
                <td class="text-right">${r.appointments}</td>
                <td class="text-right">${r.completed}</td>
                <td class="text-right">${r.cancelled}</td>
                <td class="text-right">${Fmt.currency(r.revenue)}</td>
            </tr>`).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr>
                <th>Dentist</th><th class="text-right">Appointments</th><th class="text-right">Completed</th>
                <th class="text-right">Cancelled</th><th class="text-right">Revenue</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    function sum(values) {
        return values.reduce((a, b) => a + b, 0);
    }
})();
