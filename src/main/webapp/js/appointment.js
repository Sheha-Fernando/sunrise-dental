(function () {
    const form = document.getElementById("appointmentForm");
    const formAlert = document.getElementById("formAlert");
    const submitButton = document.getElementById("submitButton");
    const successPanel = document.getElementById("successPanel");

    const dentistSelect = document.getElementById("dentistId");
    const treatmentSelect = document.getElementById("treatmentId");
    const dateInput = document.getElementById("appointmentDate");

    dateInput.min = new Date().toISOString().split("T")[0];

    const fields = [
        { input: document.getElementById("patientName"), error: document.getElementById("patientNameError"), message: "Patient name is required." },
        { input: document.getElementById("contactNumber"), error: document.getElementById("contactNumberError"), message: "Contact number is required." },
        { input: document.getElementById("address"), error: document.getElementById("addressError"), message: "Address is required." },
        { input: document.getElementById("appointmentNumber"), error: document.getElementById("appointmentNumberError"), message: "Appointment number is required." },
        { input: dentistSelect, error: document.getElementById("dentistIdError"), message: "Please select a dentist." },
        { input: treatmentSelect, error: document.getElementById("treatmentIdError"), message: "Please select a treatment." },
        { input: dateInput, error: document.getElementById("appointmentDateError"), message: "Please select an appointment date." },
        { input: document.getElementById("appointmentTime"), error: document.getElementById("appointmentTimeError"), message: "Please select an appointment time." },
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

    function validate() {
        let valid = true;
        for (const field of fields) {
            const empty = field.input.value.trim() === "";
            if (empty) {
                setFieldError(field, true);
                valid = false;
            } else {
                setFieldError(field, false);
            }
        }
        return valid;
    }

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
        div.textContent = value;
        return div.innerHTML;
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
        document.getElementById("registerAnotherBtn").onclick = () => {
            form.reset();
            form.style.display = "block";
            successPanel.style.display = "none";
            dateInput.min = new Date().toISOString().split("T")[0];
        };
        successPanel.scrollIntoView({ behavior: "smooth", block: "start" });
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        hideAlert();

        if (!validate()) {
            return;
        }

        submitButton.disabled = true;
        submitButton.textContent = "Registering...";

        try {
            const result = await Api.post("/appointments", {
                appointmentNumber: document.getElementById("appointmentNumber").value.trim(),
                patientName: document.getElementById("patientName").value.trim(),
                address: document.getElementById("address").value.trim(),
                contactNumber: document.getElementById("contactNumber").value.trim(),
                dentistId: dentistSelect.value,
                treatmentId: treatmentSelect.value,
                appointmentDate: dateInput.value,
                appointmentTime: document.getElementById("appointmentTime").value,
            });
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
})();
