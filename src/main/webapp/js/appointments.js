(function () {
    let allAppointments = [];

    let session = null;

    document.addEventListener("shell:ready", (e) => onReady(e.detail));

    function onReady(currentSession) {
        session = currentSession;
        AppointmentActions.init(session);

        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        if (session.role === "DENTIST" || session.role === "CLINICAL_ASSISTANT") {
            document.getElementById("pageTitle").textContent = "My Schedule Today";
        }

        const canCreate = session.role === "ADMIN" || session.role === "RECEPTIONIST";
        if (canCreate) {
            document.getElementById("headerActions").innerHTML =
                `<a href="appointment.html" class="btn btn-primary">+ New Appointment</a>`;
        }

        const showsAllDentists = session.role !== "DENTIST" && session.role !== "CLINICAL_ASSISTANT";
        if (showsAllDentists) {
            loadDentistFilterOptions();
        }

        // Default view: prioritize today's schedule rather than loading all
        // historical appointments up front - every role can clear the date
        // filter to see everything.
        document.getElementById("filterDate").value = todayIso();

        document.getElementById("filterSearch").addEventListener("input", renderFiltered);
        document.getElementById("filterDate").addEventListener("change", loadAppointments);
        document.getElementById("filterDentist").addEventListener("change", renderFiltered);
        document.getElementById("filterStatus").addEventListener("change", renderFiltered);
        document.getElementById("clearFiltersBtn").addEventListener("click", () => {
            document.getElementById("filterSearch").value = "";
            document.getElementById("filterDate").value = "";
            document.getElementById("filterDentist").value = "";
            document.getElementById("filterStatus").value = "";
            loadAppointments();
        });
        // Capture phase: must intercept before the row's own onclick (which
        // navigates to the detail page) fires during the bubble phase.
        document.getElementById("listContainer").addEventListener("click", (e) => {
            AppointmentActions.delegate(e, () => loadAppointments());
        }, true);

        loadAppointments();
    }

    function todayIso() {
        return new Date().toISOString().split("T")[0];
    }

    async function loadDentistFilterOptions() {
        const select = document.getElementById("filterDentist");
        try {
            const dentists = await Api.get("/dentists");
            select.innerHTML = '<option value="">All dentists</option>' +
                dentists.map(d => `<option value="${escapeHtml(d.dentistName)}">${escapeHtml(d.dentistName)}</option>`).join("");
            select.style.display = "";
        } catch (err) {
            // Non-fatal - the filter simply stays hidden if the dentist list can't load.
        }
    }

    async function loadAppointments() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading appointments...</div>`;
        try {
            const date = document.getElementById("filterDate").value;
            const path = date ? "/appointments?date=" + encodeURIComponent(date) : "/appointments";
            allAppointments = await Api.get(path);
            renderFiltered();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load appointments</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderFiltered() {
        const search = document.getElementById("filterSearch").value.trim().toLowerCase();
        const status = document.getElementById("filterStatus").value;
        const dentistFilter = document.getElementById("filterDentist").value;

        let filtered = allAppointments;
        if (search) {
            filtered = filtered.filter(a => a.patientName.toLowerCase().includes(search)
                || a.appointmentNumber.toLowerCase().includes(search));
        }
        if (status) {
            filtered = filtered.filter(a => a.status === status);
        }
        if (dentistFilter) {
            filtered = filtered.filter(a => a.dentistName === dentistFilter);
        }
        filtered = [...filtered].sort((a, b) =>
            (a.appointmentDate + a.appointmentTime).localeCompare(b.appointmentDate + b.appointmentTime));

        renderList(filtered);
    }

    function renderList(appointments) {
        const container = document.getElementById("listContainer");
        if (appointments.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No appointments found</div>
                <div class="empty-desc">There are no appointments matching your filters.</div>
            </div>`;
            return;
        }

        AppointmentActions.registerAll(appointments);
        const rows = appointments.map(a => {
            const viewHref = "search.html?number=" + encodeURIComponent(a.appointmentNumber);
            return `
            <tr class="clickable" onclick="window.location.href='${viewHref}'">
                <td class="table-primary-text">${escapeHtml(a.appointmentNumber)}</td>
                <td>${Fmt.date(a.appointmentDate)}<div class="table-muted-text">${Fmt.time(a.appointmentTime)}</div></td>
                <td>${escapeHtml(a.patientName)}</td>
                <td>${escapeHtml(a.dentistName)}</td>
                <td>${escapeHtml(a.treatmentType)}</td>
                <td><span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span></td>
                <td>${AppointmentActions.actionsHtml(a, viewHref)}</td>
            </tr>`;
        }).join("");

        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr>
                <th>Appointment #</th><th>Date &amp; Time</th><th>Patient</th>
                <th>Dentist</th><th>Treatment</th><th>Status</th><th>Actions</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }
})();
