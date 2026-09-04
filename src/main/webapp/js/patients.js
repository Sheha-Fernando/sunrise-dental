(function () {
    let session = null;

    // DENTIST and CLINICAL_ASSISTANT are both scoped to a single dentist's
    // data and are forbidden from the general /api/patients endpoint - both
    // derive "My Patients" from their own (already server-scoped)
    // /api/appointments list instead. See patients.js/AppointmentServlet.
    function isScopedRole(role) {
        return role === "DENTIST" || role === "CLINICAL_ASSISTANT";
    }

    document.addEventListener("shell:ready", (e) => {
        session = e.detail;
        const patientId = new URLSearchParams(window.location.search).get("id");
        if (patientId && !isScopedRole(session.role)) {
            initProfile(Number(patientId));
        } else {
            initList();
        }
    });

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    // ---------------------------------------------------------------- list ----

    function initList() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("listTemplate").content.cloneNode(true));

        const canRegister = session.role === "ADMIN" || session.role === "RECEPTIONIST";
        if (isScopedRole(session.role)) {
            document.getElementById("pageTitle").textContent = "My Patients";
            document.querySelector('#pageContent .page-subtitle').textContent =
                "Patients from your own appointments.";
        }
        if (canRegister) {
            document.getElementById("headerActions").innerHTML =
                `<button type="button" class="btn btn-primary" id="openRegisterBtn">+ Register Patient</button>`;
            document.getElementById("openRegisterBtn").addEventListener("click", openRegisterModal);
        }
        wireRegisterModal();

        const searchInput = document.getElementById("filterSearch");
        if (isScopedRole(session.role)) {
            document.getElementById("patientStats").remove();
            document.getElementById("filterDentist").remove();
            document.getElementById("filterStatus").remove();
            document.getElementById("filterLastVisit").remove();
            document.getElementById("clearFiltersBtn").remove();
            searchInput.placeholder = "Filter by patient name...";
            loadDentistPatients();
            searchInput.addEventListener("input", () => renderDentistPatients(searchInput.value));
        } else {
            loadDentistFilterOptions();
            loadPatients();
            searchInput.addEventListener("input", renderFiltered);
            document.getElementById("filterDentist").addEventListener("change", renderFiltered);
            document.getElementById("filterStatus").addEventListener("change", renderFiltered);
            document.getElementById("filterLastVisit").addEventListener("change", renderFiltered);
            document.getElementById("clearFiltersBtn").addEventListener("click", () => {
                searchInput.value = "";
                document.getElementById("filterDentist").value = "";
                document.getElementById("filterStatus").value = "";
                document.getElementById("filterLastVisit").value = "";
                renderFiltered();
            });
        }

        if (new URLSearchParams(window.location.search).get("action") === "new" && canRegister) {
            openRegisterModal();
        }
    }

    let allPatients = [];

    async function loadPatients() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading patients...</div>`;
        try {
            allPatients = await Api.get("/patients");
            renderFiltered();
            loadPatientStats();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load patients</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
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

    function matchesLastVisit(lastVisitDate, range) {
        if (!range) return true;
        if (!lastVisitDate) return false;
        const visit = new Date(lastVisitDate + "T00:00:00");
        const now = new Date();
        if (range === "WEEK") {
            const weekAgo = new Date(now);
            weekAgo.setDate(now.getDate() - 7);
            return visit >= weekAgo && visit <= now;
        }
        if (range === "MONTH") {
            return visit.getFullYear() === now.getFullYear() && visit.getMonth() === now.getMonth();
        }
        if (range === "YEAR") {
            return visit.getFullYear() === now.getFullYear();
        }
        return true;
    }

    function renderFiltered() {
        const search = document.getElementById("filterSearch").value.trim().toLowerCase();
        const dentist = document.getElementById("filterDentist").value;
        const status = document.getElementById("filterStatus").value;
        const lastVisit = document.getElementById("filterLastVisit").value;

        let filtered = allPatients;
        if (search) {
            filtered = filtered.filter(p => p.patientName.toLowerCase().includes(search)
                || p.contactNumber.toLowerCase().includes(search)
                || String(p.patientId).includes(search));
        }
        if (dentist) {
            filtered = filtered.filter(p => p.assignedDentistName === dentist);
        }
        if (status) {
            filtered = filtered.filter(p => p.status === status);
        }
        if (lastVisit) {
            filtered = filtered.filter(p => matchesLastVisit(p.lastVisitDate, lastVisit));
        }

        renderPatientTable(filtered, true);
    }

    const STATUS_BADGE_LABELS = { UPCOMING: "Upcoming", NEW: "New", ACTIVE: "Active" };

    function statusBadge(status) {
        const label = STATUS_BADGE_LABELS[status] || "Active";
        const cls = status === "UPCOMING" ? "badge-upcoming" : status === "NEW" ? "badge-new" : "badge-active";
        return `<span class="badge ${cls}">${label}</span>`;
    }

    function renderPatientTable(patients, clickable) {
        const container = document.getElementById("listContainer");
        if (patients.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No patients found</div>
                <div class="empty-desc">Try adjusting your search or filters.</div>
            </div>`;
            return;
        }
        const rows = patients.map(p => `
            <tr ${clickable ? `class="clickable" onclick="window.location.href='patients.html?id=${p.patientId}'"` : ""}>
                <td>
                    <div class="table-primary-text">${escapeHtml(p.patientName)}</div>
                    <div class="table-muted-text">PT-${String(p.patientId).padStart(6, "0")}</div>
                </td>
                <td>${escapeHtml(p.contactNumber)}</td>
                <td>${p.assignedDentistName ? escapeHtml(p.assignedDentistName) : "&mdash;"}</td>
                <td>${p.lastVisitDate ? Fmt.date(p.lastVisitDate) : "&mdash;"}</td>
                <td>${p.nextAppointmentDate ? Fmt.date(p.nextAppointmentDate) + (p.nextAppointmentTime ? " &middot; " + Fmt.time(p.nextAppointmentTime) : "") : "&mdash;"}</td>
                <td>${statusBadge(p.status)}</td>
            </tr>`).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Patient</th><th>Contact</th><th>Assigned Dentist</th><th>Last Visit</th><th>Next Appointment</th><th>Status</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    function loadPatientStats() {
        const statsEl = document.getElementById("patientStatsRow");
        try {
            const patients = allPatients;
            const now = new Date();
            const currentMonth = now.getMonth();
            const currentYear = now.getFullYear();
            const withUpcoming = patients.filter(p => p.nextAppointmentDate).length;
            const newThisMonth = patients.filter(p => {
                if (!p.registeredDate) return false;
                const d = new Date(p.registeredDate);
                return d.getMonth() === currentMonth && d.getFullYear() === currentYear;
            }).length;
            const visitedThisMonth = patients.filter(p => {
                if (!p.lastVisitDate) return false;
                const d = new Date(p.lastVisitDate);
                return d.getMonth() === currentMonth && d.getFullYear() === currentYear;
            }).length;

            Metrics.render(statsEl, [
                { label: "Total Patients", value: patients.length },
                { label: "With Upcoming Appointment", value: withUpcoming },
                { label: "Visited This Month", value: visitedThisMonth },
                { label: "New This Month", value: newThisMonth },
            ]);
        } catch (err) {
            statsEl.innerHTML = "";
        }
    }

    let dentistPatients = [];
    let ownDentistName = "";

    async function loadDentistPatients() {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading patients...</div>`;
        try {
            const [appointments, dentists] = await Promise.all([
                Api.get("/appointments"),
                Api.get("/dentists"),
            ]);
            const ownDentistId = session.role === "DENTIST" ? session.dentistId : session.assignedDentistId;
            const ownDentist = dentists.find(d => d.dentistId === ownDentistId);
            ownDentistName = ownDentist ? ownDentist.dentistName : "";

            const now = new Date();
            const byName = new Map();
            for (const a of appointments) {
                const key = a.patientName + "|" + a.contactNumber;
                if (!byName.has(key)) {
                    byName.set(key, {
                        patientName: a.patientName,
                        contactNumber: a.contactNumber,
                        lastVisitDate: null,
                        lastVisitNumber: null,
                        nextAppointmentDate: null,
                        nextAppointmentTime: null,
                        nextAppointmentNumber: null,
                        hasCompleted: false,
                    });
                }
                const entry = byName.get(key);
                const isFuture = new Date(a.appointmentDate + "T" + a.appointmentTime) >= now;
                if (a.status === "SCHEDULED" && isFuture) {
                    if (!entry.nextAppointmentDate
                            || (a.appointmentDate + a.appointmentTime) < (entry.nextAppointmentDate + entry.nextAppointmentTime)) {
                        entry.nextAppointmentDate = a.appointmentDate;
                        entry.nextAppointmentTime = a.appointmentTime;
                        entry.nextAppointmentNumber = a.appointmentNumber;
                    }
                }
                if (a.status !== "CANCELLED" && (!entry.lastVisitDate || a.appointmentDate > entry.lastVisitDate)) {
                    entry.lastVisitDate = a.appointmentDate;
                    entry.lastVisitNumber = a.appointmentNumber;
                }
                if (a.status === "COMPLETED") {
                    entry.hasCompleted = true;
                }
            }
            // Same Upcoming -> New -> Active priority the backend uses for
            // the general patient list, computed here from the same real
            // appointment statuses since a scoped dentist has no access to
            // the /api/patients endpoint this data would otherwise come from.
            for (const entry of byName.values()) {
                entry.status = entry.nextAppointmentDate ? "UPCOMING" : (entry.hasCompleted ? "ACTIVE" : "NEW");
            }
            dentistPatients = [...byName.values()].sort((a, b) => a.patientName.localeCompare(b.patientName));
            renderDentistPatients("");
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load your patients</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderDentistPatients(filterText) {
        const filtered = filterText
            ? dentistPatients.filter(p => p.patientName.toLowerCase().includes(filterText.toLowerCase()))
            : dentistPatients;
        const container = document.getElementById("listContainer");
        if (filtered.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No patients found</div>
                <div class="empty-desc">You have no recorded patients yet.</div>
            </div>`;
            return;
        }
        const rows = filtered.map(p => {
            const targetNumber = p.nextAppointmentNumber || p.lastVisitNumber;
            return `
            <tr class="clickable" onclick="window.location.href='search.html?number=${encodeURIComponent(targetNumber)}'">
                <td class="table-primary-text">${escapeHtml(p.patientName)}</td>
                <td>${escapeHtml(p.contactNumber)}</td>
                <td>${escapeHtml(ownDentistName)}</td>
                <td>${p.lastVisitDate ? Fmt.date(p.lastVisitDate) : "&mdash;"}</td>
                <td>${p.nextAppointmentDate ? Fmt.date(p.nextAppointmentDate) + " &middot; " + Fmt.time(p.nextAppointmentTime) : "&mdash;"}</td>
                <td>${statusBadge(p.status)}</td>
            </tr>`;
        }).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Patient</th><th>Contact</th><th>Assigned Dentist</th><th>Last Visit</th><th>Next Appointment</th><th>Status</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    // -------------------------------------------------------- register modal ----

    function openRegisterModal() {
        document.getElementById("registerModalOverlay").classList.add("open");
        document.getElementById("regPatientName").focus();
    }

    function closeRegisterModal() {
        document.getElementById("registerModalOverlay").classList.remove("open");
        document.getElementById("registerForm").reset();
        document.getElementById("registerAlert").classList.remove("visible");
    }

    function wireRegisterModal() {
        document.getElementById("closeRegisterModal").addEventListener("click", closeRegisterModal);
        document.getElementById("cancelRegisterBtn").addEventListener("click", closeRegisterModal);
        document.getElementById("registerModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "registerModalOverlay") closeRegisterModal();
        });

        document.getElementById("registerForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            const name = document.getElementById("regPatientName");
            const address = document.getElementById("regAddress");
            const contact = document.getElementById("regContact");
            const fields = [
                { input: name, error: document.getElementById("regPatientNameError") },
                { input: address, error: document.getElementById("regAddressError") },
                { input: contact, error: document.getElementById("regContactError") },
            ];
            let valid = true;
            for (const f of fields) {
                if (f.input.value.trim() === "") {
                    f.input.setAttribute("aria-invalid", "true");
                    f.error.classList.add("visible");
                    valid = false;
                } else {
                    f.input.removeAttribute("aria-invalid");
                    f.error.classList.remove("visible");
                }
            }
            if (!valid) return;

            const submitBtn = document.getElementById("registerSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = "Registering...";
            const alertEl = document.getElementById("registerAlert");
            alertEl.classList.remove("visible");

            try {
                await Api.post("/patients", {
                    patientName: name.value.trim(),
                    address: address.value.trim(),
                    contactNumber: contact.value.trim(),
                });
                closeRegisterModal();
                Toast.success("Patient registered successfully.");
                loadPatients();
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "Register Patient";
            }
        });
    }

    // ---------------------------------------------------------------- profile ----

    let currentProfilePatient = null;

    async function initProfile(patientId) {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("profileTemplate").content.cloneNode(true));
        wireEditModal(patientId);

        try {
            const result = await Api.get("/patients/" + patientId);
            currentProfilePatient = result.patient;
            document.getElementById("profileName").textContent = result.patient.patientName;
            document.getElementById("profileMeta").textContent = "Patient ID: P-" + String(patientId).padStart(6, "0");
            document.getElementById("profileContact").textContent = result.patient.contactNumber;
            document.getElementById("profileAddress").textContent = result.patient.address;

            const canEdit = session.role === "ADMIN" || session.role === "RECEPTIONIST";
            if (canEdit) {
                document.getElementById("profileActions").innerHTML =
                    `<button type="button" class="btn btn-secondary" id="openEditPatientBtn">Edit Patient</button>` +
                    `<a href="appointment.html" class="btn btn-primary">New Appointment</a>`;
                document.getElementById("openEditPatientBtn").addEventListener("click", () => openEditModal());
            }

            const history = result.appointmentHistory;
            const historyContainer = document.getElementById("historyContainer");
            if (history.length === 0) {
                historyContainer.innerHTML = `<div class="empty-state">
                    <div class="empty-title">No appointment history</div>
                    <div class="empty-desc">This patient has no recorded appointments yet.</div>
                </div>`;
                return;
            }
            const rows = history.map(a => `
                <tr class="clickable" onclick="window.location.href='search.html?number=${encodeURIComponent(a.appointmentNumber)}'">
                    <td class="table-primary-text">${escapeHtml(a.appointmentNumber)}</td>
                    <td>${Fmt.date(a.appointmentDate)}<div class="table-muted-text">${Fmt.time(a.appointmentTime)}</div></td>
                    <td>${escapeHtml(a.dentistName)}</td>
                    <td>${escapeHtml(a.treatmentType)}</td>
                    <td><span class="${Fmt.statusBadgeClass(a.status)}">${Fmt.statusLabel(a.status)}</span></td>
                </tr>`).join("");
            historyContainer.innerHTML = `<div class="table-wrap"><table class="data-table">
                <thead><tr><th>Appointment #</th><th>Date &amp; Time</th><th>Dentist</th><th>Treatment</th><th>Status</th></tr></thead>
                <tbody>${rows}</tbody>
            </table></div>`;
        } catch (err) {
            content.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load this patient</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    // ------------------------------------------------------------- edit patient ----

    function openEditModal() {
        if (!currentProfilePatient) return;
        document.getElementById("editPatientName").value = currentProfilePatient.patientName;
        document.getElementById("editAddress").value = currentProfilePatient.address;
        document.getElementById("editContact").value = currentProfilePatient.contactNumber;
        document.getElementById("editAlert").classList.remove("visible");
        document.getElementById("editModalOverlay").classList.add("open");
        document.getElementById("editPatientName").focus();
    }

    function closeEditModal() {
        document.getElementById("editModalOverlay").classList.remove("open");
    }

    function wireEditModal(patientId) {
        document.getElementById("closeEditModal").addEventListener("click", closeEditModal);
        document.getElementById("cancelEditBtn").addEventListener("click", closeEditModal);
        document.getElementById("editModalOverlay").addEventListener("click", (e) => {
            if (e.target.id === "editModalOverlay") closeEditModal();
        });

        document.getElementById("editForm").addEventListener("submit", async (event) => {
            event.preventDefault();
            const name = document.getElementById("editPatientName");
            const address = document.getElementById("editAddress");
            const contact = document.getElementById("editContact");
            const fields = [
                { input: name, error: document.getElementById("editPatientNameError") },
                { input: address, error: document.getElementById("editAddressError") },
                { input: contact, error: document.getElementById("editContactError") },
            ];
            let valid = true;
            for (const f of fields) {
                if (f.input.value.trim() === "") {
                    f.input.setAttribute("aria-invalid", "true");
                    f.error.classList.add("visible");
                    valid = false;
                } else {
                    f.input.removeAttribute("aria-invalid");
                    f.error.classList.remove("visible");
                }
            }
            if (!valid) return;

            const submitBtn = document.getElementById("editSubmitBtn");
            submitBtn.disabled = true;
            submitBtn.textContent = "Saving...";
            const alertEl = document.getElementById("editAlert");
            alertEl.classList.remove("visible");

            try {
                const params = new URLSearchParams({
                    patientName: name.value.trim(),
                    address: address.value.trim(),
                    contactNumber: contact.value.trim(),
                });
                const result = await Api.put(`/patients/${patientId}?${params.toString()}`);
                currentProfilePatient = result.patient;
                document.getElementById("profileName").textContent = result.patient.patientName;
                document.getElementById("profileContact").textContent = result.patient.contactNumber;
                document.getElementById("profileAddress").textContent = result.patient.address;
                closeEditModal();
                Toast.success("Patient details updated successfully.");
            } catch (err) {
                alertEl.textContent = err.message;
                alertEl.classList.add("visible");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "Save Changes";
            }
        });
    }
})();
