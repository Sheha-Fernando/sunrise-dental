(function () {
    const form = document.getElementById("searchForm");
    const numberInput = document.getElementById("appointmentNumber");
    const numberError = document.getElementById("appointmentNumberError");
    const searchAlert = document.getElementById("searchAlert");
    const searchButton = document.getElementById("searchButton");
    const resultCard = document.getElementById("resultCard");

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

        document.getElementById("generateBillBtn").onclick = () => {
            window.location.href = "billing.html?number=" + encodeURIComponent(appointment.appointmentNumber);
        };
    }

    async function search(appointmentNumber) {
        hideAlert();
        searchButton.disabled = true;
        searchButton.textContent = "Searching...";
        try {
            const result = await Api.get("/appointments/" + encodeURIComponent(appointmentNumber));
            renderResult(result.appointment);
        } catch (err) {
            showAlert(err.message || "Appointment not found. Please check the appointment number and try again.");
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
})();
