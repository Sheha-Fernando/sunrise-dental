(function () {
    const ROLE_SUBTITLE = {
        ADMIN: "Today's Clinic Overview",
        RECEPTIONIST: "Today's Front Desk Overview",
        DENTIST: "Your Clinical Schedule",
        BILLING: "Today's Billing Overview",
        CLINICAL_ASSISTANT: "Your Clinical Schedule",
    };

    document.addEventListener("shell:ready", (e) => onReady(e.detail));

    function onReady(session) {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        document.getElementById("greeting").textContent = `Welcome back, ${session.fullName || session.username}`;
        document.getElementById("dateLine").textContent = ROLE_SUBTITLE[session.role] || "";

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

    async function loadAdminDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "New Appointment", href: "appointments.html?action=new", icon: "▤" },
            { label: "Register Patient", href: "patients.html?action=new", icon: "▥" },
            { label: "Create Bill", href: "billing.html", icon: "▧" },
            { label: "Add Staff", href: "staff.html?action=new", icon: "▦" },
        ]);

        try {
            const [appointments, bills, patients] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/bills"),
                Api.get("/patients"),
            ]);

            const completed = appointments.filter(a => a.status === "COMPLETED").length;
            const todaysBills = bills.filter(b => b.billDate.startsWith(todayIso()));
            const revenue = todaysBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", appointments.length) +
                statCard("Total Patients", patients.length) +
                statCard("Completed Today", completed) +
                statCard("Today's Revenue", Fmt.currency(revenue), null, true);

            renderScheduleTable(appointments, "There are no appointments scheduled for today.");

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Recent Activity";
            const recentActivity = bills.slice(0, 5);
            document.getElementById("secondaryContainer").innerHTML = recentActivity.length === 0
                ? `<div class="empty-state"><div class="empty-desc">No recent billing activity.</div></div>`
                : recentActivity.map(b => `<div class="schedule-row" style="cursor:default;">
                    <div class="schedule-main">
                        <div class="schedule-patient">Bill generated for ${escapeHtml(b.patientName)}</div>
                        <div class="schedule-meta">${escapeHtml(b.appointmentNumber)} &middot; ${Fmt.currency(b.totalAmount)}</div>
                    </div>
                  </div>`).join("");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load the dashboard. Please try again.</div></div>`;
        }
    }

    async function loadReceptionistDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "New Appointment", href: "appointments.html?action=new", icon: "▤" },
            { label: "Register Patient", href: "patients.html?action=new", icon: "▥" },
            { label: "Find Patient", href: "patients.html", icon: "▥" },
        ]);

        try {
            const [today, all] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
            ]);
            const now = new Date();
            const upcoming = all.filter(a => {
                const d = new Date(a.appointmentDate + "T" + a.appointmentTime);
                return d > now && a.status === "SCHEDULED";
            }).slice(0, 5);

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", today.length) +
                statCard("Upcoming", upcoming.length) +
                statCard("Completed Today", today.filter(a => a.status === "COMPLETED").length);

            renderScheduleTable(today, "There are no appointments scheduled for today.");

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Upcoming Appointments";
            document.getElementById("secondaryContainer").innerHTML = upcoming.length === 0
                ? `<div class="empty-state"><div class="empty-desc">No upcoming appointments scheduled.</div></div>`
                : upcoming.map(a => `<div class="schedule-row" onclick="window.location.href='search.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                    <div class="schedule-time">${Fmt.date(a.appointmentDate)}</div>
                    <div class="schedule-main">
                        <div class="schedule-patient">${escapeHtml(a.patientName)}</div>
                        <div class="schedule-meta">${escapeHtml(a.dentistName)} &middot; ${Fmt.time(a.appointmentTime)}</div>
                    </div>
                  </div>`).join("");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load the dashboard. Please try again.</div></div>`;
        }
    }

    async function loadDentistDashboard() {
        document.getElementById("secondaryCard").remove?.();
        document.querySelector(".card:has(#quickActions)")?.remove?.();
        try {
            const [today, all] = await Promise.all([
                Api.get("/appointments?date=" + todayIso()),
                Api.get("/appointments"),
            ]);
            const now = new Date();
            const upcoming = all.filter(a => {
                const d = new Date(a.appointmentDate + "T" + a.appointmentTime);
                return d > now && a.status === "SCHEDULED";
            });
            const completed = today.filter(a => a.status === "COMPLETED").length;

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", today.length) +
                statCard("Upcoming Patients", upcoming.length) +
                statCard("Completed Visits", completed);

            document.getElementById("scheduleTitle").textContent = "Today's Appointments";
            renderScheduleTable(today, "You have no appointments scheduled for today.");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load your schedule. Please try again.</div></div>`;
        }
    }

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

            const now = new Date();
            const upcoming = all
                .filter(a => new Date(a.appointmentDate + "T" + a.appointmentTime) > now && a.status === "SCHEDULED")
                .sort((a, b) => (a.appointmentDate + a.appointmentTime).localeCompare(b.appointmentDate + b.appointmentTime));
            const nextAppointment = upcoming[0];
            const nextAppointmentLabel = nextAppointment
                ? `${Fmt.date(nextAppointment.appointmentDate)}, ${Fmt.time(nextAppointment.appointmentTime)}`
                : "None scheduled";

            document.getElementById("statCards").innerHTML =
                statCard("Today's Appointments", today.length) +
                statCard("Upcoming Patients", upcoming.length) +
                statCard("Assigned Dentist", escapeHtml(assignedDentistName), null, true) +
                statCard("Next Appointment", escapeHtml(nextAppointmentLabel));

            renderScheduleTable(today, "There are no appointments scheduled for today.");
        } catch (err) {
            document.getElementById("scheduleContainer").innerHTML =
                `<div class="empty-state"><div class="empty-desc">We couldn't load your schedule. Please try again.</div></div>`;
        }
    }

    async function loadBillingDashboard() {
        document.getElementById("quickActions").innerHTML = quickActionsHtml([
            { label: "Create Bill", href: "billing.html", icon: "▧" },
            { label: "Find Appointment", href: "search.html", icon: "▤" },
        ]);
        document.getElementById("scheduleTitle").textContent = "Recent Bills";

        try {
            const [bills, appointments] = await Promise.all([
                Api.get("/bills"),
                Api.get("/appointments?date=" + todayIso()),
            ]);
            const todaysBills = bills.filter(b => b.billDate.startsWith(todayIso()));
            const revenue = todaysBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);
            const billedNumbers = new Set(bills.map(b => b.appointmentNumber));
            const pending = appointments.filter(a => !billedNumbers.has(a.appointmentNumber));

            document.getElementById("statCards").innerHTML =
                statCard("Today's Bills", todaysBills.length) +
                statCard("Today's Revenue", Fmt.currency(revenue), null, true) +
                statCard("Pending Billing (Today)", pending.length);

            const container = document.getElementById("scheduleContainer");
            container.innerHTML = bills.length === 0
                ? `<div class="empty-state"><div class="empty-title">No bills found</div></div>`
                : `<div class="schedule-list">` + bills.slice(0, 8).map(b => `
                    <div class="schedule-row" onclick="window.location.href='billing.html?number=${encodeURIComponent(b.appointmentNumber)}'">
                        <div class="schedule-main">
                            <div class="schedule-patient">${escapeHtml(b.patientName)}</div>
                            <div class="schedule-meta">${escapeHtml(b.appointmentNumber)} &middot; ${escapeHtml(b.dentistName)}</div>
                        </div>
                        <div class="table-primary-text">${Fmt.currency(b.totalAmount)}</div>
                    </div>`).join("") + `</div>`;

            document.getElementById("secondaryCard").style.display = "block";
            document.getElementById("secondaryTitle").textContent = "Pending Billing";
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
        }
    }
})();
