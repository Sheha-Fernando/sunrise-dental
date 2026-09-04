(function () {
    const lookupForm = document.getElementById("lookupForm");
    const lookupCard = document.getElementById("lookupCard");
    const numberInput = document.getElementById("appointmentNumber");
    const numberError = document.getElementById("appointmentNumberError");
    const lookupButton = document.getElementById("lookupButton");
    const billingAlert = document.getElementById("billingAlert");
    const appointmentSummary = document.getElementById("appointmentSummary");
    const receiptCard = document.getElementById("receiptCard");
    const generateBillBtn = document.getElementById("generateBillBtn");
    const printBillBtn = document.getElementById("printBillBtn");

    let currentAppointment = null;

    function showAlert(message) {
        billingAlert.textContent = message;
        billingAlert.classList.add("visible");
    }

    function hideAlert() {
        billingAlert.classList.remove("visible");
        billingAlert.textContent = "";
    }

    function renderAppointmentSummary(appointment) {
        currentAppointment = appointment;
        appointmentSummary.style.display = "block";
        receiptCard.style.display = "none";
        document.getElementById("summaryPatient").textContent = appointment.patientName;
        document.getElementById("summaryDentist").textContent = appointment.dentistName;
        document.getElementById("summaryTreatment").textContent = appointment.treatmentType;
        document.getElementById("summaryStatus").innerHTML =
            `<span class="${Fmt.statusBadgeClass(appointment.status)}">${Fmt.statusLabel(appointment.status)}</span>`;
    }

    function renderReceipt(appointment, bill) {
        lookupCard.style.display = "none";
        appointmentSummary.style.display = "none";
        billingAlert.classList.remove("visible");
        receiptCard.style.display = "block";

        document.getElementById("billNumber").textContent = appointment.appointmentNumber;
        document.getElementById("billPatient").textContent = appointment.patientName;
        document.getElementById("billDentist").textContent = appointment.dentistName;
        document.getElementById("billTreatment").textContent = appointment.treatmentType;
        document.getElementById("billDate").textContent = bill.billDate ? bill.billDate.replace("T", " ") : "";
        document.getElementById("billConsultation").textContent = Fmt.currency(bill.consultationFee);
        document.getElementById("billTreatmentCost").textContent = Fmt.currency(bill.treatmentCost);
        document.getElementById("billTotal").textContent = Fmt.currency(bill.totalAmount);
    }

    async function findAppointment(appointmentNumber) {
        hideAlert();
        appointmentSummary.style.display = "none";
        lookupButton.disabled = true;
        lookupButton.textContent = "Searching...";
        try {
            const result = await Api.get("/appointments/" + encodeURIComponent(appointmentNumber));
            renderAppointmentSummary(result.appointment);
        } catch (err) {
            showAlert(err.message || "Appointment not found. Please check the appointment number and try again.");
        } finally {
            lookupButton.disabled = false;
            lookupButton.textContent = "Find Appointment";
        }
    }

    lookupForm.addEventListener("submit", (event) => {
        event.preventDefault();
        const value = numberInput.value.trim();
        if (!value) {
            numberInput.setAttribute("aria-invalid", "true");
            numberError.classList.add("visible");
            return;
        }
        numberInput.removeAttribute("aria-invalid");
        numberError.classList.remove("visible");
        findAppointment(value);
    });

    generateBillBtn.addEventListener("click", async () => {
        if (!currentAppointment) {
            return;
        }
        hideAlert();
        generateBillBtn.disabled = true;
        generateBillBtn.textContent = "Generating bill...";

        try {
            const result = await Api.post("/bills?appointmentNumber="
                + encodeURIComponent(currentAppointment.appointmentNumber), {});
            renderReceipt(currentAppointment, result.bill);
        } catch (err) {
            if (err.message === "A bill has already been generated for this appointment.") {
                try {
                    const existing = await Api.get("/bills?appointmentNumber="
                        + encodeURIComponent(currentAppointment.appointmentNumber));
                    renderReceipt(currentAppointment, existing.bill);
                    return;
                } catch (lookupErr) {
                    showAlert(lookupErr.message);
                    return;
                }
            }
            showAlert(err.message || "Unable to generate bill.");
        } finally {
            generateBillBtn.disabled = false;
            generateBillBtn.textContent = "Generate Bill";
        }
    });

    printBillBtn.addEventListener("click", () => {
        window.print();
    });

    const prefill = new URLSearchParams(window.location.search).get("number");
    if (prefill) {
        numberInput.value = prefill;
        findAppointment(prefill);
    }
})();
