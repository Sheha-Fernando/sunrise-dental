(function () {
    // Standard clinic time slots (matches appointment.js's availability grid) -
    // used only to compute a real "available slots today" figure, never to
    // fabricate appointment data.
    const CLINIC_TIMES = ["08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
        "13:30", "14:00", "14:30", "15:00", "15:30", "16:00"];

    const STATUS_COLORS = { SCHEDULED: "#2A78D6", COMPLETED: "#1BAF7A", CANCELLED: "#EB6834" };
    const STATUS_LABELS = { SCHEDULED: "Scheduled", COMPLETED: "Completed", CANCELLED: "Cancelled" };
    const DOUGHNUT_PALETTE = ["#B89552", "#2A78D6", "#1BAF7A", "#EB6834", "#8F7440", "#1D5D95", "#A6403A", "#6B706D"];

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

    function statCard(label, value, meta, accent) {
        return `<div class="stat-card">
            <div class="stat-label">${label}</div>
            <div class="stat-value${accent ? " accent" : ""}">${value}</div>
            ${meta ? `<div class="stat-meta">${meta}</div>` : ""}
        </div>`;
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
            data: { labels: dayLabels, datasets: [{ label: "Revenue", data: totals, backgroundColor: "#B89552", borderRadius: 4, maxBarThickness: 36 }] },
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
            <span class="chart-legend-dot" style="background:#B89552"></span>
            Total this week <span class="chart-legend-value">${Fmt.currency(grandTotal)}</span>
        </span>`;
    }

    function renderStatusSummary(scheduled, completed, cancelled) {
        document.getElementById("statusSummaryCard").style.display = "block";
        document.getElementById("statusSummaryContainer").innerHTML = [
            ["SCHEDULED", scheduled], ["COMPLETED", completed], ["CANCELLED", cancelled]
        ].map(([status, count]) => `<div class="status-summary-item">
                <span class="status-summary-dot" style="background:${STATUS_COLORS[status]}"></span>
                <div>
                    <div class="status-summary-count">${count}</div>
                    <div class="status-summary-label">${STATUS_LABELS[status]} Today</div>
                </div>
            </div>`).join("");
    }

    function renderClinicOverview(totalPatients, activeDentists, revenue) {
        document.getElementById("clinicOverviewCard").style.display = "block";
        document.getElementById("clinicOverviewContainer").innerHTML = [
            ["#2A78D6", totalPatients, "Total Patients"],
            ["#1BAF7A", activeDentists, "Active Dentists"],
            ["#B89552", Fmt.currency(revenue), "Today's Revenue"],
        ].map(([color, value, label]) => `<div class="status-summary-item">
                <span class="status-summary-dot" style="background:${color}"></span>
                <div>
                    <div class="status-summary-count">${value}</div>
                    <div class="status-summary-label">${label}</div>
                </div>
            </div>`).join("");
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
                color: "#B89552"
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

            document.getElementById("statCards").style.display = "none";

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

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", today.length) +
                statCard("Upcoming", upcoming.length) +
                statCard("Today's Patients", distinctPatientsToday) +
                statCard("Available Slots Today", availableSlots);

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

    // --- DENTIST ---------------------------------------------------------------

    async function loadDentistDashboard() {
        document.getElementById("secondaryCard").remove?.();
        document.querySelector(".card:has(#quickActions)")?.remove?.();
        document.getElementById("scheduleTitle").textContent = "My Schedule";

        const chartsSection = document.getElementById("chartsSection");
        chartsSection.style.display = "block";
        chartsSection.innerHTML = `<div class="chart-grid single">
            ${chartCardHtml("myTreatmentChart", "My Treatment Overview", "This month")}
        </div>`;

        try {
            const [today, all] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
            ]);
            const upcoming = nextUpcoming(all);
            const nextToday = upcoming.find(a => a.appointmentDate === todayIso());
            const nextPatientLabel = nextToday
                ? `${nextToday.patientName} &middot; ${Fmt.time(nextToday.appointmentTime)}`
                : (upcoming[0] ? `${upcoming[0].patientName} &middot; ${Fmt.date(upcoming[0].appointmentDate)}` : "None scheduled");
            const completed = today.filter(a => a.status === "COMPLETED").length;

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", today.length) +
                statCard("Next Patient", nextPatientLabel, null, true) +
                statCard("Patients Seen Today", completed) +
                statCard("Upcoming Appointments", upcoming.length);

            const thisMonth = all.filter(a => a.appointmentDate.startsWith(monthPrefix()));
            renderTreatmentsDoughnut("myTreatmentChart", thisMonth, "No treatment data available for this month.");

            renderScheduleTable(today, "You have no appointments scheduled for today.");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load your schedule. Please try again.</div></div>`;
            showChartError("myTreatmentChart", loadDentistDashboard);
        }
    }

    // --- CLINICAL ASSISTANT -----------------------------------------------------

    async function loadClinicalAssistantDashboard(session) {
        document.getElementById("secondaryCard").remove?.();
        document.querySelector(".card:has(#quickActions)")?.remove?.();
        document.getElementById("scheduleTitle").textContent = "Today's Clinical Schedule";

        try {
            const [today, all, dentists] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
                Api.get("/dentists"),
            ]);

            const assignedDentist = dentists.find(d => d.dentistId === session.assignedDentistId);
            const assignedDentistName = assignedDentist ? assignedDentist.dentistName : "Not assigned";

            const upcoming = nextUpcoming(all);
            const nextToday = upcoming.find(a => a.appointmentDate === todayIso());
            const nextPatientLabel = nextToday
                ? `${nextToday.patientName} &middot; ${Fmt.time(nextToday.appointmentTime)}`
                : (upcoming[0] ? `${upcoming[0].patientName} &middot; ${Fmt.date(upcoming[0].appointmentDate)}` : "None scheduled");

            document.getElementById("statCards").innerHTML =
                statCard("Assigned Dentist", escapeHtml(assignedDentistName), null, true) +
                statCard("Today's Appointments", today.length) +
                statCard("Next Patient", nextPatientLabel) +
                statCard("Upcoming Patients", upcoming.length);

            renderScheduleTable(today, "There are no appointments scheduled for today.");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load your schedule. Please try again.</div></div>`;
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

            document.getElementById("statCards").innerHTML =
                statCard("Today's Revenue", Fmt.currency(revenue), null, true) +
                statCard("This Month's Revenue", Fmt.currency(monthRevenue)) +
                statCard("Bills Generated Today", todaysBills.length) +
                statCard("Pending Billing (Today)", pending.length);

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
