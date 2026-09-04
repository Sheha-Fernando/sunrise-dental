(function () {
    let selectedPatientId = null;
    let usingExistingPatient = false;

    document.addEventListener("shell:ready", init);

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        const form = document.getElementById("appointmentForm");
        const formAlert = document.getElementById("formAlert");
        const submitButton = document.getElementById("submitButton");
        const successPanel = document.getElementById("successPanel");
        const dentistSelect = document.getElementById("dentistId");
        const treatmentSelect = document.getElementById("treatmentId");
        const dateInput = document.getElementById("appointmentDate");
        const timeInput = document.getElementById("appointmentTime");

        dateInput.min = new Date().toISOString().split("T")[0];

        const fields = [
            { input: dentistSelect, error: document.getElementById("dentistIdError") },
            { input: treatmentSelect, error: document.getElementById("treatmentIdError") },
            { input: dateInput, error: document.getElementById("appointmentDateError") },
            { input: timeInput, error: document.getElementById("appointmentTimeError") },
        ];

        function showAlert(message) {
            formAlert.textContent = message;
            formAlert.classList.add("visible");
            formAlert.scrollIntoView({ behavior: "smooth", block: "start" });
        }

        function hideAlert() {
            formAlert.classList.remove("visible");
            formAlert.textContent = "";
        }

        function setFieldError(field, show) {
            if (show) {
                field.input.setAttribute("aria-invalid", "true");
                field.error.classList.add("visible");
            } else {
                field.input.removeAttribute("aria-invalid");
                field.error.classList.remove("visible");
            }
        }

        // --- Patient: new vs existing ------------------------------------------------

        const newTab = document.getElementById("newPatientTab");
        const existingTab = document.getElementById("existingPatientTab");
        const newFields = document.getElementById("newPatientFields");
        const existingFields = document.getElementById("existingPatientFields");
        const patientSearch = document.getElementById("patientSearch");
        const patientResults = document.getElementById("patientSearchResults");
        const selectedSummary = document.getElementById("selectedPatientSummary");
        const patientIdError = document.getElementById("patientIdError");

        newTab.addEventListener("click", () => {
            usingExistingPatient = false;
            selectedPatientId = null;
            newTab.classList.add("active");
            existingTab.classList.remove("active");
            newFields.style.display = "block";
            existingFields.style.display = "none";
        });

        existingTab.addEventListener("click", () => {
            usingExistingPatient = true;
            existingTab.classList.add("active");
            newTab.classList.remove("active");
            newFields.style.display = "none";
            existingFields.style.display = "block";
        });

        let searchTimer = null;
        patientSearch.addEventListener("input", () => {
            clearTimeout(searchTimer);
            const query = patientSearch.value.trim();
            selectedSummary.style.display = "none";
            selectedPatientId = null;
            if (query.length < 2) {
                patientResults.innerHTML = "";
                return;
            }
            searchTimer = setTimeout(() => searchPatients(query), 300);
        });

        async function searchPatients(query) {
            try {
                const results = await Api.get("/patients?q=" + encodeURIComponent(query));
                if (results.length === 0) {
                    patientResults.innerHTML = `<div class="empty-state" style="padding:1rem;">
                        <div class="empty-desc">No matching patients. Try New Patient instead.</div>
                    </div>`;
                    return;
                }
                patientResults.innerHTML = `<div class="table-wrap"><table class="data-table">
                    <tbody>${results.map(p => `
                        <tr class="clickable" data-id="${p.patientId}" data-name="${escapeHtml(p.patientName)}" data-contact="${escapeHtml(p.contactNumber)}">
                            <td class="table-primary-text">${escapeHtml(p.patientName)}</td>
                            <td class="table-muted-text">${escapeHtml(p.contactNumber)}</td>
                        </tr>`).join("")}</tbody>
                </table></div>`;
                patientResults.querySelectorAll("tr[data-id]").forEach(row => {
                    row.addEventListener("click", () => {
                        selectedPatientId = Number(row.dataset.id);
                        selectedSummary.textContent = `Selected: ${row.dataset.name} (${row.dataset.contact})`;
                        selectedSummary.style.display = "flex";
                        patientResults.innerHTML = "";
                        patientSearch.value = "";
                        setFieldError({ input: patientSearch, error: patientIdError }, false);
                    });
                });
            } catch (err) {
                patientResults.innerHTML = `<div class="field-error visible">${escapeHtml(err.message)}</div>`;
            }
        }

        // --- Reference data -----------------------------------------------------------

        async function loadDentists() {
            try {
                const dentists = await Api.get("/dentists");
                if (!Array.isArray(dentists) || dentists.length === 0) {
                    dentistSelect.innerHTML = '<option value="">No dentists available</option>';
                    dentistSelect.disabled = true;
                    showAlert("No dentists are currently available. Please contact the administrator.");
                    return;
                }
                dentistSelect.innerHTML = '<option value="">Select dentist</option>' +
                    dentists.map(d => `<option value="${d.dentistId}">${escapeHtml(d.dentistName)}</option>`).join("");
            } catch (err) {
                dentistSelect.innerHTML = '<option value="">Unable to load dentists</option>';
                dentistSelect.disabled = true;
                showAlert(err.message);
            }
        }

        async function loadTreatments() {
            try {
                const treatments = await Api.get("/treatments");
                if (!Array.isArray(treatments) || treatments.length === 0) {
                    treatmentSelect.innerHTML = '<option value="">No treatments available</option>';
                    treatmentSelect.disabled = true;
                    showAlert("No treatments are currently available. Please contact the administrator.");
                    return;
                }
                treatmentSelect.innerHTML = '<option value="">Select treatment</option>' +
                    treatments.map(t => `<option value="${t.treatmentId}">${escapeHtml(t.treatmentName)} - ${Fmt.currency(t.cost)}</option>`).join("");
            } catch (err) {
                treatmentSelect.innerHTML = '<option value="">Unable to load treatments</option>';
                treatmentSelect.disabled = true;
                showAlert(err.message);
            }
        }

        function escapeHtml(value) {
            const div = document.createElement("div");
            div.textContent = value == null ? "" : value;
            return div.innerHTML;
        }

        // --- Availability grid -----------------------------------------------------
        // Shows every standard clinic slot as Available/Booked as soon as a dentist
        // and date are both chosen, so staff never have to guess or manually probe
        // a time - clicking an available slot fills the Time field. The backend's
        // UNIQUE constraint remains the real, authoritative protection against a
        // double-booking race; this is purely a "don't make staff guess" convenience.
        const CLINIC_TIMES = ["08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
            "13:30", "14:00", "14:30", "15:00", "15:30", "16:00"];

        const availabilityGroup = document.getElementById("availabilityGroup");
        const availabilityGrid = document.getElementById("availabilityGrid");

        async function refreshAvailability() {
            const dentistId = dentistSelect.value;
            const date = dateInput.value;
            if (!dentistId || !date) {
                availabilityGroup.style.display = "none";
                return;
            }
            availabilityGroup.style.display = "block";
            availabilityGrid.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Checking availability...</div>`;
            try {
                const existing = await Api.get(`/appointments?date=${date}&dentistId=${dentistId}`);
                const bookedTimes = new Set(existing.filter(a => a.status !== "CANCELLED")
                    .map(a => a.appointmentTime.slice(0, 5)));
                renderAvailabilityGrid(bookedTimes);
            } catch (err) {
                availabilityGrid.innerHTML = `<div class="field-error visible">${escapeHtml(err.message)}</div>`;
            }
        }

        function renderAvailabilityGrid(bookedTimes) {
            availabilityGrid.innerHTML = CLINIC_TIMES.map(t => {
                const isBooked = bookedTimes.has(t);
                const isSelected = timeInput.value === t;
                const classes = ["availability-slot"];
                if (isBooked) classes.push("booked");
                if (isSelected && !isBooked) classes.push("selected");
                return `<button type="button" class="${classes.join(" ")}" data-time="${t}" ${isBooked ? "disabled" : ""}>${Fmt.time(t)}</button>`;
            }).join("");

            availabilityGrid.querySelectorAll(".availability-slot:not(.booked)").forEach(btn => {
                btn.addEventListener("click", () => {
                    timeInput.value = btn.dataset.time;
                    setFieldError({ input: timeInput, error: document.getElementById("appointmentTimeError") }, false);
                    availabilityGrid.querySelectorAll(".availability-slot").forEach(b => b.classList.remove("selected"));
                    btn.classList.add("selected");
                });
            });
        }

        dentistSelect.addEventListener("change", refreshAvailability);
        dateInput.addEventListener("change", refreshAvailability);

        // --- Validation + submit -----------------------------------------------------

        function validate() {
            let valid = true;
            for (const field of fields) {
                if (field.input.value.trim() === "") {
                    setFieldError(field, true);
                    valid = false;
                } else {
                    setFieldError(field, false);
                }
            }
            if (usingExistingPatient) {
                if (!selectedPatientId) {
                    setFieldError({ input: patientSearch, error: patientIdError }, true);
                    valid = false;
                }
            } else {
                const nameField = { input: document.getElementById("patientName"), error: document.getElementById("patientNameError") };
                const contactField = { input: document.getElementById("contactNumber"), error: document.getElementById("contactNumberError") };
                const addressField = { input: document.getElementById("address"), error: document.getElementById("addressError") };
                for (const f of [nameField, contactField, addressField]) {
                    if (f.input.value.trim() === "") {
                        setFieldError(f, true);
                        valid = false;
                    } else {
                        setFieldError(f, false);
                    }
                }
            }
            return valid;
        }

        function showSuccess(appointment) {
            form.style.display = "none";
            successPanel.style.display = "block";
            document.getElementById("resultNumber").textContent = appointment.appointmentNumber;
            document.getElementById("resultPatient").textContent = appointment.patientName;
            document.getElementById("resultDentist").textContent = appointment.dentistName;
            document.getElementById("resultDate").textContent = Fmt.date(appointment.appointmentDate);
            document.getElementById("resultTime").textContent = Fmt.time(appointment.appointmentTime);

            document.getElementById("viewAppointmentBtn").onclick = () => {
                window.location.href = "search.html?number=" + encodeURIComponent(appointment.appointmentNumber);
            };
            document.getElementById("viewPatientBtn").onclick = () => {
                window.location.href = "patients.html?id=" + appointment.patientId;
            };
            document.getElementById("registerAnotherBtn").onclick = () => {
                window.location.reload();
            };
            successPanel.scrollIntoView({ behavior: "smooth", block: "start" });
            Toast.success("Appointment booked successfully.");
        }

        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            hideAlert();

            if (!validate()) {
                return;
            }

            submitButton.disabled = true;
            submitButton.textContent = "Registering...";

            const payload = {
                dentistId: dentistSelect.value,
                treatmentId: treatmentSelect.value,
                appointmentDate: dateInput.value,
                appointmentTime: timeInput.value,
            };
            if (usingExistingPatient) {
                payload.patientId = selectedPatientId;
            } else {
                payload.patientName = document.getElementById("patientName").value.trim();
                payload.address = document.getElementById("address").value.trim();
                payload.contactNumber = document.getElementById("contactNumber").value.trim();
            }

            try {
                const result = await Api.post("/appointments", payload);
                showSuccess(result.appointment);
            } catch (err) {
                showAlert(err.message);
            } finally {
                submitButton.disabled = false;
                submitButton.textContent = "Register Appointment";
            }
        });

        loadDentists();
        loadTreatments();
    }
})();
