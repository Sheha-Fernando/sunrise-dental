(function () {
    let allAppointments = [];

    document.addEventListener("shell:ready", (e) => onReady(e.detail));

    function onReady(session) {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        if (session.role === "DENTIST" || session.role === "CLINICAL_ASSISTANT") {
            document.getElementById("pageTitle").textContent = "My Schedule";
        }

        const canCreate = session.role === "ADMIN" || session.role === "RECEPTIONIST";
        if (canCreate) {
            document.getElementById("headerActions").innerHTML =
                `<a href="appointment.html" class="btn btn-primary">+ New Appointment</a>`;
        }

        document.getElementById("filterSearch").addEventListener("input", renderFiltered);
        document.getElementById("filterDate").addEventListener("change", loadAppointments);
        document.getElementById("filterStatus").addEventListener("change", renderFiltered);
        document.getElementById("clearFiltersBtn").addEventListener("click", () => {
            document.getElementById("filterSearch").value = "";
            document.getElementById("filterDate").value = "";
            document.getElementById("filterStatus").value = "";
            loadAppointments();
        });

        loadAppointments();
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

        let filtered = allAppointments;
        if (search) {
            filtered = filtered.filter(a => a.patientName.toLowerCase().includes(search)
                || a.appointmentNumber.toLowerCase().includes(search));
        }
        if (status) {
            filtered = filtered.filter(a => a.status === status);
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

        const rows = appointments.map(a => `
            <tr class="clickable" onclick="window.location.href='search.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                <td class="table-primary-text">${escapeHtml(a.appointmentNumber)}</td>
                <td>${Fmt.date(a.appointmentDate)}<div class="table-muted-text">${Fmt.time(a.appointmentTime)}</div></td>
                <td>${escapeHtml(a.patientName)}</td>
                <td>${escapeHtml(a.dentistName)}</td>
                <td>${escapeHtml(a.treatmentType)}</td>
                <td><span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span></td>
            </tr>`).join("");

        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr>
                <th>Appointment #</th><th>Date &amp; Time</th><th>Patient</th>
                <th>Dentist</th><th>Treatment</th><th>Status</th>
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
