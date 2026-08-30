(function () {
    let currentAppointment = null;

    document.addEventListener("shell:ready", init);

    function init() {
        const content = document.getElementById("pageContent");
        content.appendChild(document.getElementById("pageTemplate").content.cloneNode(true));

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
            if (!currentAppointment) return;
            hideAlert();
            generateBillBtn.disabled = true;
            generateBillBtn.textContent = "Generating bill...";

            try {
                const result = await Api.post("/bills?appointmentNumber="
                    + encodeURIComponent(currentAppointment.appointmentNumber), {});
                renderReceipt(currentAppointment, result.bill);
                Toast.success("Bill generated successfully.");
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

        printBillBtn.addEventListener("click", () => window.print());

        loadRecentBills();

        const prefill = new URLSearchParams(window.location.search).get("number");
        if (prefill) {
            numberInput.value = prefill;
            findAppointment(prefill);
        }
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    let allBills = [];

    async function loadRecentBills() {
        const container = document.getElementById("recentBillsContainer");
        try {
            allBills = await Api.get("/bills");
            const todayIso = new Date().toISOString().split("T")[0];
            const todaysBills = allBills.filter(b => b.billDate.startsWith(todayIso));
            const revenue = todaysBills.reduce((sum, b) => sum + Number(b.totalAmount), 0);

            document.getElementById("statCards").innerHTML = `
                <div class="stat-card"><div class="stat-label">Today's Bills</div><div class="stat-value">${todaysBills.length}</div></div>
                <div class="stat-card"><div class="stat-label">Today's Revenue</div><div class="stat-value accent">${Fmt.currency(revenue)}</div></div>
            `;

            document.getElementById("billSearch").addEventListener("input", renderBillList);
            renderBillList();
        } catch (err) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">We couldn't load recent bills</div>
                <div class="empty-desc">${escapeHtml(err.message || "Please try again.")}</div>
            </div>`;
        }
    }

    function renderBillList() {
        const container = document.getElementById("recentBillsContainer");
        const search = document.getElementById("billSearch").value.trim().toLowerCase();

        const filtered = search
            ? allBills.filter(b => b.patientName.toLowerCase().includes(search)
                || b.appointmentNumber.toLowerCase().includes(search))
            : allBills;

        if (allBills.length === 0) {
            container.innerHTML = `<div class="empty-state"><div class="empty-title">No bills found</div></div>`;
            return;
        }
        if (filtered.length === 0) {
            container.innerHTML = `<div class="empty-state">
                <div class="empty-title">No bills found</div>
                <div class="empty-desc">Try a different patient name or appointment number.</div>
            </div>`;
            return;
        }
        const rows = filtered.map(b => `
            <tr class="clickable" onclick="window.location.href='billing.html?number=${encodeURIComponent(b.appointmentNumber)}'">
                <td class="table-primary-text">${escapeHtml(b.appointmentNumber)}</td>
                <td>${escapeHtml(b.patientName)}</td>
                <td>${escapeHtml(b.dentistName)}</td>
                <td>${Fmt.currency(b.totalAmount)}</td>
                <td class="table-muted-text">${b.billDate.replace("T", " ")}</td>
            </tr>`).join("");
        container.innerHTML = `<div class="table-wrap"><table class="data-table">
            <thead><tr><th>Appointment #</th><th>Patient</th><th>Dentist</th><th>Total</th><th>Bill Date</th></tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }
})();
