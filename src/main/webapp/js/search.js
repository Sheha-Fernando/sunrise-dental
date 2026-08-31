(function () {
    let session = null;

    document.addEventListener("shell:ready", (e) => {
        session = e.detail;
        init();
    });

    function init() {
        AppointmentActions.init(session);

        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

        const form = document.getElementById("searchForm");
        const numberInput = document.getElementById("appointmentNumber");
        const numberError = document.getElementById("appointmentNumberError");
        const searchAlert = document.getElementById("searchAlert");
        const searchButton = document.getElementById("searchButton");
        const resultCard = document.getElementById("resultCard");
        const canBill = session.role === "ADMIN" || session.role === "RECEPTIONIST" || session.role === "BILLING";
        const canManage = session.role === "ADMIN" || session.role === "RECEPTIONIST";
        const canComplete = session.role === "ADMIN" || session.role === "DENTIST";

        document.getElementById("resultActions").addEventListener("click", (e) => {
            AppointmentActions.delegate(e, () => search(numberInput.value.trim()));
        });

        function showAlert(message) {
            resultCard.style.display = "none";
            searchAlert.textContent = message;
            searchAlert.classList.add("visible");
        }

        function hideAlert() {
            searchAlert.classList.remove("visible");
            searchAlert.textContent = "";
        }

        function renderResult(appointment) {
            AppointmentActions.register(appointment);
            resultCard.style.display = "block";
            document.getElementById("resultNumber").textContent = appointment.appointmentNumber;
            document.getElementById("resultStatus").innerHTML =
                `<span class="${Fmt.statusBadgeClass(appointment.status)}">${Fmt.statusLabel(appointment.status)}</span>`;
            document.getElementById("resultPatient").textContent = appointment.patientName;
            document.getElementById("resultContact").textContent = appointment.contactNumber;
            document.getElementById("resultAddress").textContent = appointment.address;
            document.getElementById("resultDentist").textContent = appointment.dentistName;
            document.getElementById("resultTreatment").textContent = appointment.treatmentType;
            document.getElementById("resultDate").textContent = Fmt.date(appointment.appointmentDate);
            document.getElementById("resultTime").textContent = Fmt.time(appointment.appointmentTime);

            const reasonGroup = document.getElementById("resultReasonGroup");
            if (appointment.status === "CANCELLED" && appointment.cancellationReason) {
                document.getElementById("resultReason").textContent = appointment.cancellationReason;
                reasonGroup.style.display = "block";
            } else {
                reasonGroup.style.display = "none";
            }

            const actions = [];
            const number = appointment.appointmentNumber;
            if (appointment.status === "SCHEDULED") {
                if (canManage) {
                    actions.push(`<button type="button" class="btn btn-secondary" data-ap-action="reschedule" data-ap-number="${number}">Reschedule</button>`);
                    actions.push(`<button type="button" class="btn btn-danger" data-ap-action="cancel" data-ap-number="${number}">Cancel</button>`);
                }
                if (canComplete) {
                    actions.push(`<button type="button" class="btn btn-primary" data-ap-action="complete" data-ap-number="${number}">Mark Completed</button>`);
                }
            } else if (appointment.status === "COMPLETED" && canBill) {
                actions.push(`<button type="button" class="btn btn-primary" data-ap-action="bill" data-ap-number="${number}">Generate Bill</button>`);
            }
            document.getElementById("resultActions").innerHTML = actions.join("");
        }

        async function search(appointmentNumber) {
            hideAlert();
            searchButton.disabled = true;
            searchButton.textContent = "Searching...";
            try {
                const result = await Api.get("/appointments/" + encodeURIComponent(appointmentNumber));
                renderResult(result.appointment);
            } catch (err) {
                if (err.status === 403) {
                    showAlert("You are not authorized to access this appointment.");
                } else {
                    showAlert(err.message || "Appointment not found. Please check the appointment number and try again.");
                }
            } finally {
                searchButton.disabled = false;
                searchButton.textContent = "Search";
            }
        }

        form.addEventListener("submit", (event) => {
            event.preventDefault();
            const value = numberInput.value.trim();
            if (!value) {
                numberInput.setAttribute("aria-invalid", "true");
                numberError.classList.add("visible");
                return;
            }
            numberInput.removeAttribute("aria-invalid");
            numberError.classList.remove("visible");
            search(value);
        });

        const prefill = new URLSearchParams(window.location.search).get("number");
        if (prefill) {
            numberInput.value = prefill;
            search(prefill);
        }
    }
})();
