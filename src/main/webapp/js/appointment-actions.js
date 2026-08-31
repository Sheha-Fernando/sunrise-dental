/**
 * Shared appointment status-change actions (cancel / mark completed / reschedule)
 * used by both the appointments list page and the appointment detail page, so the
 * confirmation modals, API calls and success/error messaging live in one place
 * instead of being duplicated per page. The backend remains the sole authority
 * on whether an action is actually permitted - this module only decides whether
 * to *show* a control, never whether the resulting request succeeds.
 */
const AppointmentActions = (() => {
    const CLINIC_TIMES = ["08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
        "13:30", "14:00", "14:30", "15:00", "15:30", "16:00"];

    const registry = new Map();
    let session = null;
    let host = null;

    function init(currentSession) {
        session = currentSession;
    }

    function register(appointment) {
        if (appointment && appointment.appointmentNumber) {
            registry.set(appointment.appointmentNumber, appointment);
        }
    }

    function registerAll(appointments) {
        (appointments || []).forEach(register);
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : value;
        return div.innerHTML;
    }

    function canManage() {
        return !!session && (session.role === "ADMIN" || session.role === "RECEPTIONIST");
    }

    function canComplete() {
        return !!session && (session.role === "ADMIN" || session.role === "DENTIST");
    }

    function canBill() {
        return !!session && (session.role === "ADMIN" || session.role === "RECEPTIONIST" || session.role === "BILLING");
    }

    /** Compact "View · Reschedule · Cancel" style action list for a table row or detail page. */
    function actionsHtml(appointment, viewHref) {
        register(appointment);
        const links = [`<a href="${viewHref}">View</a>`];

        if (appointment.status === "SCHEDULED") {
            if (canManage()) {
                links.push(`<button type="button" class="link-btn" data-ap-action="reschedule" data-ap-number="${escapeHtml(appointment.appointmentNumber)}">Reschedule</button>`);
                links.push(`<button type="button" class="link-btn link-btn-danger" data-ap-action="cancel" data-ap-number="${escapeHtml(appointment.appointmentNumber)}">Cancel</button>`);
            }
            if (canComplete()) {
                links.push(`<button type="button" class="link-btn" data-ap-action="complete" data-ap-number="${escapeHtml(appointment.appointmentNumber)}">Mark Completed</button>`);
            }
        } else if (appointment.status === "COMPLETED" && canBill()) {
            links.push(`<button type="button" class="link-btn" data-ap-action="bill" data-ap-number="${escapeHtml(appointment.appointmentNumber)}">Generate Bill</button>`);
        }

        return `<div class="action-links">${links.join('<span class="action-sep">&middot;</span>')}</div>`;
    }

    /**
     * Delegated click handler - attach once per page via
     * `container.addEventListener("click", AppointmentActions.delegate)`.
     * Using delegation (rather than inline onclick) keeps appointment data
     * out of HTML attributes entirely, so nothing needs manual escaping.
     */
    function delegate(event, onDone) {
        const target = event.target.closest("[data-ap-action]");
        if (!target) return;
        event.preventDefault();
        event.stopPropagation();
        const number = target.dataset.apNumber;
        const action = target.dataset.apAction;
        if (action === "cancel") openCancel(number, onDone);
        else if (action === "reschedule") openReschedule(number, onDone);
        else if (action === "complete") complete(number, onDone);
        else if (action === "bill") window.location.href = "billing.html?number=" + encodeURIComponent(number);
    }

    function ensureHost() {
        if (!host) {
            host = document.createElement("div");
            host.id = "appointmentActionModalHost";
            document.body.appendChild(host);
        }
        return host;
    }

    function closeModals() {
        ensureHost().innerHTML = "";
    }

    function apDateTimeLabel(a) {
        return `${Fmt.date(a.appointmentDate)}, ${Fmt.time(a.appointmentTime)}`;
    }

    // --- Cancel -----------------------------------------------------------------

    function openCancel(appointmentNumber, onDone) {
        const a = registry.get(appointmentNumber);
        if (!a) return;
        ensureHost().innerHTML = `
            <div class="modal-overlay open" id="apCancelOverlay">
                <div class="modal" role="dialog" aria-modal="true" aria-labelledby="apCancelTitle">
                    <button type="button" class="modal-close" id="apCancelClose" aria-label="Close">&times;</button>
                    <h2 id="apCancelTitle">Cancel appointment?</h2>
                    <div class="detail-grid" style="margin:1rem 0;">
                        <div class="detail-item">
                            <div class="detail-label">Appointment</div>
                            <div class="detail-value">${escapeHtml(a.appointmentNumber)}</div>
                        </div>
                        <div class="detail-item">
                            <div class="detail-label">Patient</div>
                            <div class="detail-value">${escapeHtml(a.patientName)}</div>
                        </div>
                        <div class="detail-item">
                            <div class="detail-label">Date</div>
                            <div class="detail-value">${apDateTimeLabel(a)}</div>
                        </div>
                        <div class="detail-item">
                            <div class="detail-label">Dentist</div>
                            <div class="detail-value">${escapeHtml(a.dentistName)}</div>
                        </div>
                    </div>
                    <p style="color:var(--color-text-muted); margin-bottom:1rem;">Are you sure you want to cancel this appointment?</p>
                    <div class="form-group">
                        <label for="apCancelReasonSelect">Reason for cancellation (optional)</label>
                        <select id="apCancelReasonSelect">
                            <option value="">Select a reason...</option>
                            <option value="Patient requested cancellation">Patient requested cancellation</option>
                            <option value="Patient unavailable">Patient unavailable</option>
                            <option value="Dentist unavailable">Dentist unavailable</option>
                            <option value="Clinic scheduling change">Clinic scheduling change</option>
                            <option value="Other">Other</option>
                        </select>
                        <input type="text" id="apCancelReasonOther" placeholder="Please specify..." style="display:none; margin-top:0.6rem;">
                    </div>
                    <div id="apCancelAlert" class="alert alert-error" role="alert"></div>
                    <div class="btn-row">
                        <button type="button" class="btn btn-secondary" id="apKeepBtn">Keep Appointment</button>
                        <button type="button" class="btn btn-danger" id="apConfirmCancelBtn">Cancel Appointment</button>
                    </div>
                </div>
            </div>`;

        const overlay = document.getElementById("apCancelOverlay");
        const reasonSelect = document.getElementById("apCancelReasonSelect");
        const reasonOther = document.getElementById("apCancelReasonOther");
        const alertEl = document.getElementById("apCancelAlert");
        const confirmBtn = document.getElementById("apConfirmCancelBtn");

        reasonSelect.addEventListener("change", () => {
            reasonOther.style.display = reasonSelect.value === "Other" ? "block" : "none";
        });
        document.getElementById("apKeepBtn").addEventListener("click", closeModals);
        document.getElementById("apCancelClose").addEventListener("click", closeModals);
        overlay.addEventListener("click", (e) => { if (e.target === overlay) closeModals(); });

        confirmBtn.addEventListener("click", async () => {
            const reason = reasonSelect.value === "Other" ? reasonOther.value.trim() : reasonSelect.value;
            confirmBtn.disabled = true;
            confirmBtn.textContent = "Cancelling...";
            alertEl.classList.remove("visible");
            try {
                const result = await Api.put(`/appointments/${encodeURIComponent(appointmentNumber)}/status?status=CANCELLED`
                    + (reason ? `&reason=${encodeURIComponent(reason)}` : ""));
                register(result.appointment);
                closeModals();
                Toast.success(result.message || `Appointment ${appointmentNumber} has been cancelled successfully.`);
                if (onDone) onDone(result.appointment);
            } catch (err) {
                alertEl.textContent = err.message || "We couldn't update the appointment right now. Please try again.";
                alertEl.classList.add("visible");
                confirmBtn.disabled = false;
                confirmBtn.textContent = "Cancel Appointment";
            }
        });
    }

    // --- Mark completed -----------------------------------------------------------

    async function complete(appointmentNumber, onDone) {
        try {
            const result = await Api.put(`/appointments/${encodeURIComponent(appointmentNumber)}/status?status=COMPLETED`);
            register(result.appointment);
            Toast.success(result.message || `Appointment ${appointmentNumber} has been marked as completed.`);
            if (onDone) onDone(result.appointment);
        } catch (err) {
            Toast.error(err.message || "We couldn't update the appointment right now. Please try again.");
        }
    }

    // --- Reschedule -----------------------------------------------------------------

    function openReschedule(appointmentNumber, onDone) {
        const a = registry.get(appointmentNumber);
        if (!a) return;
        ensureHost().innerHTML = `
            <div class="modal-overlay open" id="apRescheduleOverlay">
                <div class="modal" role="dialog" aria-modal="true" aria-labelledby="apRescheduleTitle">
                    <button type="button" class="modal-close" id="apRescheduleClose" aria-label="Close">&times;</button>
                    <h2 id="apRescheduleTitle">Reschedule Appointment</h2>
                    <div class="detail-grid" style="margin:1rem 0;">
                        <div class="detail-item">
                            <div class="detail-label">Appointment</div>
                            <div class="detail-value">${escapeHtml(a.appointmentNumber)}</div>
                        </div>
                        <div class="detail-item">
                            <div class="detail-label">Patient</div>
                            <div class="detail-value">${escapeHtml(a.patientName)}</div>
                        </div>
                        <div class="detail-item" style="grid-column: 1 / -1;">
                            <div class="detail-label">Current</div>
                            <div class="detail-value">${apDateTimeLabel(a)} &middot; ${escapeHtml(a.dentistName)}</div>
                        </div>
                    </div>
                    <div class="form-group">
                        <label for="apRDentist">Dentist</label>
                        <select id="apRDentist"><option value="">Loading dentists...</option></select>
                    </div>
                    <div class="form-group">
                        <label for="apRDate">Date</label>
                        <input type="date" id="apRDate" value="${a.appointmentDate}">
                    </div>
                    <div class="form-group" id="apRAvailabilityGroup" style="display:none;">
                        <label>Time</label>
                        <div class="availability-grid" id="apRAvailabilityGrid"></div>
                    </div>
                    <div id="apRescheduleAlert" class="alert alert-error" role="alert"></div>
                    <div class="btn-row">
                        <button type="button" class="btn btn-secondary" id="apRescheduleCancelBtn">Cancel</button>
                        <button type="button" class="btn btn-primary" id="apRescheduleSubmitBtn" disabled>Reschedule Appointment</button>
                    </div>
                </div>
            </div>`;

        const overlay = document.getElementById("apRescheduleOverlay");
        const dentistSelect = document.getElementById("apRDentist");
        const dateInput = document.getElementById("apRDate");
        const availGroup = document.getElementById("apRAvailabilityGroup");
        const availGrid = document.getElementById("apRAvailabilityGrid");
        const alertEl = document.getElementById("apRescheduleAlert");
        const submitBtn = document.getElementById("apRescheduleSubmitBtn");
        dateInput.min = new Date().toISOString().split("T")[0];

        let selectedTime = a.appointmentTime.slice(0, 5);

        document.getElementById("apRescheduleClose").addEventListener("click", closeModals);
        document.getElementById("apRescheduleCancelBtn").addEventListener("click", closeModals);
        overlay.addEventListener("click", (e) => { if (e.target === overlay) closeModals(); });

        function updateSubmitState() {
            submitBtn.disabled = !(dentistSelect.value && dateInput.value && selectedTime);
        }

        async function refreshAvailability() {
            if (!dentistSelect.value || !dateInput.value) {
                availGroup.style.display = "none";
                return;
            }
            availGroup.style.display = "block";
            availGrid.innerHTML = `<div class="loading-inline"><span class="spinner"></span> Checking availability...</div>`;
            try {
                const existing = await Api.get(`/appointments?date=${dateInput.value}&dentistId=${dentistSelect.value}`);
                const bookedTimes = new Set(existing
                    .filter(x => x.status !== "CANCELLED" && x.appointmentNumber !== appointmentNumber)
                    .map(x => x.appointmentTime.slice(0, 5)));
                renderGrid(bookedTimes);
            } catch (err) {
                availGrid.innerHTML = `<div class="field-error visible">${escapeHtml(err.message)}</div>`;
            }
        }

        function renderGrid(bookedTimes) {
            availGrid.innerHTML = CLINIC_TIMES.map(t => {
                const isBooked = bookedTimes.has(t);
                const isSelected = selectedTime === t;
                const classes = ["availability-slot"];
                if (isBooked) classes.push("booked");
                if (isSelected && !isBooked) classes.push("selected");
                return `<button type="button" class="${classes.join(" ")}" data-time="${t}" ${isBooked ? "disabled" : ""}>${Fmt.time(t)}</button>`;
            }).join("");
            availGrid.querySelectorAll(".availability-slot:not(.booked)").forEach(btn => {
                btn.addEventListener("click", () => {
                    selectedTime = btn.dataset.time;
                    availGrid.querySelectorAll(".availability-slot").forEach(b => b.classList.remove("selected"));
                    btn.classList.add("selected");
                    updateSubmitState();
                });
            });
            updateSubmitState();
        }

        dentistSelect.addEventListener("change", () => { refreshAvailability(); });
        dateInput.addEventListener("change", () => { refreshAvailability(); });

        (async () => {
            try {
                const dentists = await Api.get("/dentists");
                dentistSelect.innerHTML = dentists.map(d =>
                    `<option value="${d.dentistId}" ${d.dentistId === a.dentistId ? "selected" : ""}>${escapeHtml(d.dentistName)}</option>`).join("");
                refreshAvailability();
            } catch (err) {
                dentistSelect.innerHTML = '<option value="">Unable to load dentists</option>';
            }
        })();

        submitBtn.addEventListener("click", async () => {
            alertEl.classList.remove("visible");
            submitBtn.disabled = true;
            submitBtn.textContent = "Rescheduling...";
            try {
                const result = await Api.put(`/appointments/${encodeURIComponent(appointmentNumber)}/reschedule`
                    + `?dentistId=${encodeURIComponent(dentistSelect.value)}`
                    + `&appointmentDate=${encodeURIComponent(dateInput.value)}`
                    + `&appointmentTime=${encodeURIComponent(selectedTime)}`);
                register(result.appointment);
                closeModals();
                Toast.success(result.message || `Appointment ${appointmentNumber} has been rescheduled successfully.`);
                if (onDone) onDone(result.appointment);
            } catch (err) {
                alertEl.textContent = err.message || "We couldn't update the appointment right now. Please try again.";
                alertEl.classList.add("visible");
                submitBtn.disabled = false;
                submitBtn.textContent = "Reschedule Appointment";
            }
        });
    }

    return { init, register, registerAll, actionsHtml, delegate, openCancel, openReschedule, complete };
})();
