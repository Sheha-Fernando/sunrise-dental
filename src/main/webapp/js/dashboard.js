(function () {
    // Standard clinic time slots (matches appointment.js's availability grid) -
    // used only to compute a real "available slots today" figure, never to
    // fabricate appointment data.
    const CLINIC_TIMES = ["08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
        "13:30", "14:00", "14:30", "15:00", "15:30", "16:00"];

    const STATUS_COLORS = { SCHEDULED: Theme.chart(0), COMPLETED: Theme.chart(1), CANCELLED: Theme.chart(2) };
    const STATUS_LABELS = { SCHEDULED: "Scheduled", COMPLETED: "Completed", CANCELLED: "Cancelled" };
    const DOUGHNUT_PALETTE = [0, 1, 2, 3, 4, 5].map(i => Theme.chart(i));

    const chartInstances = {};

    document.addEventListener("shell:ready", (e) => onReady(e.detail));

    function onReady(session) {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("greeting").textContent = `Welcome back, ${session.fullName || session.username}`;
        document.getElementById("dateLine").textContent = Shell.roleLabel(session.role);

        switch (session.role) {
            case "ADMIN": loadAdminDashboard(); break;
            case "RECEPTIONIST": loadReceptionistDashboard(); break;
            case "DENTIST": loadDentistDashboard(); break;
            case "BILLING": loadBillingDashboard(); break;
            case "CLINICAL_ASSISTANT": loadClinicalAssistantDashboard(session); break;
        }
    }

    function todayIso() {
        return new Date().toISOString().split("T")[0];
    }

    function monthPrefix() {
        return todayIso().slice(0, 7); // YYYY-MM
    }

    function mondayOfWeek(date) {
        const d = new Date(date);
        const day = d.getDay(); // 0 = Sun .. 6 = Sat
        const diff = day === 0 ? -6 : 1 - day;
        d.setDate(d.getDate() + diff);
        d.setHours(0, 0, 0, 0);
        return d;
    }

    function weekDatesMonToSat() {
        const monday = mondayOfWeek(new Date());
        const dates = [];
        for (let i = 0; i < 6; i++) {
            const d = new Date(monday);
            d.setDate(monday.getDate() + i);
            dates.push(d.toISOString().split("T")[0]);
        }
        return dates;
    }

    function renderScheduleTable(appointments, emptyDesc) {
        const container = document.getElementById("scheduleContainer");
        if (appointments.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No appointments</div>
                <div class="empty-desc">${emptyDesc}</div>
            </div>`;
            return;
        }
        const rows = appointments.map(a => `
            <div class="schedule-row" onclick="window.location.href='search.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                <div class="schedule-time">${Fmt.time(a.appointmentTime)}</div>
                <div class="schedule-main">
                    <div class="schedule-patient">${escapeHtml(a.patientName)}</div>
                    <div class="schedule-meta">${escapeHtml(a.treatmentType)} &middot; ${escapeHtml(a.dentistName)}</div>
                </div>
                <span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span>
            </div>`).join("");
        container.innerHTML = `<div class="schedule-list">${rows}</div>`;
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function quickActionsHtml(actions) {
        return actions.map(a => `
            <a class="stat-card" href="${a.href}" style="text-decoration:none;">
                <div class="stat-label">${a.icon}</div>
                <div class="stat-value" style="font-size:1rem;">${a.label}</div>
            </a>`).join("");
    }

    function nextUpcoming(all) {
        const now = new Date();
        return all
            .filter(a => a.status === "SCHEDULED" && new Date(a.appointmentDate + "T" + a.appointmentTime) >= now)
            .sort((a, b) => (a.appointmentDate + a.appointmentTime).localeCompare(b.appointmentDate + b.appointmentTime));
    }

    // --- Chart helpers ---------------------------------------------------------

    function chartCardHtml(canvasId, title, caption) {
        return `<div class="chart-card">
            <div class="chart-card-header">
                <h2 class="section-title" style="border:none;margin:0;padding:0;">${title}</h2>
                ${caption ? `<span class="chart-card-caption">${escapeHtml(caption)}</span>` : ""}
            </div>
            <div class="chart-canvas-wrap" id="${canvasId}-wrap"><div class="chart-skeleton"></div></div>
            <div class="chart-legend" id="${canvasId}-legend"></div>
        </div>`;
    }

    function insertCanvas(canvasId, ariaLabel) {
        document.getElementById(canvasId + "-wrap").innerHTML =
            `<canvas id="${canvasId}" role="img" aria-label="${escapeHtml(ariaLabel)}"></canvas>`;
    }

    function showChartEmpty(canvasId, message) {
        document.getElementById(canvasId + "-wrap").innerHTML =
            `<div class="chart-empty">${escapeHtml(message)}</div>`;
        const legend = document.getElementById(canvasId + "-legend");
        if (legend) legend.innerHTML = "";
    }

    function showChartError(canvasId, retryFn) {
        const wrap = document.getElementById(canvasId + "-wrap");
        wrap.innerHTML = `<div class="chart-error">
            <div>We couldn't load this chart.</div>
            <button type="button" class="btn btn-secondary">Retry</button>
        </div>`;
        wrap.querySelector("button").addEventListener("click", retryFn);
    }

    function destroyChart(canvasId) {
        if (chartInstances[canvasId]) {
            chartInstances[canvasId].destroy();
            delete chartInstances[canvasId];
        }
    }

    function renderWeeklyAppointmentsChart(canvasId, appointments) {
        const dates = weekDatesMonToSat();
        const totalThisWeek = appointments.filter(a => dates.includes(a.appointmentDate)).length;
        if (totalThisWeek === 0) {
            showChartEmpty(canvasId, "No appointments recorded for this period.");
            return;
        }
        insertCanvas(canvasId,
            "Grouped bar chart showing scheduled, completed and cancelled appointments for each day this week, Monday through Saturday.");

        const dayLabels = dates.map(d => new Date(d + "T00:00:00").toLocaleDateString("en-US", { weekday: "short" }));
        const counts = { SCHEDULED: [], COMPLETED: [], CANCELLED: [] };
        for (const date of dates) {
            const dayAppts = appointments.filter(a => a.appointmentDate === date);
            counts.SCHEDULED.push(dayAppts.filter(a => a.status === "SCHEDULED").length);
            counts.COMPLETED.push(dayAppts.filter(a => a.status === "COMPLETED").length);
            counts.CANCELLED.push(dayAppts.filter(a => a.status === "CANCELLED").length);
        }

        destroyChart(canvasId);
        chartInstances[canvasId] = new Chart(document.getElementById(canvasId).getContext("2d"), {
            type: "bar",
            data: {
                labels: dayLabels,
                datasets: [
                    { label: "Scheduled", data: counts.SCHEDULED, backgroundColor: STATUS_COLORS.SCHEDULED, borderRadius: 4, maxBarThickness: 28 },
                    { label: "Completed", data: counts.COMPLETED, backgroundColor: STATUS_COLORS.COMPLETED, borderRadius: 4, maxBarThickness: 28 },
                    { label: "Cancelled", data: counts.CANCELLED, backgroundColor: STATUS_COLORS.CANCELLED, borderRadius: 4, maxBarThickness: 28 },
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: { legend: { display: false }, tooltip: { mode: "index", intersect: false } },
                scales: {
                    x: { grid: { display: false } },
                    y: { beginAtZero: true, ticks: { precision: 0 } }
                }
            }
        });

        const legend = document.getElementById(canvasId + "-legend");
        legend.innerHTML = ["SCHEDULED", "COMPLETED", "CANCELLED"].map(status => {
            const total = counts[status].reduce((a, b) => a + b, 0);
            return `<span class="chart-legend-item">
                <span class="chart-legend-dot" style="background:${STATUS_COLORS[status]}"></span>
                ${STATUS_LABELS[status]} <span class="chart-legend-value">${total}</span>
            </span>`;
        }).join("");
    }

    function renderTreatmentsDoughnut(canvasId, appointments, emptyMessage) {
        if (appointments.length === 0) {
            showChartEmpty(canvasId, emptyMessage);
            return;
        }
        insertCanvas(canvasId, "Doughnut chart showing the share of each treatment type among these appointments.");

        const counts = new Map();
        for (const a of appointments) {
            counts.set(a.treatmentType, (counts.get(a.treatmentType) || 0) + 1);
        }
        const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
        const total = appointments.length;
        const colors = entries.map((_, i) => DOUGHNUT_PALETTE[i % DOUGHNUT_PALETTE.length]);

        destroyChart(canvasId);
        chartInstances[canvasId] = new Chart(document.getElementById(canvasId).getContext("2d"), {
            type: "doughnut",
            data: {
                labels: entries.map(e => e[0]),
                datasets: [{ data: entries.map(e => e[1]), backgroundColor: colors, borderWidth: 0 }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: "65%",
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => `${ctx.label}: ${ctx.parsed} (${Math.round(ctx.parsed / total * 100)}%)` } }
                }
            }
        });

        const legend = document.getElementById(canvasId + "-legend");
        legend.innerHTML = entries.map(([name, count], i) => {
            const pct = Math.round(count / total * 100);
            return `<span class="chart-legend-item">
                <span class="chart-legend-dot" style="background:${colors[i]}"></span>
                ${escapeHtml(name)} <span class="chart-legend-value">${pct}%</span>
            </span>`;
        }).join("");
    }

    function renderRevenueTrendChart(canvasId, bills) {
        const dates = weekDatesMonToSat();
        const totals = dates.map(date => bills
            .filter(b => b.billDate.startsWith(date))
            .reduce((sum, b) => sum + Number(b.totalAmount), 0));
        const grandTotal = totals.reduce((a, b) => a + b, 0);
        if (grandTotal === 0) {
            showChartEmpty(canvasId, "No bills generated yet this week.");
            return;
        }
        insertCanvas(canvasId, "Bar chart showing total billing revenue for each day this week, Monday through Saturday.");

        const dayLabels = dates.map(d => new Date(d + "T00:00:00").toLocaleDateString("en-US", { weekday: "short" }));
        destroyChart(canvasId);
        chartInstances[canvasId] = new Chart(document.getElementById(canvasId).getContext("2d"), {
            type: "bar",
            data: { labels: dayLabels, datasets: [{ label: "Revenue", data: totals, backgroundColor: Theme.chart(3), borderRadius: 4, maxBarThickness: 36 }] },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => Fmt.currency(ctx.parsed.y) } }
                },
                scales: {
                    x: { grid: { display: false } },
                    y: { beginAtZero: true, ticks: { callback: (v) => Fmt.currency(v) } }
                }
            }
        });

        const legend = document.getElementById(canvasId + "-legend");
        legend.innerHTML = `<span class="chart-legend-item">
            <span class="chart-legend-dot" style="background:${Theme.chart(3)}"></span>
            Total this week <span class="chart-legend-value">${Fmt.currency(grandTotal)}</span>
        </span>`;
    }

    function renderStatusSummary(scheduled, completed, cancelled) {
        document.getElementById("summaryGridRow").style.display = "grid";
        Metrics.render(document.getElementById("statusSummaryContainer"), [
            { label: "Scheduled Today", value: scheduled, color: STATUS_COLORS.SCHEDULED },
            { label: "Completed Today", value: completed, color: STATUS_COLORS.COMPLETED },
            { label: "Cancelled Today", value: cancelled, color: STATUS_COLORS.CANCELLED },
        ]);
    }

    function renderClinicOverview(totalPatients, activeDentists, revenue) {
        document.getElementById("summaryGridRow").style.display = "grid";
        Metrics.render(document.getElementById("clinicOverviewContainer"), [
            { label: "Total Patients", value: totalPatients, color: Theme.chart(0) },
            { label: "Active Dentists", value: activeDentists, color: Theme.chart(1) },
            { label: "Today's Revenue", value: Fmt.currency(revenue), color: Theme.chart(3) },
        ]);
    }

    function renderActivityFeed(todayAppointments, todayBills) {
        document.getElementById("activityCard").style.display = "block";
        const events = [];
        for (const a of todayAppointments) {
            events.push({
                time: a.appointmentTime,
                text: `${escapeHtml(a.patientName)} &mdash; ${STATUS_LABELS[a.status] || a.status} appointment with ${escapeHtml(a.dentistName)}`,
                color: STATUS_COLORS[a.status] || "var(--color-text-faint)"
            });
        }
        for (const b of todayBills) {
            const timePart = (b.billDate.split("T")[1] || "00:00").slice(0, 5);
            events.push({
                time: timePart,
                text: `Bill generated for ${escapeHtml(b.patientName)} (${escapeHtml(b.appointmentNumber)})`,
                color: Theme.chart(3)
            });
        }
        events.sort((x, y) => y.time.localeCompare(x.time));

        const container = document.getElementById("activityContainer");
        if (events.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="empty-desc">No activity recorded yet today.</div></div>`;
            return;
        }
        container.innerHTML = events.slice(0, 10).map(e => `
            <div class="activity-row">
                <span class="activity-dot" style="background:${e.color}"></span>
                <div class="activity-text">${e.text}</div>
                <div class="activity-time" style="margin-left:auto;">${Fmt.time(e.time)}</div>
            </div>`).join("");
    }

    // --- ADMIN ---------------------------------------------------------------

    async function loadAdminDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "Register Patient", href: "patients.html?action=new", icon: "▥" },
            { label: "New Appointment", href: "appointments.html?action=new", icon: "▤" },
            { label: "Manage Staff", href: "staff.html", icon: "▦" },
            { label: "View Reports", href: "reports.html", icon: "▣" },
        ]);

        const chartsSection = document.getElementById("chartsSection");
        chartsSection.style.display = "block";
        chartsSection.innerHTML = `<div class="chart-grid">
            ${chartCardHtml("weeklyChart", "Appointments This Week", "Mon–Sat")}
            ${chartCardHtml("treatmentsChart", "Treatments This Month")}
        </div>`;

        try {
            const [today, all, bills, patients, dentists] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
                Api.get("/bills"),
                Api.get("/patients"),
                Api.get("/dentists"),
            ]);

            const scheduled = today.filter(a => a.status === "SCHEDULED").length;
            const completed = today.filter(a => a.status === "COMPLETED").length;
            const cancelled = today.filter(a => a.status === "CANCELLED").length;
            const todaysBills = bills.filter(b => b.billDate.startsWith(todayIso()));
            const revenue = todaysBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);

            document.getElementById("statCardsCard").style.display = "none";

            renderClinicOverview(patients.length, dentists.length, revenue);

            renderWeeklyAppointmentsChart("weeklyChart", all);
            const thisMonthAppointments = all.filter(a => a.appointmentDate.startsWith(monthPrefix()));
            renderTreatmentsDoughnut("treatmentsChart", thisMonthAppointments, "No treatment data available for this month.");

            renderStatusSummary(scheduled, completed, cancelled);
            renderActivityFeed(today, todaysBills);

            renderScheduleTable(today, "There are no appointments scheduled for today.");

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Recent Appointments";
            const recentList = [...all]
                .sort((a, b) => (b.appointmentDate + b.appointmentTime).localeCompare(a.appointmentDate + a.appointmentTime))
                .slice(0, 5);
            document.getElementById("secondaryContainer").innerHTML = recentList.length === 0
                ? `<div class="empty-state"><div class="empty-desc">No recent appointments.</div></div>`
                : recentList.map(a => `<div class="schedule-row" onclick="window.location.href='search.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                    <div class="schedule-time">${Fmt.date(a.appointmentDate)}</div>
                    <div class="schedule-main">
                        <div class="schedule-patient">${escapeHtml(a.patientName)}</div>
                        <div class="schedule-meta">${escapeHtml(a.dentistName)} &middot; ${escapeHtml(a.treatmentType)}</div>
                    </div>
                    <span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span>
                  </div>`).join("");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load the dashboard. Please try again.</div></div>`;
            showChartError("weeklyChart", loadAdminDashboard);
            showChartError("treatmentsChart", loadAdminDashboard);
        }
    }

    // --- RECEPTIONIST ---------------------------------------------------------

    async function loadReceptionistDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "New Appointment", href: "appointments.html?action=new", icon: "▤" },
            { label: "Register Patient", href: "patients.html?action=new", icon: "▥" },
            { label: "Find Patient", href: "patients.html", icon: "▥" },
            { label: "Find Appointment", href: "search.html", icon: "▤" },
        ]);

        const chartsSection = document.getElementById("chartsSection");
        chartsSection.style.display = "block";
        chartsSection.innerHTML = `<div class="chart-grid single">
            ${chartCardHtml("weeklyChart", "Appointments This Week", "Mon–Sat")}
        </div>`;

        try {
            const [today, all, dentists, patients] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
                Api.get("/dentists"),
                Api.get("/patients"),
            ]);
            const upcoming = nextUpcoming(all);

            const distinctPatientsToday = new Set(today.map(a => a.patientName + "|" + a.contactNumber)).size;

            const bookedTodayByDentist = new Map();
            for (const a of today) {
                if (a.status === "CANCELLED") continue;
                bookedTodayByDentist.set(a.dentistName, (bookedTodayByDentist.get(a.dentistName) || 0) + 1);
            }
            const totalSlots = dentists.length * CLINIC_TIMES.length;
            const bookedSlots = [...bookedTodayByDentist.values()].reduce((sum, n) => sum + n, 0);
            const availableSlots = Math.max(totalSlots - bookedSlots, 0);

            Metrics.render(document.getElementById("statCards"), [
                { label: "Today's Appointments", value: today.length },
                { label: "Upcoming", value: upcoming.length },
                { label: "Today's Patients", value: distinctPatientsToday },
                { label: "Available Slots Today", value: availableSlots },
            ]);

            renderWeeklyAppointmentsChart("weeklyChart", all);

            renderScheduleTable(today, "There are no appointments scheduled for today.");

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Recently Registered Patients";
            const recentPatients = [...patients]
                .filter(p => p.registeredDate)
                .sort((a, b) => b.registeredDate.localeCompare(a.registeredDate))
                .slice(0, 5);
            document.getElementById("secondaryContainer").innerHTML = recentPatients.length === 0
                ? `<div class="empty-state"><div class="empty-desc">No patients registered yet.</div></div>`
                : recentPatients.map(p => `<div class="schedule-row" onclick="window.location.href='patients.html?id=${p.patientId}'">
                    <div class="schedule-main">
                        <div class="schedule-patient">${escapeHtml(p.patientName)}</div>
                        <div class="schedule-meta">${escapeHtml(p.contactNumber)} &middot; Registered ${Fmt.date(p.registeredDate)}</div>
                    </div>
                  </div>`).join("");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load the dashboard. Please try again.</div></div>`;
            showChartError("weeklyChart", loadReceptionistDashboard);
        }
    }

    // --- DENTIST -----------------------------------------------------------
    // Fully separate, self-contained redesign: removes every generic shared
    // dashboard block below and renders only into #dentistDashboard, so this
    // cannot affect the Admin/Receptionist/Billing/Clinical Assistant layouts
    // (which keep using chartCardHtml/renderScheduleTable/Metrics.render as-is).

    const DD_TREATMENT_COLORS = {
        "Extraction": Theme.chart(0),
        "Cleaning": Theme.chart(1),
        "Consultation": Theme.chart(2),
        "Filling": Theme.chart(3),
        "Root Canal": Theme.chart(4),
        "Dental X-Ray": Theme.chart(5),
    };

    function ddColorFor(treatmentName, index) {
        return DD_TREATMENT_COLORS[treatmentName] || Theme.chart(index);
    }

    function ddInitials(name) {
        const parts = (name || "").trim().split(/\s+/).filter(Boolean);
        if (parts.length === 0) return "?";
        return (parts[0].charAt(0) + (parts.length > 1 ? parts[parts.length - 1].charAt(0) : "")).toUpperCase();
    }

    const DENTIST_DASHBOARD_SKELETON = `
        <div class="dd-stats-row" id="ddStatsRow"></div>

        <div class="dd-card dd-next-patient" id="ddNextPatientCard"></div>

        <div class="dd-analytics-row">
            <div class="dd-card">
                <div class="dd-card-header">
                    <h2 class="dd-card-title">My treatment overview</h2>
                    <span class="dd-card-caption">This month</span>
                </div>
                <div class="dd-donut-wrap" id="ddTreatmentChart-wrap"><div class="chart-skeleton"></div></div>
                <div class="dd-legend" id="ddTreatmentChart-legend"></div>
            </div>
            <div class="dd-card">
                <div class="dd-card-header">
                    <h2 class="dd-card-title">Completion rate this week</h2>
                </div>
                <div class="dd-line-wrap" id="ddCompletionChart-wrap"><div class="chart-skeleton"></div></div>
                <p class="dd-summary" id="ddCompletionSummary"></p>
            </div>
        </div>

        <div class="dd-card">
            <h2 class="dd-card-title" style="margin-bottom:0.9rem;">My schedule</h2>
            <div id="ddScheduleContainer"></div>
        </div>`;

    function ddInsertDonutCanvas(ariaLabel) {
        document.getElementById("ddTreatmentChart-wrap").innerHTML =
            `<canvas id="ddTreatmentChart" role="img" aria-label="${escapeHtml(ariaLabel)}"></canvas>` +
            `<div class="dd-donut-center" id="ddDonutCenter"></div>`;
    }

    function ddInsertLineCanvas(ariaLabel) {
        document.getElementById("ddCompletionChart-wrap").innerHTML =
            `<canvas id="ddCompletionChart" role="img" aria-label="${escapeHtml(ariaLabel)}"></canvas>`;
    }

    function renderDentistStats(today, upcoming, completedTodayCount) {
        document.getElementById("ddStatsRow").innerHTML = [
            { value: today.length, label: "today's appointments" },
            { value: completedTodayCount, label: "patients seen today" },
            { value: upcoming.length, label: "upcoming appointments" },
        ].map(s => `<div class="dd-stat-card">
            <div class="dd-stat-value">${s.value}</div>
            <div class="dd-stat-label">${s.label}</div>
        </div>`).join("");
    }

    function renderDentistNextPatient(nextAppointment) {
        const card = document.getElementById("ddNextPatientCard");
        if (!nextAppointment) {
            card.innerHTML = `<div class="dd-empty" style="width:100%;">No upcoming patients</div>`;
            return;
        }
        card.innerHTML = `
            <div class="dd-next-patient-left">
                <div class="dd-avatar">${escapeHtml(ddInitials(nextAppointment.patientName))}</div>
                <div>
                    <div class="dd-next-patient-label">Next patient</div>
                    <div class="dd-next-patient-name">${escapeHtml(nextAppointment.patientName)}</div>
                    <div class="dd-next-patient-treatment">${escapeHtml(nextAppointment.treatmentType)}</div>
                </div>
            </div>
            <div class="dd-next-patient-right">
                <div class="dd-next-patient-time">${Fmt.time(nextAppointment.appointmentTime)}</div>
                <div class="dd-next-patient-date">${Fmt.date(nextAppointment.appointmentDate)}</div>
            </div>`;
    }

    function renderDentistTreatmentDonut(monthCompletedAppointments) {
        if (monthCompletedAppointments.length === 0) {
            showChartEmpty("ddTreatmentChart", "No treatment data available this month.");
            document.getElementById("ddDonutCenter")?.remove();
            return;
        }
        ddInsertDonutCanvas("Donut chart showing this month's completed treatment distribution.");

        const counts = new Map();
        for (const a of monthCompletedAppointments) {
            counts.set(a.treatmentType, (counts.get(a.treatmentType) || 0) + 1);
        }
        const entries = [...counts.entries()].sort((a, b) => b[1] - a[1]);
        const total = monthCompletedAppointments.length;
        const colors = entries.map(([name], i) => ddColorFor(name, i));

        destroyChart("ddTreatmentChart");
        chartInstances.ddTreatmentChart = new Chart(document.getElementById("ddTreatmentChart").getContext("2d"), {
            type: "doughnut",
            data: { labels: entries.map(e => e[0]), datasets: [{ data: entries.map(e => e[1]), backgroundColor: colors, borderWidth: 0 }] },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: "70%",
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => `${ctx.label}: ${ctx.parsed} (${Math.round(ctx.parsed / total * 100)}%)` } }
                }
            }
        });

        document.getElementById("ddDonutCenter").innerHTML =
            `<div class="dd-donut-center-value">${total}</div><div class="dd-donut-center-label">treatments</div>`;

        document.getElementById("ddTreatmentChart-legend").innerHTML = entries.map(([name, count], i) => {
            const pct = Math.round(count / total * 100);
            return `<span class="dd-legend-item">
                <span class="dd-legend-swatch" style="background:${colors[i]}"></span>
                ${escapeHtml(name)} ${pct}%
            </span>`;
        }).join("");
    }

    function renderDentistCompletionChart(weekAppointments) {
        const monday = mondayOfWeek(new Date());
        const weekdayDates = [];
        for (let i = 0; i < 5; i++) {
            const d = new Date(monday);
            d.setDate(monday.getDate() + i);
            weekdayDates.push(d.toISOString().split("T")[0]);
        }
        const labels = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"];

        let totalCompleted = 0;
        let totalCountable = 0;
        const rates = weekdayDates.map(date => {
            const dayAppointments = weekAppointments.filter(a => a.appointmentDate === date && a.status !== "CANCELLED");
            if (dayAppointments.length === 0) return null; // no data that day - don't plot a misleading 0%
            const completed = dayAppointments.filter(a => a.status === "COMPLETED").length;
            totalCompleted += completed;
            totalCountable += dayAppointments.length;
            return Math.round((completed / dayAppointments.length) * 100);
        });

        const summaryEl = document.getElementById("ddCompletionSummary");
        if (totalCountable === 0) {
            showChartEmpty("ddCompletionChart", "No appointment data available this week.");
            summaryEl.textContent = "No scheduled appointments this week.";
            return;
        }
        ddInsertLineCanvas("Line chart showing appointment completion rate for each weekday this week.");

        destroyChart("ddCompletionChart");
        chartInstances.ddCompletionChart = new Chart(document.getElementById("ddCompletionChart").getContext("2d"), {
            type: "line",
            data: {
                labels,
                datasets: [{
                    label: "Completion rate", data: rates,
                    borderColor: Theme.chart(0), backgroundColor: Theme.rgba("--sd-chart-1", 0.12),
                    pointBackgroundColor: Theme.chart(0), pointRadius: 3, borderWidth: 2,
                    tension: 0.1, fill: true, spanGaps: true,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                plugins: {
                    legend: { display: false },
                    tooltip: { callbacks: { label: (ctx) => ctx.parsed.y == null ? "No appointments" : `${ctx.parsed.y}% completed` } }
                },
                scales: {
                    x: { grid: { display: false } },
                    y: { min: 0, max: 100, grid: { color: "rgba(32,35,33,0.06)" }, ticks: { callback: (v) => v + "%" } }
                }
            }
        });

        const overallRate = Math.round((totalCompleted / totalCountable) * 100);
        summaryEl.innerHTML = `Averaging <strong>${overallRate}%</strong> of scheduled appointments completed.`;
    }

    function ddStatusPill(status) {
        const key = (status || "").toUpperCase();
        const cls = key === "COMPLETED" ? "dd-status-completed"
            : key === "CANCELLED" ? "dd-status-cancelled"
            : key === "CHECKED_IN" ? "dd-status-checked-in"
            : "dd-status-scheduled";
        return `<span class="dd-status-pill ${cls}"><span class="dd-status-dot"></span>${escapeHtml(Fmt.statusLabel(status).toLowerCase())}</span>`;
    }

    function renderDentistSchedule(today) {
        const container = document.getElementById("ddScheduleContainer");
        if (today.length === 0) {
            container.innerHTML = `<div class="dd-empty">No appointments today</div>`;
            return;
        }
        const sorted = [...today].sort((a, b) => a.appointmentTime.localeCompare(b.appointmentTime));
        container.innerHTML = sorted.map(a => `
            <div class="dd-schedule-row">
                <div class="dd-schedule-time">${Fmt.time(a.appointmentTime)}</div>
                <div class="dd-schedule-main">
                    <div class="dd-schedule-patient">${escapeHtml(a.patientName)}</div>
                    <div class="dd-schedule-treatment">${escapeHtml(a.treatmentType)}</div>
                </div>
                ${ddStatusPill(a.status)}
            </div>`).join("");
    }

    async function loadDentistDashboard() {
        document.getElementById("secondaryCard")?.remove();
        document.querySelector(".card:has(#quickActions)")?.remove();
        document.getElementById("statCardsCard")?.remove();
        document.getElementById("chartsSection")?.remove();
        document.querySelector(".card:has(#scheduleContainer)")?.remove();
        document.getElementById("activityCard")?.remove();

        const dash = document.getElementById("dentistDashboard");
        dash.style.display = "flex";
        dash.innerHTML = `<div class="dd-card"><div class="loading-inline"><span class="spinner"></span> Loading your dashboard...</div></div>`;

        try {
            const [today, all] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
            ]);

            dash.innerHTML = DENTIST_DASHBOARD_SKELETON;

            const upcoming = nextUpcoming(all);
            const completedTodayCount = today.filter(a => a.status === "COMPLETED").length;
            renderDentistStats(today, upcoming, completedTodayCount);
            renderDentistNextPatient(upcoming[0] || null);

            const monthCompleted = all.filter(a => a.appointmentDate.startsWith(monthPrefix()) && a.status === "COMPLETED");
            renderDentistTreatmentDonut(monthCompleted);

            const monday = mondayOfWeek(new Date());
            const friday = new Date(monday);
            friday.setDate(monday.getDate() + 4);
            const mondayIso = monday.toISOString().split("T")[0];
            const fridayIso = friday.toISOString().split("T")[0];
            const weekAppointments = all.filter(a => a.appointmentDate >= mondayIso && a.appointmentDate <= fridayIso);
            renderDentistCompletionChart(weekAppointments);

            renderDentistSchedule(today);
        } catch (err) {
            dash.innerHTML = `<div class="dd-card"><div class="empty-state">
                <div class="empty-title">We couldn't load your dashboard</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
                <button type="button" class="btn btn-secondary" style="margin-top:0.9rem;" id="ddRetryBtn">Retry</button>
            </div></div>`;
            document.getElementById("ddRetryBtn").addEventListener("click", loadDentistDashboard);
        }
    }

    // --- CLINICAL ASSISTANT -------------------------------------------------
    // Fully separate, self-contained redesign: removes every generic shared
    // dashboard block below and renders only into #clinicalAssistantDashboard,
    // so this cannot affect the Admin/Receptionist/Billing/Dentist layouts.

    const CA_DASHBOARD_SKELETON = `
        <div class="ca-context-bar" id="caContextBar"></div>
        <div class="ca-stats-row" id="caStatsRow"></div>
        <div class="ca-card">
            <h2 class="ca-card-title">Today's clinical schedule</h2>
            <div id="caScheduleContainer"></div>
        </div>`;

    function caStatusPill(status) {
        const key = (status || "").toUpperCase();
        const cls = key === "COMPLETED" ? "ca-status-completed"
            : key === "CANCELLED" ? "ca-status-cancelled"
            : key === "CHECKED_IN" ? "ca-status-checked-in"
            : "ca-status-scheduled";
        return `<span class="ca-status-pill ${cls}"><span class="ca-status-dot"></span>${escapeHtml(Fmt.statusLabel(status).toLowerCase())}</span>`;
    }

    function renderCaContextBar(assignedDentistName, nextAppointment) {
        const rightHtml = nextAppointment
            ? `<span class="ca-context-label">Next patient</span>
               <span class="ca-context-value">${escapeHtml(nextAppointment.patientName)}</span>
               <span class="ca-context-time">&middot; ${nextAppointment.appointmentDate === todayIso() ? Fmt.time(nextAppointment.appointmentTime) : Fmt.date(nextAppointment.appointmentDate)}</span>`
            : `<span class="ca-context-label">No upcoming patients</span>`;

        document.getElementById("caContextBar").innerHTML = `
            <div class="ca-context-left">
                <span class="ca-context-icon" aria-hidden="true">⚕</span>
                <span class="ca-context-label">Assisting</span>
                <span class="ca-context-value">${escapeHtml(assignedDentistName)}</span>
            </div>
            <div class="ca-context-right">${rightHtml}</div>`;
    }

    function renderCaStats(todayCount, completedCount, upcomingCount) {
        document.getElementById("caStatsRow").innerHTML = [
            { value: todayCount, label: "today's appointments" },
            { value: completedCount, label: "completed today" },
            { value: upcomingCount, label: "upcoming patients" },
        ].map(s => `<div class="ca-stat-card">
            <div class="ca-stat-value">${s.value}</div>
            <div class="ca-stat-label">${s.label}</div>
        </div>`).join("");
    }

    function renderCaSchedule(todayAppointments, onCheckIn) {
        const container = document.getElementById("caScheduleContainer");
        if (todayAppointments.length === 0) {
            container.innerHTML = `<div class="ca-empty">No appointments today</div>`;
            return;
        }
        const sorted = [...todayAppointments].sort((a, b) => a.appointmentTime.localeCompare(b.appointmentTime));
        container.innerHTML = sorted.map(a => `
            <div class="ca-schedule-row">
                <div class="ca-schedule-time">${Fmt.time(a.appointmentTime)}</div>
                <div class="ca-schedule-main">
                    <div class="ca-schedule-patient">${escapeHtml(a.patientName)}</div>
                    <div class="ca-schedule-meta">${escapeHtml(a.treatmentType)} &middot; ${escapeHtml(a.dentistName)}</div>
                </div>
                <div class="ca-schedule-actions">
                    ${caStatusPill(a.status)}
                    ${a.status === "SCHEDULED" ? `<button type="button" class="btn btn-secondary btn-sm" data-checkin="${escapeHtml(a.appointmentNumber)}">Check in</button>` : ""}
                </div>
            </div>`).join("");

        container.querySelectorAll("[data-checkin]").forEach(btn => {
            btn.addEventListener("click", () => onCheckIn(btn.dataset.checkin, btn));
        });
    }

    async function loadClinicalAssistantDashboard(session) {
        document.getElementById("secondaryCard")?.remove();
        document.querySelector(".card:has(#quickActions)")?.remove();
        document.getElementById("statCardsCard")?.remove();
        document.getElementById("chartsSection")?.remove();
        document.querySelector(".card:has(#scheduleContainer)")?.remove();
        document.getElementById("activityCard")?.remove();

        const dash = document.getElementById("clinicalAssistantDashboard");
        dash.style.display = "flex";

        if (!session.assignedDentistId) {
            dash.innerHTML = `<div class="ca-card"><div class="ca-empty">No dentist assigned</div></div>`;
            return;
        }

        dash.innerHTML = `<div class="ca-card"><div class="loading-inline"><span class="spinner"></span> Loading your dashboard...</div></div>`;

        let today = [];
        let all = [];
        let assignedDentistName = "Not assigned";

        function renderAll() {
            const upcoming = nextUpcoming(all);
            const completedCount = today.filter(a => a.status === "COMPLETED").length;

            renderCaContextBar(assignedDentistName, upcoming[0] || null);
            renderCaStats(today.length, completedCount, upcoming.length);
            renderCaSchedule(today, handleCheckIn);
        }

        async function handleCheckIn(appointmentNumber, buttonEl) {
            buttonEl.disabled = true;
            buttonEl.textContent = "Checking in...";
            try {
                const result = await Api.put(`/appointments/${encodeURIComponent(appointmentNumber)}/status?status=CHECKED_IN`);
                Toast.success(result.message || `Appointment ${appointmentNumber} has been checked in.`);

                const markCheckedIn = (list) => {
                    const match = list.find(a => a.appointmentNumber === appointmentNumber);
                    if (match) match.status = "CHECKED_IN";
                };
                markCheckedIn(today);
                markCheckedIn(all);

                renderAll();
            } catch (err) {
                Toast.error(err.message || "We couldn't check in this patient right now. Please try again.");
                buttonEl.disabled = false;
                buttonEl.textContent = "Check in";
            }
        }

        try {
            const [todayData, allData, dentists] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
                Api.get("/dentists"),
            ]);
            today = todayData;
            all = allData;
            const assignedDentist = dentists.find(d => d.dentistId === session.assignedDentistId);
            assignedDentistName = assignedDentist ? assignedDentist.dentistName : "Not assigned";

            dash.innerHTML = CA_DASHBOARD_SKELETON;
            renderAll();
        } catch (err) {
            dash.innerHTML = `<div class="ca-card"><div class="empty-state">
                <div class="empty-title">We couldn't load your dashboard</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
                <button type="button" class="btn btn-secondary" style="margin-top:0.9rem;" id="caRetryBtn">Retry</button>
            </div></div>`;
            document.getElementById("caRetryBtn").addEventListener("click", () => loadClinicalAssistantDashboard(session));
        }
    }

    // --- BILLING -----------------------------------------------------------------

    async function loadBillingDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "Find Appointment", href: "search.html", icon: "▤" },
            { label: "Generate Bill", href: "billing.html", icon: "▧" },
            { label: "View Recent Bills", href: "billing.html", icon: "▣" },
        ]);
        document.getElementById("scheduleTitle").textContent = "Recent Bills";

        const chartsSection = document.getElementById("chartsSection");
        chartsSection.style.display = "block";
        chartsSection.innerHTML = `<div class="chart-grid single">
            ${chartCardHtml("revenueChart", "Revenue This Week", "Mon–Sat")}
        </div>`;

        try {
            const [bills, appointments] = await Promise.all([
                Api.get("/bills"),
                Api.get("/appointments?date=" + todayIso()),
            ]);
            const todaysBills = bills.filter(b => b.billDate.startsWith(todayIso()));
            const revenue = todaysBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);
            const monthBills = bills.filter(b => b.billDate.startsWith(monthPrefix()));
            const monthRevenue = monthBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);
            const billedNumbers = new Set(bills.map(b => b.appointmentNumber));
            const pending = appointments.filter(a => !billedNumbers.has(a.appointmentNumber));

            Metrics.render(document.getElementById("statCards"), [
                { label: "Today's Revenue", value: Fmt.currency(revenue), color: Theme.chart(3) },
                { label: "This Month's Revenue", value: Fmt.currency(monthRevenue) },
                { label: "Bills Generated Today", value: todaysBills.length },
                { label: "Pending Billing (Today)", value: pending.length },
            ]);

            renderRevenueTrendChart("revenueChart", bills);

            const container = document.getElementById("scheduleContainer");
            container.innerHTML = bills.length === 0
                ? `<div class="empty-state"><div class="empty-title">No bills found</div></div>`
                : `<div class="table-wrap"><table class="data-table">
                    <thead><tr><th>Patient</th><th>Appointment #</th><th>Treatment</th><th>Amount</th><th>Bill Date</th></tr></thead>
                    <tbody>${bills.slice(0, 8).map(b => `
                        <tr class="clickable" onclick="window.location.href='billing.html?number=${encodeURIComponent(b.appointmentNumber)}'">
                            <td class="table-primary-text">${escapeHtml(b.patientName)}</td>
                            <td>${escapeHtml(b.appointmentNumber)}</td>
                            <td>${escapeHtml(b.treatmentName || "")}</td>
                            <td>${Fmt.currency(b.totalAmount)}</td>
                            <td class="table-muted-text">${b.billDate.replace("T", " ")}</td>
                        </tr>`).join("")}</tbody>
                  </table></div>`;

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Appointments Requiring Billing";
            document.getElementById("secondaryContainer").innerHTML = pending.length === 0
                ? `<div class="empty-state"><div class="empty-desc">Every appointment today has been billed.</div></div>`
                : pending.map(a => `<div class="schedule-row" onclick="window.location.href='billing.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                    <div class="schedule-main">
                        <div class="schedule-patient">${escapeHtml(a.patientName)}</div>
                        <div class="schedule-meta">${escapeHtml(a.appointmentNumber)} &middot; ${escapeHtml(a.treatmentType)}</div>
                    </div>
                  </div>`).join("");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load the dashboard. Please try again.</div></div>`;
            showChartError("revenueChart", loadBillingDashboard);
        }
    }
})();
