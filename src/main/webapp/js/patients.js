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
            searchInput.placeholder = "Filter by patient name...";
            loadDentistPatients();
            searchInput.addEventListener("input", () => renderDentistPatients(searchInput.value));
        } else {
            let debounce = null;
            loadPatients("");
            searchInput.addEventListener("input", () => {
                clearTimeout(debounce);
                debounce = setTimeout(() => loadPatients(searchInput.value.trim()), 300);
            });
        }

        if (new URLSearchParams(window.location.search).get("action") === "new" && canRegister) {
            openRegisterModal();
        }
    }

    async function loadPatients(query) {
        const container = document.getElementById("listContainer");
        container.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Loading patients...</div>`;
        try {
            const path = query ? "/patients?q=" + encodeURIComponent(query) : "/patients";
            const patients = await Api.get(path);
            renderPatientTable(patients, true);
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load patients</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderPatientTable(patients, clickable) {
        const container = document.getElementById("listContainer");
        if (patients.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No patients found</div>
                <div class="empty-desc">Try a different search or register a new patient.</div>
            </div>`;
            return;
        }
        const rows = patients.map(p => `
            <tr ${clickable ? `class="clickable" onclick="window.location.href='patients.html?id=${p.patientId}'"` : ""}>
                <td class="table-primary-text">${escapeHtml(p.patientName)}</td>
                <td>${escapeHtml(p.contactNumber)}</td>
                <td>${p.assignedDentistName ? escapeHtml(p.assignedDentistName) : "&mdash;"}</td>
                <td>${p.lastVisitDate ? Fmt.date(p.lastVisitDate) : "&mdash;"}</td>
                <td>${p.nextAppointmentDate ? Fmt.date(p.nextAppointmentDate) + (p.nextAppointmentTime ? " &middot; " + Fmt.time(p.nextAppointmentTime) : "") : "&mdash;"}</td>
                <td><span class="badge badge-active">Active</span></td>
            </tr>`).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Patient</th><th>Contact</th><th>Assigned Dentist</th><th>Last Visit</th><th>Next Appointment</th><th>Status</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
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
                <td><span class="badge badge-active">Active</span></td>
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
                loadPatients("");
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

    async function initProfile(patientId) {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("profileTemplate").content.cloneNode(true));

        try {
            const result = await Api.get("/patients/" + patientId);
            document.getElementById("profileName").textContent = result.patient.patientName;
            document.getElementById("profileMeta").textContent = "Patient ID: P-" + String(patientId).padStart(6, "0");
            document.getElementById("profileContact").textContent = result.patient.contactNumber;
            document.getElementById("profileAddress").textContent = result.patient.address;

            const canCreate = session.role === "ADMIN" || session.role === "RECEPTIONIST";
            if (canCreate) {
                document.getElementById("profileActions").innerHTML =
                    `<a href="appointment.html" class="btn btn-primary">New Appointment</a>`;
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
})();
